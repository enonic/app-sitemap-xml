package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.event.Event;
import com.enonic.xp.security.PrincipalKey;
import com.enonic.xp.security.auth.AuthenticationInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SitemapCacheTest
{
    private static final SitemapParams PARAMS = new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 50_000 );

    private static final SitemapCache.Key KEY =
        new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone" ), PARAMS );

    private static final AuthenticationInfo AUTH_INFO = AuthenticationInfo.unAuthenticated();

    private final AtomicInteger generated = new AtomicInteger();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final ExecutorService requests = Executors.newCachedThreadPool();

    private final MutableClock clock = new MutableClock( Instant.parse( "2024-01-01T00:00:00Z" ) );

    @TempDir
    private Path storage;

    private SitemapCache cache;

    @BeforeEach
    void setUp()
    {
        cache = new SitemapCache( executor, clock, Duration.ZERO, Duration.ZERO, storage );
    }

    @AfterEach
    void shutdown()
        throws InterruptedException
    {
        requests.shutdownNow();
        executor.shutdownNow();
        assertThat( executor.awaitTermination( 5, TimeUnit.SECONDS ) ).isTrue();
        assertThat( requests.awaitTermination( 5, TimeUnit.SECONDS ) ).isTrue();
    }

    @Test
    void generatesOnceAndServesFromCache()
    {
        final Sitemap first = cache.get( KEY, false, AUTH_INFO, this::generate );

        assertThat( cache.get( KEY, false, AUTH_INFO, this::generate ) ).isSameAs( first );
        assertThat( first.size() ).isEqualTo( 1 );
        assertThat( generated ).hasValue( 1 );
    }

    @Test
    void generatesInContextOfKey()
    {
        final List<String> seen = new ArrayList<>();
        final AuthenticationInfo authInfo =
            AuthenticationInfo.copyOf( AuthenticationInfo.unAuthenticated() ).principals( PrincipalKey.ofAnonymous() ).build();

        cache.get( KEY, false, authInfo, ( sink, cancelled ) -> {
            seen.add( ContextAccessor.current().getRepositoryId() + " " + ContextAccessor.current().getBranch() + " " +
                          ContextAccessor.current().getAuthInfo().getPrincipals() );
            return generate( sink, cancelled );
        } );

        assertThat( seen ).containsExactly( "com.enonic.cms.project master " + authInfo.getPrincipals() );
    }

    @Test
    void requestBoundSitemapIsDroppedOnChangeAndGeneratedOnNextRequest()
    {
        final Sitemap first = cache.get( KEY, false, AUTH_INFO, this::generate );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.other", "master" ) );
        cache.onEvent( nodeEvent( "node.updated", "com.enonic.cms.project", "draft" ) );
        assertThat( cache.size() ).isEqualTo( 1 );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        assertThat( cache.size() ).isZero();
        awaitIdle();
        assertThat( generated ).hasValue( 1 );

        assertThat( cache.get( KEY, false, AUTH_INFO, this::generate ) ).isNotSameAs( first );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void backgroundSitemapIsRegeneratedAfterGracePeriodWhilePreviousIsServed()
    {
        final SitemapCache delayed = new SitemapCache( executor, clock, Duration.ofMillis( 300 ), Duration.ZERO, storage );
        final Sitemap first = delayed.get( KEY, true, AUTH_INFO, this::generate );

        delayed.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        delayed.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        assertThat( delayed.get( KEY, true, AUTH_INFO, this::generate ) ).isSameAs( first );
        assertThat( generated ).hasValue( 1 );

        awaitUntil( () -> delayed.get( KEY, true, AUTH_INFO, this::generate ) != first );
        awaitIdle();
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void haltsRunningBackgroundGenerationWhenContentChangesAgain()
        throws Exception
    {
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final List<Boolean> halted = Collections.synchronizedList( new ArrayList<>() );
        final AtomicInteger runs = new AtomicInteger();
        final Sitemap first = cache.get( KEY, true, AUTH_INFO, ( sink, cancelled ) -> {
            if ( runs.incrementAndGet() == 2 )
            {
                started.countDown();
                await( release );
                halted.add( cancelled.getAsBoolean() );
            }
            return generate( sink, cancelled );
        } );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        assertThat( started.await( 5, TimeUnit.SECONDS ) ).isTrue();
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        assertThat( cache.get( KEY, true, AUTH_INFO, this::generate ) ).isSameAs( first );
        release.countDown();

        awaitUntil( () -> runs.get() == 3 );
        awaitIdle();
        assertThat( halted ).containsExactly( true );
        final Sitemap regenerated = cache.get( KEY, true, AUTH_INFO, this::generate );
        assertThat( regenerated ).isNotSameAs( first );
        try (Stream<Path> dirs = Files.list( storage ))
        {
            assertThat( dirs ).containsExactly( regenerated.directory() );
        }
    }

    @Test
    void restartsGenerationThatRequestsWaitForWhenContentChanges()
        throws Exception
    {
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final AtomicInteger runs = new AtomicInteger();
        final SitemapCache.Source source = ( sink, cancelled ) -> {
            if ( runs.incrementAndGet() == 1 )
            {
                started.countDown();
                await( release );
            }
            sink.accept( new SitemapEntry( "/run-" + runs.get(), SitemapParams.SITE_CONTENT_TYPE, null ) );
            return null;
        };

        final Future<Sitemap> waiting = requests.submit( () -> cache.get( KEY, false, AUTH_INFO, source ) );
        started.await( 5, TimeUnit.SECONDS );
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        release.countDown();

        assertThat( SitemapTest.text( waiting.get( 5, TimeUnit.SECONDS ).root() ) ).contains( "/run-2<" );
        assertThat( runs ).hasValue( 2 );
    }

    @Test
    void removesIdleBackgroundSitemapInsteadOfRegenerating()
    {
        cache.get( KEY, true, AUTH_INFO, this::generate );
        clock.now = clock.now.plus( SitemapCache.IDLE_TIMEOUT ).plusSeconds( 1 );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );

        assertThat( cache.size() ).isZero();
        awaitIdle();
        assertThat( generated ).hasValue( 1 );
    }

    @Test
    void repositoryAndPermissionEventsInvalidate()
    {
        cache.get( KEY, false, AUTH_INFO, this::generate );
        cache.onEvent( Event.create( "repository.deleted" ).value( "id", "com.enonic.cms.project" ).build() );
        assertThat( cache.size() ).isZero();

        cache.get( KEY, false, AUTH_INFO, this::generate );
        cache.onEvent( Event.create( "node.permissionsUpdated" )
                           .value( "id", "1" )
                           .value( "repo", "com.enonic.cms.project" )
                           .value( "branch", "master" )
                           .build() );
        assertThat( cache.size() ).isZero();
    }

    @Test
    void requestBoundSitemapIsGeneratedAgainWhenScheduledContentChanges()
    {
        final Instant validUntil = clock.now.plusSeconds( 3600 );
        cache.get( KEY, false, AUTH_INFO, ( sink, cancelled ) -> generate( validUntil ) );

        clock.now = validUntil.minusSeconds( 1 );
        cache.get( KEY, false, AUTH_INFO, this::generate );
        assertThat( generated ).hasValue( 1 );

        clock.now = validUntil;
        cache.get( KEY, false, AUTH_INFO, this::generate );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void backgroundSitemapIsGeneratedAgainWhenScheduledContentChanges()
    {
        final AtomicInteger runs = new AtomicInteger();
        final Sitemap first =
            cache.get( KEY, true, AUTH_INFO, ( sink, cancelled ) -> generate( runs.incrementAndGet() == 1 ? clock.now.minusMillis( 1 ) : null ) );

        awaitUntil( () -> generated.get() == 2 );
        awaitIdle();
        assertThat( cache.get( KEY, true, AUTH_INFO, this::generate ) ).isNotSameAs( first );
    }

    @Test
    void expiredBackgroundSitemapIsServedWhileGeneratedAgain()
    {
        final Instant validUntil = clock.now.plusSeconds( 3600 );
        final AtomicInteger runs = new AtomicInteger();
        final Sitemap first =
            cache.get( KEY, true, AUTH_INFO, ( sink, cancelled ) -> generate( runs.incrementAndGet() == 1 ? validUntil : null ) );
        clock.now = validUntil;

        assertThat( cache.get( KEY, true, AUTH_INFO, this::generate ) ).isSameAs( first );
        awaitUntil( () -> generated.get() >= 2 );
    }

    @Test
    void failedFirstGenerationIsNotCached()
    {
        assertThatThrownBy( () -> cache.get( KEY, false, AUTH_INFO, ( sink, cancelled ) -> {
            throw new IllegalStateException( "boom" );
        } ) ).isInstanceOf( IllegalStateException.class ).hasMessage( "boom" );

        cache.get( KEY, false, AUTH_INFO, this::generate );
        assertThat( generated ).hasValue( 1 );
        try (Stream<Path> dirs = Files.list( storage ))
        {
            assertThat( dirs ).hasSize( 1 );
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( e );
        }
    }

    @Test
    void failedRegenerationKeepsPreviousSitemap()
    {
        final AtomicInteger runs = new AtomicInteger();
        final Sitemap first = cache.get( KEY, true, AUTH_INFO, ( sink, cancelled ) -> {
            if ( runs.incrementAndGet() > 1 )
            {
                throw new IllegalStateException( "boom" );
            }
            return generate( sink, cancelled );
        } );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitUntil( () -> runs.get() == 2 );
        awaitIdle();

        assertThat( cache.get( KEY, true, AUTH_INFO, this::generate ) ).isSameAs( first );
    }

    @Test
    void requestsForSameSitemapShareOneGeneration()
        throws Exception
    {
        final CountDownLatch release = new CountDownLatch( 1 );
        final List<Future<Sitemap>> results = new ArrayList<>();
        for ( int i = 0; i < 5; i++ )
        {
            results.add( requests.submit( () -> cache.get( KEY, false, AUTH_INFO, ( sink, cancelled ) -> {
                await( release );
                return generate( sink, cancelled );
            } ) ) );
        }
        awaitUntil( () -> cache.waiting() == 5 );
        release.countDown();

        for ( final Future<Sitemap> result : results )
        {
            assertThat( result.get( 5, TimeUnit.SECONDS ) ).isSameAs( results.get( 0 ).get() );
        }
        assertThat( generated ).hasValue( 1 );
    }

    @Test
    void queuesAllGenerationsAndThrottlesOnlyWaitingRequests()
        throws Exception
    {
        final Sitemap ready = cache.get( key( "ready" ), false, AUTH_INFO, this::generate );
        final CountDownLatch release = new CountDownLatch( 1 );
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final SitemapCache.Source slow = ( sink, cancelled ) -> {
            maxRunning.accumulateAndGet( running.incrementAndGet(), Math::max );
            await( release );
            running.decrementAndGet();
            return generate( sink, cancelled );
        };

        final List<Future<Sitemap>> waiting = new ArrayList<>();
        for ( int i = 0; i < SitemapCache.MAX_WAITING; i++ )
        {
            final SitemapCache.Key key = key( "repo" + i );
            waiting.add( requests.submit( () -> cache.get( key, false, AUTH_INFO, slow ) ) );
        }
        awaitUntil( () -> cache.waiting() == SitemapCache.MAX_WAITING );

        assertThatThrownBy( () -> cache.get( key( "extra" ), false, AUTH_INFO, slow ) ).isInstanceOf( SitemapBusyException.class );
        assertThat( cache.get( key( "ready" ), false, AUTH_INFO, this::generate ) ).isSameAs( ready );

        release.countDown();
        for ( final Future<Sitemap> result : waiting )
        {
            assertThat( result.get( 5, TimeUnit.SECONDS ) ).isNotNull();
        }
        awaitUntil( () -> generated.get() == SitemapCache.MAX_WAITING + 2 );
        assertThat( maxRunning ).hasValue( 1 );

        // The throttled request queued its generation anyway, so the sitemap is ready when asked again.
        cache.get( key( "extra" ), false, AUTH_INFO, this::generate );
        assertThat( generated ).hasValue( SitemapCache.MAX_WAITING + 2 );
    }

    @Test
    void evictsLeastRecentlyUsedWhenFull()
    {
        for ( int i = 0; i <= SitemapCache.MAX_ENTRIES; i++ )
        {
            clock.now = clock.now.plusSeconds( 1 );
            cache.get( key( "repo" + i ), false, AUTH_INFO, this::generate );
        }
        clock.now = clock.now.plusSeconds( 1 );
        cache.get( key( "repo" + ( SitemapCache.MAX_ENTRIES + 1 ) ), false, AUTH_INFO, this::generate );

        assertThat( cache.size() ).isEqualTo( SitemapCache.MAX_ENTRIES );
        cache.get( key( "repo" + SitemapCache.MAX_ENTRIES ), false, AUTH_INFO, this::generate );
        assertThat( generated ).hasValue( SitemapCache.MAX_ENTRIES + 2 );
        cache.get( key( "repo0" ), false, AUTH_INFO, this::generate );
        assertThat( generated ).hasValue( SitemapCache.MAX_ENTRIES + 3 );
    }

    @Test
    void deletesFilesOfReplacedAndRemovedSitemaps()
        throws IOException
    {
        final Sitemap first = cache.get( KEY, true, AUTH_INFO, this::generate );
        assertThat( first.directory() ).startsWith( storage ).isDirectory();

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitUntil( () -> !Files.exists( first.directory() ) );
        final Sitemap second = cache.get( KEY, true, AUTH_INFO, this::generate );
        try (Stream<Path> dirs = Files.list( storage ))
        {
            assertThat( dirs ).containsExactly( second.directory() );
        }

        cache.onEvent( Event.create( "repository.deleted" ).value( "id", "com.enonic.cms.project" ).build() );
        awaitUntil( () -> !Files.exists( second.directory() ) );
    }

    @Test
    void cleansStorageOnStartAndStop()
        throws Exception
    {
        final Path stale = Files.createDirectories( storage.resolve( "stale" ) );
        final SitemapCache restarted = new SitemapCache( executor, clock, Duration.ZERO, Duration.ZERO, storage );
        assertThat( Files.exists( stale ) ).isFalse();

        restarted.get( KEY, false, AUTH_INFO, this::generate );
        final CountDownLatch blocked = new CountDownLatch( 1 );
        final Future<Sitemap> waiting = requests.submit( () -> restarted.get( key( "other" ), false, AUTH_INFO, ( sink, cancelled ) -> {
            blocked.countDown();
            await( new CountDownLatch( 1 ) );
            return null;
        } ) );
        blocked.await( 5, TimeUnit.SECONDS );
        restarted.deactivate();

        assertThat( Files.exists( storage ) ).isFalse();
        assertThatThrownBy( () -> waiting.get( 5, TimeUnit.SECONDS ) ).isInstanceOf( ExecutionException.class )
            .hasCauseInstanceOf( CancellationException.class );
    }

    private Instant generate( final java.util.function.Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        sink.accept( new SitemapEntry( "", SitemapParams.SITE_CONTENT_TYPE, null ) );
        return generate( null );
    }

    private Instant generate( final Instant validUntil )
    {
        generated.incrementAndGet();
        return validUntil;
    }

    private static SitemapCache.Key key( final String repo )
    {
        return new SitemapCache.Key( repo, "master", Set.of(), PARAMS );
    }

    private void awaitIdle()
    {
        try
        {
            executor.submit( () -> {
            } ).get( 5, TimeUnit.SECONDS );
        }
        catch ( Exception e )
        {
            throw new IllegalStateException( e );
        }
    }

    private static void awaitUntil( final BooleanSupplier condition )
    {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
        while ( !condition.getAsBoolean() )
        {
            if ( System.nanoTime() > deadline )
            {
                throw new AssertionError( "Condition not met in time" );
            }
            sleep( 5 );
        }
    }

    private static Event nodeEvent( final String type, final String repo, final String branch )
    {
        return Event.create( type )
            .value( "nodes", List.of( Map.of( "id", "1", "path", "/content/a", "repo", repo, "branch", branch ) ) )
            .build();
    }

    private static void sleep( final long millis )
    {
        try
        {
            Thread.sleep( millis );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException( e );
        }
    }

    private static void await( final CountDownLatch latch )
    {
        try
        {
            latch.await();
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new CancellationException( "Interrupted" );
        }
    }

    private static final class MutableClock
        extends Clock
    {
        private volatile Instant now;

        MutableClock( final Instant now )
        {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone( final ZoneId zone )
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }
}

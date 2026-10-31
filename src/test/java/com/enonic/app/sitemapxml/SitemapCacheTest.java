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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
    private static final SitemapParams PARAMS = new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10, 10 );

    private static final SitemapCache.Key KEY =
        new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone" ), PARAMS );

    private static final AuthenticationInfo AUTH_INFO = AuthenticationInfo.unAuthenticated();

    private final AtomicInteger generated = new AtomicInteger();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final MutableClock clock = new MutableClock( Instant.parse( "2024-01-01T00:00:00Z" ) );

    @TempDir
    private Path storage;

    private SitemapCache cache;

    @BeforeEach
    void setUp()
    {
        cache = new SitemapCache( executor, clock, Duration.ZERO, storage );
    }

    @AfterEach
    void shutdown()
        throws InterruptedException
    {
        executor.shutdownNow();
        assertThat( executor.awaitTermination( 5, TimeUnit.SECONDS ) ).isTrue();
    }

    @Test
    void cachesUntilInvalidatedByNodeEvent()
    {
        final Sitemap first = cache.get( KEY, AUTH_INFO, this::generate );
        assertThat( cache.get( KEY, AUTH_INFO, this::generate ) ).isSameAs( first );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.other", "master" ) );
        cache.onEvent( nodeEvent( "node.updated", "com.enonic.cms.project", "draft" ) );
        cache.get( KEY, AUTH_INFO, this::generate );
        assertThat( generated ).hasValue( 1 );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        assertThat( cache.get( KEY, AUTH_INFO, this::generate ) ).isNotSameAs( first );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void generatesInContextOfKey()
    {
        final List<String> seen = new ArrayList<>();
        final AuthenticationInfo authInfo =
            AuthenticationInfo.copyOf( AuthenticationInfo.unAuthenticated() ).principals( PrincipalKey.ofAnonymous() ).build();

        cache.get( KEY, authInfo, () -> {
            seen.add( ContextAccessor.current().getRepositoryId() + " " + ContextAccessor.current().getBranch() + " " +
                          ContextAccessor.current().getAuthInfo().getPrincipals() );
            return generate();
        } );

        assertThat( seen ).containsExactly( "com.enonic.cms.project master " + authInfo.getPrincipals() );
    }

    @Test
    void invalidatedByRepositoryEvent()
    {
        cache.get( KEY, AUTH_INFO, this::generate );

        cache.onEvent( Event.create( "repository.deleted" ).value( "id", "com.enonic.cms.project" ).build() );

        assertThat( cache.size() ).isZero();
    }

    @Test
    void invalidatedByPermissionsEvent()
    {
        cache.get( KEY, AUTH_INFO, this::generate );

        cache.onEvent( Event.create( "node.permissionsUpdated" )
                           .value( "id", "1" )
                           .value( "repo", "com.enonic.cms.project" )
                           .value( "branch", "master" )
                           .build() );

        assertThat( cache.size() ).isZero();
    }

    @Test
    void expiresWhenScheduledContentChanges()
    {
        final Instant validUntil = clock.now.plusSeconds( 3600 );

        cache.get( KEY, AUTH_INFO, () -> generate( validUntil ) );
        clock.now = validUntil.minusSeconds( 1 );
        cache.get( KEY, AUTH_INFO, () -> generate( validUntil ) );
        assertThat( generated ).hasValue( 1 );

        clock.now = validUntil;
        cache.get( KEY, AUTH_INFO, () -> generate( validUntil ) );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void failedGenerationIsNotCached()
    {
        assertThatThrownBy( () -> cache.get( KEY, AUTH_INFO, () -> {
            throw new IllegalStateException( "boom" );
        } ) ).isInstanceOf( IllegalStateException.class ).hasMessage( "boom" );

        cache.get( KEY, AUTH_INFO, this::generate );
        assertThat( generated ).hasValue( 1 );
    }

    @Test
    void generationsAreSerializedAndShared()
        throws Exception
    {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final ExecutorService requests = Executors.newFixedThreadPool( 6 );
        try
        {
            final List<Future<Sitemap>> results = new ArrayList<>();
            for ( int i = 0; i < 6; i++ )
            {
                final SitemapCache.Key key = new SitemapCache.Key( "repo" + ( i % 3 ), "master", Set.of(), PARAMS );
                results.add( requests.submit( () -> cache.get( key, AUTH_INFO, () -> {
                    maxRunning.accumulateAndGet( running.incrementAndGet(), Math::max );
                    sleep( 20 );
                    running.decrementAndGet();
                    return generate();
                } ) ) );
            }
            for ( final Future<Sitemap> result : results )
            {
                assertThat( result.get( 5, TimeUnit.SECONDS ) ).isNotNull();
            }
            assertThat( maxRunning ).hasValue( 1 );
            assertThat( generated ).hasValue( 3 );
        }
        finally
        {
            requests.shutdownNow();
        }
    }

    @Test
    void rejectsRequestsBeyondInFlightLimit()
        throws Exception
    {
        final CountDownLatch release = new CountDownLatch( 1 );
        final CountDownLatch started = new CountDownLatch( 1 );
        final ExecutorService requests = Executors.newFixedThreadPool( SitemapCache.MAX_IN_FLIGHT );
        try
        {
            final List<Future<Sitemap>> waiting = new ArrayList<>();
            waiting.add( requests.submit( () -> cache.get( KEY, AUTH_INFO, () -> {
                started.countDown();
                await( release );
                return generate();
            } ) ) );
            started.await();
            for ( int i = 1; i < SitemapCache.MAX_IN_FLIGHT; i++ )
            {
                waiting.add( requests.submit( () -> cache.get( KEY, AUTH_INFO, this::generate ) ) );
            }
            awaitUntil( () -> cache.inFlight() == SitemapCache.MAX_IN_FLIGHT );

            assertThatThrownBy( () -> cache.get( KEY, AUTH_INFO, this::generate ) ).isInstanceOf( SitemapBusyException.class );

            release.countDown();
            for ( final Future<Sitemap> result : waiting )
            {
                assertThat( result.get( 5, TimeUnit.SECONDS ) ).isNotNull();
            }
            assertThat( cache.get( KEY, AUTH_INFO, this::generate ) ).isNotNull();
            assertThat( generated ).hasValue( 1 );
        }
        finally
        {
            requests.shutdownNow();
        }
    }

    @Test
    void evictsLeastRecentlyUsedWhenFull()
    {
        for ( int i = 0; i <= SitemapCache.MAX_ENTRIES; i++ )
        {
            clock.now = clock.now.plusSeconds( 1 );
            cache.get( new SitemapCache.Key( "repo" + i, "master", Set.of(), PARAMS ), AUTH_INFO, this::generate );
        }

        assertThat( cache.size() ).isEqualTo( SitemapCache.MAX_ENTRIES );
        cache.get( new SitemapCache.Key( "repo" + SitemapCache.MAX_ENTRIES, "master", Set.of(), PARAMS ), AUTH_INFO, this::generate );
        cache.get( new SitemapCache.Key( "repo0", "master", Set.of(), PARAMS ), AUTH_INFO, this::generate );
        assertThat( generated ).hasValue( SitemapCache.MAX_ENTRIES + 2 );
    }

    @Test
    void writesSitemapsToStorageAndDeletesInvalidatedFiles()
        throws IOException
    {
        final Sitemap first = cache.get( KEY, AUTH_INFO, this::generate );
        assertThat( first.directory() ).startsWith( storage ).isDirectory();

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitUntil( () -> !Files.exists( first.directory() ) );

        final Sitemap second = cache.get( KEY, AUTH_INFO, this::generate );
        try (Stream<Path> dirs = Files.list( storage ))
        {
            assertThat( dirs ).containsExactly( second.directory() );
        }
    }

    @Test
    void deletesFilesOfExpiredSitemaps()
    {
        final Instant validUntil = clock.now.plusSeconds( 60 );
        final Sitemap first = cache.get( KEY, AUTH_INFO, () -> generate( validUntil ) );

        clock.now = validUntil;
        final Sitemap second = cache.get( KEY, AUTH_INFO, this::generate );

        awaitUntil( () -> !Files.exists( first.directory() ) );
        assertThat( second.directory() ).isDirectory();
    }

    @Test
    void cleansStorageOnStartAndStop()
        throws IOException
    {
        final Path stale = Files.createDirectories( storage.resolve( "stale" ) );
        final SitemapCache restarted = new SitemapCache( executor, clock, Duration.ZERO, storage );
        assertThat( stale ).doesNotExist();

        restarted.get( KEY, AUTH_INFO, this::generate );
        restarted.deactivate();

        assertThat( storage ).doesNotExist();
    }

    private SitemapContents generate()
    {
        return generate( null );
    }

    private SitemapContents generate( final Instant validUntil )
    {
        generated.incrementAndGet();
        return new SitemapContents( List.of(), validUntil );
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
            throw new IllegalStateException( e );
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

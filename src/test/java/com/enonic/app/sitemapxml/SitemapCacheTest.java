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
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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

    /**
     * A logged-in user's sitemap: generated on request.
     */
    private static final SitemapCache.Key KEY =
        new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone", "user:system:editor" ), PARAMS );

    /**
     * The public sitemap: generated again in the background.
     */
    private static final SitemapCache.Key PUBLIC_KEY =
        new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone", SitemapCache.Key.ANONYMOUS ), PARAMS );

    private static final AuthenticationInfo AUTH_INFO = AuthenticationInfo.unAuthenticated();

    private volatile SitemapCache.Source source = this::generate;

    private final AtomicBoolean current = new AtomicBoolean( true );

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
        cache = newCache( repositoryId -> executor, Duration.ZERO, Duration.ZERO );
    }

    private SitemapCache newCache( final java.util.function.Function<String, ExecutorService> generators, final Duration delay,
                                   final Duration retention )
    {
        return new SitemapCache( executor, generators, clock, delay, retention, storage, key -> source, key -> current.get() );
    }

    private Sitemap get( final SitemapCache cache, final SitemapCache.Key key, final SitemapCache.Source source )
    {
        this.source = source;
        return cache.get( key );
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
        final Sitemap first = get( cache, KEY, this::generate );

        assertThat( get( cache, KEY, this::generate ) ).isSameAs( first );
        assertThat( first.size() ).isEqualTo( 1 );
        assertThat( generated ).hasValue( 1 );
    }

    @Test
    void generatesInContextOfKey()
    {
        final List<String> seen = new ArrayList<>();
        get( cache, KEY, ( sink, cancelled ) -> {
            seen.add( ContextAccessor.current().getRepositoryId() + " " + ContextAccessor.current().getBranch() + " " +
                          ContextAccessor.current().getAuthInfo().getPrincipals() );
            return generate( sink, cancelled );
        } );

        assertThat( seen ).hasSize( 1 );
        assertThat( seen.get( 0 ) ).startsWith( "com.enonic.cms.project master " )
            .contains( "role:system.everyone" )
            .contains( "user:system:editor" );
    }

    @Test
    void requestBoundSitemapIsServedAfterChangeWhileGeneratedAgainOnNextRequest()
    {
        final Sitemap first = get( cache, KEY, this::generate );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.other", "master" ) );
        cache.onEvent( nodeEvent( "node.updated", "com.enonic.cms.project", "draft" ) );
        assertThat( get( cache, KEY, this::generate ) ).isSameAs( first );
        awaitIdle();
        assertThat( generated ).hasValue( 1 );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitIdle();
        assertThat( generated ).hasValue( 1 );

        assertThat( get( cache, KEY, this::generate ) ).isSameAs( first );
        awaitUntil( () -> get( cache, KEY, this::generate ) != first );
        awaitIdle();
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void backgroundSitemapIsRegeneratedAfterGracePeriodWhilePreviousIsServed()
    {
        final SitemapCache delayed = newCache( repositoryId -> executor, Duration.ofMillis( 300 ), Duration.ZERO );
        final Sitemap first = get( delayed, PUBLIC_KEY, this::generate );

        delayed.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        delayed.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        assertThat( get( delayed, PUBLIC_KEY, this::generate ) ).isSameAs( first );
        assertThat( generated ).hasValue( 1 );

        awaitUntil( () -> get( delayed, PUBLIC_KEY, this::generate ) != first );
        awaitIdle();
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void changeWhilePublicSitemapIsGeneratedIsFollowedByAnotherGeneration()
        throws Exception
    {
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final List<Boolean> halted = Collections.synchronizedList( new ArrayList<>() );
        final AtomicInteger runs = new AtomicInteger();
        final Sitemap first = get( cache, PUBLIC_KEY, ( sink, cancelled ) -> {
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
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        assertThat( cache.get( PUBLIC_KEY ) ).isSameAs( first );
        release.countDown();

        // not halted, and one more generation for both changes
        awaitUntil( () -> runs.get() == 3 );
        awaitIdle();
        assertThat( halted ).containsExactly( false );
        assertThat( runs ).hasValue( 3 );
        final Sitemap regenerated = cache.get( PUBLIC_KEY );
        assertThat( regenerated ).isNotSameAs( first );
        awaitUntil( () -> sitemapDirectories().equals( List.of( regenerated.directory() ) ) );
    }

    @Test
    void requestsWaitingForFirstGenerationGetItAlsoWhenContentChanges()
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

        final Future<Sitemap> waiting = requests.submit( () -> get( cache, KEY, source ) );
        started.await( 5, TimeUnit.SECONDS );
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        release.countDown();

        assertThat( SitemapTest.text( waiting.get( 5, TimeUnit.SECONDS ).root() ) ).contains( "/run-1<" );
        // generated again on the next request, which gets the first one meanwhile
        assertThat( cache.get( KEY ) ).isSameAs( waiting.get() );
        awaitUntil( () -> runs.get() == 2 );
    }

    @Test
    void changesElsewhereLeaveTheSitemapAlone()
    {
        final Sitemap first = get( cache, PUBLIC_KEY, this::generate );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", "/content/mysiteother/a" ) );
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", "/content/other" ) );
        cache.onEvent( nodeEvent( "node.updated", "com.enonic.cms.project", "draft", "/content/mysite/a" ) );
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.other", "master", "/content/mysite/a" ) );
        awaitIdle();
        assertThat( generated ).hasValue( 1 );
        assertThat( cache.get( PUBLIC_KEY ) ).isSameAs( first );

        // a move into the site, and a change above it
        cache.onEvent( Event.create( "node.moved" )
                           .value( "nodes", List.of( Map.of( "id", "1", "path", "/content/other/a", "newPath", "/content/mysite/a", "repo",
                                                             "com.enonic.cms.project", "branch", "master" ) ) )
                           .build() );
        awaitUntil( () -> generated.get() == 2 );
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", "/content" ) );
        awaitUntil( () -> generated.get() == 3 );
    }

    @Test
    void changedPathIsKeptTwoLevelsDeep()
    {
        assertThat( SitemapCache.changedPath( "/content/mysite/news/a" ) ).isEqualTo( "/content/mysite" );
        assertThat( SitemapCache.changedPath( "/content/mysite" ) ).isEqualTo( "/content/mysite" );
        assertThat( SitemapCache.changedPath( "/content" ) ).isEqualTo( "/content" );
        assertThat( SitemapCache.affects( "/content/mysite", PUBLIC_KEY ) ).isTrue();
        assertThat( SitemapCache.affects( "/content", PUBLIC_KEY ) ).isTrue();
        assertThat( SitemapCache.affects( "/", PUBLIC_KEY ) ).isTrue();
        assertThat( SitemapCache.affects( "/content/mysiteother", PUBLIC_KEY ) ).isFalse();
        assertThat( SitemapCache.affects( "/archive", PUBLIC_KEY ) ).isFalse();
    }

    @Test
    void sitemapOfLoggedInUserIsNotGeneratedInTheBackground()
    {
        final Sitemap first = get( cache, KEY, this::generate );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitIdle();
        assertThat( generated ).hasValue( 1 );

        assertThat( cache.get( KEY ) ).isSameAs( first );
        awaitUntil( () -> generated.get() == 2 );
    }

    @Test
    void deletesSitemapsOfLoggedInUsersNotRequestedForADay()
    {
        final Sitemap user = get( cache, KEY, this::generate );
        final Sitemap anonymous = get( cache, PUBLIC_KEY, this::generate );
        clock.now = clock.now.plus( SitemapCache.USER_RETENTION ).minusSeconds( 1 );
        cache.deleteUnused();
        assertThat( cache.size() ).isEqualTo( 2 );

        clock.now = clock.now.plusSeconds( 2 );
        cache.deleteUnused();
        assertThat( cache.size() ).isEqualTo( 1 );
        assertThat( cache.get( PUBLIC_KEY ) ).isSameAs( anonymous );
        awaitUntil( () -> !Files.exists( user.directory() ) );
    }

    @Test
    void publicSitemapNoLongerConfiguredIsDeletedInsteadOfGeneratedAgain()
    {
        final Sitemap first = get( cache, PUBLIC_KEY, this::generate );
        current.set( false );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitUntil( () -> cache.size() == 0 );
        awaitUntil( () -> !Files.exists( first.directory() ) );
        assertThat( generated ).hasValue( 1 );
    }

    @Test
    void generationReadsNothingWhenNoContentChanged()
    {
        final CountingSource contents = new CountingSource();
        final Sitemap first = get( cache, PUBLIC_KEY, contents );
        assertThat( contents.calls ).containsExactly( "write" );
        assertThat( first.count() ).isEqualTo( 3 );
        assertThat( first.cutoff() ).isEqualTo( clock.now.minus( SitemapCache.CUTOFF_MARGIN ) );

        // nothing changed: nothing read, the same files
        contents.calls.clear();
        clock.now = clock.now.plusSeconds( 10 );
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitUntil( () -> contents.calls.size() == 1 );
        awaitIdle();
        assertThat( contents.calls ).containsExactly( "count" );
        final Sitemap checked = cache.get( PUBLIC_KEY );
        assertThat( checked.directory() ).isEqualTo( first.directory() );
        assertThat( checked.cutoff() ).isEqualTo( clock.now.minus( SitemapCache.CUTOFF_MARGIN ) );

        // a change since: read whole
        contents.calls.clear();
        contents.changed = 1;
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitUntil( () -> contents.calls.size() == 2 );
        assertThat( contents.calls ).containsExactly( "count", "write" );

        // a content left: the count is lower
        awaitIdle();
        contents.calls.clear();
        contents.changed = 0;
        contents.total = 2;
        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitUntil( () -> contents.calls.size() == 2 );
        assertThat( contents.calls ).containsExactly( "count", "write" );
    }

    @Test
    void sitemapIsReadWholeWhenScheduledContentGoesOnlineOrOffline()
    {
        final CountingSource contents = new CountingSource();
        contents.nextChange = clock.now.plusSeconds( 60 );
        get( cache, KEY, contents );

        contents.calls.clear();
        contents.nextChange = null;
        clock.now = clock.now.plusSeconds( 60 );
        cache.get( KEY );
        awaitUntil( () -> !contents.calls.isEmpty() );
        awaitIdle();
        assertThat( contents.calls ).containsExactly( "write" );
    }

    @Test
    void generatesUncachedSitemapInCallingThreadAndDeletesItLater()
    {
        final SitemapCache retaining = newCache( repositoryId -> executor, Duration.ZERO, Duration.ofMillis( 200 ) );
        final Thread caller = Thread.currentThread();
        final List<String> seen = new ArrayList<>();

        final Sitemap sitemap = retaining.uncached( KEY, AUTH_INFO, ( sink, cancelled ) -> {
            seen.add( ( Thread.currentThread() == caller ) + " " + ContextAccessor.current().getRepositoryId() + " " +
                          ContextAccessor.current().getBranch() );
            return generate( sink, cancelled );
        } );

        assertThat( seen ).containsExactly( "true com.enonic.cms.project master" );
        assertThat( SitemapTest.text( sitemap.root() ) ).contains( "https://example.com" );
        assertThat( sitemap.directory().resolve( SitemapCache.ENTRY_FILE ) ).doesNotExist();
        assertThat( retaining.size() ).isZero();
        awaitUntil( () -> !Files.exists( sitemap.directory() ) );
    }

    @Test
    void repositoryAndPermissionEventsInvalidate()
    {
        final Sitemap first = get( cache, KEY, this::generate );
        cache.onEvent( Event.create( "repository.deleted" ).value( "id", "com.enonic.cms.project" ).build() );
        assertThat( get( cache, KEY, this::generate ) ).isSameAs( first );
        awaitUntil( () -> generated.get() == 2 );
        awaitIdle();

        final Sitemap second = get( cache, KEY, this::generate );
        cache.onEvent( Event.create( "node.permissionsUpdated" )
                           .value( "id", "1" )
                           .value( "repo", "com.enonic.cms.project" )
                           .value( "branch", "master" )
                           .build() );
        assertThat( get( cache, KEY, this::generate ) ).isSameAs( second );
        awaitUntil( () -> generated.get() == 3 );
    }

    @Test
    void requestBoundSitemapIsGeneratedAgainWhenScheduledContentChanges()
    {
        final Instant validUntil = clock.now.plusSeconds( 3600 );
        final AtomicInteger runs = new AtomicInteger();
        get( cache, KEY, ( sink, cancelled ) -> generate( runs.incrementAndGet() == 1 ? validUntil : null ) );

        clock.now = validUntil.minusSeconds( 1 );
        get( cache, KEY, this::generate );
        assertThat( generated ).hasValue( 1 );

        clock.now = validUntil;
        final Sitemap first = get( cache, KEY, this::generate );
        awaitUntil( () -> generated.get() == 2 );
        awaitIdle();
        assertThat( get( cache, KEY, this::generate ) ).isNotSameAs( first );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void backgroundSitemapIsGeneratedAgainWhenScheduledContentChanges()
    {
        final AtomicInteger runs = new AtomicInteger();
        final Sitemap first =
            get( cache, PUBLIC_KEY, ( sink, cancelled ) -> generate( runs.incrementAndGet() == 1 ? clock.now.minusMillis( 1 ) : null ) );

        awaitUntil( () -> generated.get() == 2 );
        awaitIdle();
        assertThat( get( cache, PUBLIC_KEY, this::generate ) ).isNotSameAs( first );
    }

    @Test
    void expiredBackgroundSitemapIsServedWhileGeneratedAgain()
    {
        final Instant validUntil = clock.now.plusSeconds( 3600 );
        final AtomicInteger runs = new AtomicInteger();
        final Sitemap first =
            get( cache, PUBLIC_KEY, ( sink, cancelled ) -> generate( runs.incrementAndGet() == 1 ? validUntil : null ) );
        clock.now = validUntil;

        assertThat( get( cache, PUBLIC_KEY, this::generate ) ).isSameAs( first );
        awaitUntil( () -> generated.get() >= 2 );
    }

    @Test
    void failedFirstGenerationIsNotCached()
    {
        assertThatThrownBy( () -> get( cache, KEY, ( sink, cancelled ) -> {
            throw new IllegalStateException( "boom" );
        } ) ).isInstanceOf( IllegalStateException.class ).hasMessage( "boom" );

        final Sitemap sitemap = get( cache, KEY, this::generate );
        assertThat( generated ).hasValue( 1 );
        assertThat( sitemapDirectories() ).containsExactly( sitemap.directory() );
    }

    @Test
    void failedRegenerationKeepsPreviousSitemap()
    {
        final AtomicInteger runs = new AtomicInteger();
        final Sitemap first = get( cache, PUBLIC_KEY, ( sink, cancelled ) -> {
            if ( runs.incrementAndGet() > 1 )
            {
                throw new IllegalStateException( "boom" );
            }
            return generate( sink, cancelled );
        } );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitUntil( () -> runs.get() == 2 );
        awaitIdle();

        assertThat( get( cache, PUBLIC_KEY, this::generate ) ).isSameAs( first );
    }

    @Test
    void requestsForSameSitemapShareOneGeneration()
        throws Exception
    {
        final CountDownLatch release = new CountDownLatch( 1 );
        final List<Future<Sitemap>> results = new ArrayList<>();
        for ( int i = 0; i < 5; i++ )
        {
            results.add( requests.submit( () -> get( cache, KEY, ( sink, cancelled ) -> {
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
    void generatesSitemapsOfDifferentProjectsInParallel()
        throws Exception
    {
        final SitemapCache parallel = newCache( SitemapCache::newGenerator, Duration.ZERO, Duration.ZERO );
        try
        {
            final CountDownLatch blocked = new CountDownLatch( 1 );
            final CountDownLatch release = new CountDownLatch( 1 );
            final Future<Sitemap> slow =
                requests.submit( () -> get( parallel, key( "com.enonic.cms.slow" ), ( sink, cancelled ) -> {
                    blocked.countDown();
                    await( release );
                    return generate( sink, cancelled );
                } ) );
            assertThat( blocked.await( 5, TimeUnit.SECONDS ) ).isTrue();

            assertThat( get( parallel, key( "com.enonic.cms.fast" ), this::generate ).size() ).isEqualTo( 1 );

            release.countDown();
            assertThat( slow.get( 5, TimeUnit.SECONDS ).size() ).isEqualTo( 1 );
        }
        finally
        {
            parallel.deactivate();
        }
    }

    @Test
    void queuesAllGenerationsAndThrottlesOnlyWaitingRequests()
        throws Exception
    {
        final Sitemap ready = get( cache, key( "ready" ), this::generate );
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
            waiting.add( requests.submit( () -> get( cache, key, slow ) ) );
        }
        awaitUntil( () -> cache.waiting() == SitemapCache.MAX_WAITING );

        assertThatThrownBy( () -> get( cache, key( "extra" ), slow ) ).isInstanceOf( SitemapBusyException.class );
        assertThat( get( cache, key( "ready" ), this::generate ) ).isSameAs( ready );

        release.countDown();
        for ( final Future<Sitemap> result : waiting )
        {
            assertThat( result.get( 5, TimeUnit.SECONDS ) ).isNotNull();
        }
        awaitUntil( () -> generated.get() == SitemapCache.MAX_WAITING + 2 );
        assertThat( maxRunning ).hasValue( 1 );

        get( cache, key( "extra" ), this::generate );
        assertThat( generated ).hasValue( SitemapCache.MAX_WAITING + 2 );
    }

    @Test
    void evictsLeastRecentlyUsedOfSiteWhenFull()
    {
        final Sitemap otherSite = get( cache, key( "other" ), this::generate );
        for ( int i = 0; i <= SitemapCache.MAX_PER_SITE; i++ )
        {
            clock.now = clock.now.plusSeconds( 1 );
            get( cache, hostKey( i ), this::generate );
        }

        assertThat( cache.size() ).isEqualTo( SitemapCache.MAX_PER_SITE + 1 );
        assertThat( generated ).hasValue( SitemapCache.MAX_PER_SITE + 2 );
        get( cache, hostKey( SitemapCache.MAX_PER_SITE ), this::generate );
        assertThat( generated ).hasValue( SitemapCache.MAX_PER_SITE + 2 );
        get( cache, hostKey( 0 ), this::generate );
        assertThat( generated ).hasValue( SitemapCache.MAX_PER_SITE + 3 );
        assertThat( get( cache, key( "other" ), this::generate ) ).isSameAs( otherSite );
    }

    @Test
    void limitsSitemapsPerSiteAndPrincipals()
    {
        final SitemapCache.Key otherUser = new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "user:system:editor" ),
                                                                 new SitemapParams( "/mysite", "https://example.com:9999", List.of(),
                                                                                    List.of(), 50_000 ) );
        final Sitemap kept = get( cache, otherUser, this::generate );
        for ( int i = 0; i <= SitemapCache.MAX_PER_SITE; i++ )
        {
            clock.now = clock.now.plusSeconds( 1 );
            get( cache, hostKey( i ), this::generate );
        }

        assertThat( get( cache, otherUser, this::generate ) ).isSameAs( kept );
        assertThat( cache.size() ).isEqualTo( SitemapCache.MAX_PER_SITE + 1 );
    }

    @Test
    void enforcesLimitAfterBurstOfColdRequests()
    {
        final CountDownLatch release = new CountDownLatch( 1 );
        final SitemapCache.Source slow = ( sink, cancelled ) -> {
            await( release );
            return generate( sink, cancelled );
        };
        final int requested = 2 * SitemapCache.MAX_PER_SITE + 5;
        for ( int i = 0; i < requested; i++ )
        {
            final SitemapCache.Key key = hostKey( i );
            requests.submit( () -> get( cache, key, slow ) );
        }
        awaitUntil( () -> cache.size() == requested );

        release.countDown();
        awaitUntil( () -> generated.get() == requested );
        awaitIdle();

        assertThat( cache.size() ).isEqualTo( SitemapCache.MAX_PER_SITE );
        awaitUntil( () -> sitemapDirectories().size() == SitemapCache.MAX_PER_SITE );
    }

    @Test
    void evictsSitemapsWithPortBeforeThoseWithout()
    {
        final SitemapCache.Key withoutPort = siteKey( "https://example.com" );
        final Sitemap kept = get( cache, withoutPort, this::generate );
        for ( int i = 0; i < 2 * SitemapCache.MAX_PER_SITE; i++ )
        {
            clock.now = clock.now.plusSeconds( 1 );
            get( cache, hostKey( i ), this::generate );
        }

        assertThat( get( cache, withoutPort, this::generate ) ).isSameAs( kept );
        assertThat( cache.size() ).isEqualTo( SitemapCache.MAX_PER_SITE );
    }

    @Test
    void deletesFilesOfReplacedAndEvictedSitemaps()
    {
        final Sitemap first = get( cache, PUBLIC_KEY, this::generate );
        assertThat( first.directory() ).startsWith( storage ).isDirectory();

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        awaitUntil( () -> !Files.exists( first.directory() ) );
        final Sitemap second = get( cache, PUBLIC_KEY, this::generate );
        assertThat( sitemapDirectories() ).containsExactly( second.directory() );

        // sitemaps of logged-in users beyond the limit; the public one stays
        final List<Sitemap> users = new ArrayList<>();
        for ( int i = 0; i <= SitemapCache.MAX_PER_SITE; i++ )
        {
            clock.now = clock.now.plusSeconds( 1 );
            users.add( get( cache, siteKey( "https://host" + i + ".example.com" ), this::generate ) );
        }
        awaitUntil( () -> !Files.exists( users.get( 0 ).directory() ) );
        assertThat( sitemapDirectories() ).hasSize( SitemapCache.MAX_PER_SITE + 1 ).contains( second.directory() );
    }

    @Test
    void servesSitemapsKeptFromBeforeRestartWhileGeneratingThemAgain()
    {
        final SitemapParams params = new SitemapParams( "/my site", "https://example.com/a=b", List.of(
            new ContentTypeSetting( "app:article", "daily", "0.8" ), new ContentTypeSetting( "app:page", "", "" ) ),
                                                        List.of( "/my site/hidden/*", "*/=draft" ), 1234 );
        final SitemapCache.Key key =
            new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone", "user:system:a=b" ), params );
        final Sitemap first = get( cache, key, this::generate );
        final String content = SitemapTest.text( first.root() );

        final SitemapCache restarted = newCache( repositoryId -> executor, Duration.ZERO, Duration.ofMinutes( 1 ) );
        assertThat( restarted.size() ).isEqualTo( 1 );
        awaitIdle();
        assertThat( generated ).hasValue( 1 );
        clock.now = clock.now.plusSeconds( 3600 );

        final Sitemap restored = get( restarted, key, this::generate );
        assertThat( restored.directory() ).isEqualTo( first.directory() );
        assertThat( SitemapTest.text( restored.root() ) ).isEqualTo( content );
        assertThat( restored.size() ).isEqualTo( first.size() );
        assertThat( restored.lastModified() ).isEqualTo( first.lastModified() );

        awaitUntil( () -> generated.get() == 2 );
        awaitIdle();
        final Sitemap regenerated = get( restarted, key, this::generate );
        assertThat( regenerated ).isNotSameAs( restored );
        assertThat( regenerated.lastModified() ).isEqualTo( first.lastModified() );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void deletesUnfinishedFilesOnStart()
        throws IOException
    {
        final Sitemap first = get( cache, KEY, this::generate );
        final Path unfinished = Files.createDirectories( storage.resolve( "unfinished" ) );
        final Path stale = Files.createDirectories( storage.resolve( "stale" ) );
        Files.writeString( Files.createDirectories( storage.resolve( "broken" ) ).resolve( SitemapCache.ENTRY_FILE ), "format=1" );

        final SitemapCache restarted = newCache( repositoryId -> executor, Duration.ZERO, Duration.ZERO );

        assertThat( restarted.size() ).isEqualTo( 1 );
        assertThat( unfinished ).doesNotExist();
        assertThat( stale ).doesNotExist();
        assertThat( storage.resolve( "broken" ) ).doesNotExist();
        assertThat( sitemapDirectories() ).containsExactly( first.directory() );
    }

    @Test
    void keepsNewerOfTwoSitemapsWithSameKeyOnStart()
        throws IOException
    {
        final Sitemap newer = get( cache, KEY, this::generate );
        final Path older = Files.createDirectories( storage.resolve( SitemapCache.uuid7( clock.now.minusSeconds( 1 ).toEpochMilli() ) ) );
        try (Stream<Path> files = Files.list( newer.directory() ))
        {
            for ( final Path file : files.collect( Collectors.toList() ) )
            {
                Files.copy( file, older.resolve( file.getFileName() ) );
            }
        }

        final SitemapCache restarted = newCache( repositoryId -> executor, Duration.ZERO, Duration.ZERO );

        assertThat( restarted.size() ).isEqualTo( 1 );
        assertThat( sitemapDirectories() ).containsExactly( newer.directory() );
        assertThat( get( restarted, KEY, this::generate ).directory() ).isEqualTo( newer.directory() );
    }

    @Test
    void namesDirectoriesByTimeOrderedUuids()
    {
        final String earlier = SitemapCache.uuid7( 1_700_000_000_000L );
        final String later = SitemapCache.uuid7( 1_700_000_000_001L );

        assertThat( UUID.fromString( earlier ).version() ).isEqualTo( 7 );
        assertThat( UUID.fromString( earlier ).variant() ).isEqualTo( 2 );
        assertThat( earlier ).startsWith( "018bcfe5-6800-7" ).isLessThan( later );
        assertThat( get( cache, KEY, this::generate ).directory().getFileName().toString() ).startsWith(
            SitemapCache.uuid7( clock.now.toEpochMilli() ).substring( 0, 13 ) );
    }

    @Test
    void stopWaitsForRunningGenerationToHalt()
        throws Exception
    {
        final Sitemap first = get( cache, KEY, this::generate );
        final CountDownLatch started = new CountDownLatch( 1 );
        final AtomicBoolean halted = new AtomicBoolean();
        requests.submit( () -> get( cache, key( "other" ), ( sink, cancelled ) -> {
            started.countDown();
            while ( !cancelled.getAsBoolean() )
            {
                Thread.onSpinWait();
            }
            sink.accept( new SitemapEntry( "/late", SitemapParams.SITE_CONTENT_TYPE, null ) );
            halted.set( true );
            return null;
        } ) );
        assertThat( started.await( 5, TimeUnit.SECONDS ) ).isTrue();

        cache.deactivate();

        assertThat( halted ).isTrue();
        assertThat( sitemapDirectories() ).containsExactly( first.directory() );
    }

    @Test
    void keepsFilesOnStop()
        throws Exception
    {
        final Sitemap first = get( cache, KEY, this::generate );
        final CountDownLatch blocked = new CountDownLatch( 1 );
        final Future<Sitemap> waiting = requests.submit( () -> get( cache, key( "other" ), ( sink, cancelled ) -> {
            blocked.countDown();
            await( new CountDownLatch( 1 ) );
            return null;
        } ) );
        blocked.await( 5, TimeUnit.SECONDS );
        cache.deactivate();

        assertThatThrownBy( () -> waiting.get( 5, TimeUnit.SECONDS ) ).isInstanceOf( ExecutionException.class )
            .hasCauseInstanceOf( CancellationException.class );
        assertThat( first.directory() ).isDirectory();
        assertThat( first.directory().resolve( SitemapCache.ENTRY_FILE ) ).isRegularFile();
    }

    private Instant generate( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        sink.accept( new SitemapEntry( "", SitemapParams.SITE_CONTENT_TYPE, null ) );
        return generate( null );
    }

    private Instant generate( final Instant validUntil )
    {
        generated.incrementAndGet();
        return validUntil;
    }

    private List<Path> sitemapDirectories()
    {
        try (Stream<Path> paths = Files.list( storage ))
        {
            return paths.filter( Files::isDirectory ).collect( Collectors.toList() );
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( e );
        }
    }

    private static SitemapCache.Key siteKey( final String siteUrl )
    {
        return new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone" ),
                                     new SitemapParams( "/mysite", siteUrl, List.of(), List.of(), 50_000 ) );
    }

    private static SitemapCache.Key hostKey( final int port )
    {
        return siteKey( "https://example.com:" + ( 8000 + port ) );
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
        return nodeEvent( type, repo, branch, "/content/mysite/a" );
    }

    private static Event nodeEvent( final String type, final String repo, final String branch, final String path )
    {
        return Event.create( type ).value( "nodes", List.of( Map.of( "id", "1", "path", path, "repo", repo, "branch", branch ) ) ).build();
    }

    /**
     * Three contents, counted and read as the content API does.
     */
    private final class CountingSource
        implements SitemapCache.Source
    {
        final List<String> calls = Collections.synchronizedList( new ArrayList<>() );

        volatile long total = 3;

        volatile long changed;

        volatile Instant nextChange;

        @Override
        public Instant write( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
        {
            calls.add( "write" );
            for ( int i = 0; i < total; i++ )
            {
                sink.accept( new SitemapEntry( "/item-" + i, SitemapParams.SITE_CONTENT_TYPE, null ) );
            }
            return generate( nextChange );
        }

        @Override
        public SitemapQuery.Count count( final Instant since )
        {
            calls.add( "count" );
            return new SitemapQuery.Count( total, changed );
        }

        @Override
        public long total()
        {
            return total;
        }
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

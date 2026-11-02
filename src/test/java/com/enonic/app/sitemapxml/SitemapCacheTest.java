package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.event.Event;
import com.enonic.xp.security.PrincipalKey;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.task.ProgressReportParams;
import com.enonic.xp.task.ProgressReporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SitemapCacheTest
{
    private static final SitemapParams PARAMS = new SitemapParams( "/mysite", "https://example.com", List.of(), List.of() );

    private static final SitemapCache.Key KEY =
        new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone" ), PARAMS );

    private static final Instant CLUSTER_STARTED = Instant.parse( "2024-01-01T00:00:00Z" );

    private final InMemorySitemapStore store = new InMemorySitemapStore();

    private final AtomicInteger generated = new AtomicInteger();

    private final AtomicReference<SitemapCache.Source> source = new AtomicReference<>( this::generate );

    private final MutableClock clock = new MutableClock( CLUSTER_STARTED.plusSeconds( 60 ) );

    private final ExecutorService requests = Executors.newCachedThreadPool();

    private final List<ExecutorService> executors = Collections.synchronizedList( new ArrayList<>() );

    private final List<SitemapCache> nodes = Collections.synchronizedList( new ArrayList<>() );

    private Duration regenerationDelay = Duration.ZERO;

    private final AtomicReference<RuntimeException> submitFailure = new AtomicReference<>();

    @TempDir
    private Path temp;

    @AfterEach
    void shutdown()
        throws InterruptedException
    {
        nodes.forEach( SitemapCache::deactivate );
        requests.shutdownNow();
        for ( final ExecutorService executor : executors )
        {
            executor.shutdownNow();
            assertThat( executor.awaitTermination( 5, TimeUnit.SECONDS ) ).isTrue();
        }
        assertThat( requests.awaitTermination( 5, TimeUnit.SECONDS ) ).isTrue();
    }

    @Test
    void generatesOnceAndServesFromStore()
    {
        final Node node = node();

        final Sitemap first = node.cache.get( KEY, false );

        assertThat( node.cache.get( KEY, false ) ).isSameAs( first );
        assertThat( first.size() ).isEqualTo( 1 );
        assertThat( generated ).hasValue( 1 );
        final SitemapStore.Stored stored = store.load( KEY.id() );
        assertThat( stored.generatedAt() ).isEqualTo( clock.instant() );
        assertThat( stored.generating() ).isNull();
    }

    @Test
    void generatesWithPrincipalsOfKeyInItsRepository()
    {
        final List<String> seen = new ArrayList<>();
        source.set( ( sink, cancelled ) -> {
            seen.add( ContextAccessor.current().getRepositoryId() + " " + ContextAccessor.current().getBranch() + " " +
                          ContextAccessor.current().getAuthInfo().getPrincipals() );
            return generate( sink, cancelled );
        } );

        node().cache.get( KEY, false );

        assertThat( seen ).containsExactly(
            "com.enonic.cms.project master " + List.of( PrincipalKey.ofAnonymous(), PrincipalKey.from( "role:system.everyone" ) ) );
    }

    @Test
    void otherNodeServesStoredSitemapWithoutGeneratingIt()
    {
        node().cache.get( KEY, true );

        final Sitemap served = node().cache.get( KEY, true );

        assertThat( generated ).hasValue( 1 );
        assertThat( text( served ) ).contains( "https://example.com/1" );
    }

    @Test
    void requestBoundSitemapIsServedAfterChangeWhileGeneratedAgainOnNextRequest()
    {
        final Node node = node();
        final Sitemap first = node.cache.get( KEY, false );

        node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.other", "master", true ) );
        node.cache.onEvent( nodeEvent( "node.updated", "com.enonic.cms.project", "draft", true ) );
        assertThat( node.cache.get( KEY, false ) ).isSameAs( first );
        node.awaitIdle();
        assertThat( generated ).hasValue( 1 );

        clock.advance( Duration.ofSeconds( 1 ) );
        node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", false ) );
        node.awaitIdle();
        assertThat( generated ).hasValue( 1 );

        assertThat( node.cache.get( KEY, false ) ).isSameAs( first );
        node.awaitIdle();
        assertThat( generated ).hasValue( 2 );
        assertThat( text( node.cache.get( KEY, false ) ) ).contains( "https://example.com/2" );
    }

    @Test
    void onlyNodeWhereContentChangedGeneratesBackgroundSitemapsAgain()
    {
        regenerationDelay = Duration.ofMillis( 100 );
        final Node origin = node();
        final Node other = node();
        origin.cache.get( KEY, true );
        final Sitemap served = other.cache.get( KEY, true );

        origin.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );
        other.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", false ) );
        origin.awaitIdle();
        clock.advance( Duration.ofSeconds( 1 ) );
        awaitUntil( () -> generated.get() == 2 );
        origin.awaitIdle();
        other.awaitIdle();
        assertThat( other.cache.get( KEY, true ) ).isSameAs( served );
        assertThat( generated ).hasValue( 2 );
        assertThat( store.load( KEY.id() ).obsolete() ).isNull();

        other.cache.onEvent( storedEvent( "node.updated", KEY.id() ) );
        assertThat( text( other.cache.get( KEY, true ) ) ).contains( "https://example.com/2" );
        other.awaitIdle();
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void nodeWhereContentChangedMarksSitemapsObsoleteForAllNodes()
    {
        final Node first = node();
        first.cache.get( KEY, false );

        final Node origin = node();
        origin.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );
        origin.awaitIdle();
        assertThat( store.load( KEY.id() ).obsolete() ).isEqualTo( clock.instant() );
        assertThat( generated ).hasValue( 1 );

        clock.advance( Duration.ofSeconds( 1 ) );
        final Node fresh = node();
        fresh.cache.get( KEY, false );
        awaitUntil( () -> generated.get() == 2 );
        fresh.awaitIdle();
        assertThat( store.load( KEY.id() ).obsolete() ).isNull();
    }

    @Test
    void permissionsUpdatedEventMarksSitemapsObsolete()
    {
        final Node node = node();
        node.cache.get( KEY, false );

        node.cache.onEvent( Event.create( "node.permissionsUpdated" )
                                .localOrigin( true )
                                .value( "id", "1" )
                                .value( "path", "/content/mysite/a" )
                                .value( "branch", "master" )
                                .value( "repo", "com.enonic.cms.project" )
                                .build() );
        node.awaitIdle();

        assertThat( store.load( KEY.id() ).obsolete() ).isEqualTo( clock.instant() );
    }

    @Test
    void changeDuringFirstGenerationOfBackgroundSitemapIsIncluded()
        throws Exception
    {
        final ExecutorService pool = Executors.newCachedThreadPool();
        executors.add( pool );
        final Node node = node( Duration.ZERO, pool );
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        source.set( ( sink, cancelled ) -> {
            source.set( this::generate );
            started.countDown();
            await( release );
            return generate( sink, cancelled );
        } );

        final Future<Sitemap> first = requests.submit( () -> node.cache.get( KEY, true ) );
        await( started );
        clock.advance( Duration.ofSeconds( 1 ) );
        node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );
        node.awaitIdle();
        clock.advance( Duration.ofSeconds( 1 ) );
        release.countDown();

        assertThat( text( first.get( 5, TimeUnit.SECONDS ) ) ).contains( "https://example.com/1" );
        awaitUntil( () -> generated.get() == 2 && store.load( KEY.id() ).generating() == null );
        assertThat( store.load( KEY.id() ).obsolete() ).isNull();
        awaitUntil( () -> text( node.cache.get( KEY, true ) ).contains( "https://example.com/2" ) );
    }

    @Test
    void otherNodeGeneratesBackgroundSitemapWhenNodeWhereContentChangedDidNotInTime()
    {
        final Node other = node();
        other.cache.get( KEY, true );

        clock.advance( Duration.ofSeconds( 1 ) );
        other.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", false ) );
        other.cache.get( KEY, true );
        other.awaitIdle();
        assertThat( generated ).hasValue( 1 );

        clock.advance( SitemapCache.STALE_GENERATION.plusSeconds( 1 ) );
        other.cache.get( KEY, true );
        awaitUntil( () -> generated.get() == 2 );
    }

    @Test
    void waitsForGenerationOnAnotherNodeAndTakesOverItsStaleClaim()
    {
        node().cache.get( KEY, false );
        store.markObsolete( store.load( KEY.id() ), clock.instant() );
        assertThat( store.claim( store.load( KEY.id() ), "other", clock.instant(), SitemapCache.STALE_GENERATION ) ).isTrue();

        final Node node = node();
        node.cache.get( KEY, false );
        awaitUntil( () -> store.load( KEY.id() ).queued() != null );
        assertThat( generated ).hasValue( 1 );

        clock.advance( SitemapCache.STALE_GENERATION.plusSeconds( 1 ) );
        awaitUntil( () -> generated.get() == 2 );
        node.awaitIdle();
        assertThat( store.load( KEY.id() ).generating() ).isNull();
        assertThat( store.load( KEY.id() ).queued() ).isNull();
    }

    @Test
    void queuedGenerationRunsOnceAfterRunningOneForSeveralChanges()
    {
        final ExecutorService pool = Executors.newCachedThreadPool();
        executors.add( pool );
        final Node node = node( Duration.ZERO, pool );
        node.cache.get( KEY, true );
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        source.set( ( sink, cancelled ) -> {
            source.set( this::generate );
            started.countDown();
            await( release );
            return generate( sink, cancelled );
        } );

        clock.advance( Duration.ofSeconds( 1 ) );
        node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );
        await( started );
        for ( int i = 0; i < 3; i++ )
        {
            clock.advance( Duration.ofSeconds( 1 ) );
            node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );
            node.awaitIdle();
        }
        awaitUntil( () -> store.load( KEY.id() ).queued() != null );
        sleep( 100 );
        assertThat( generated ).hasValue( 1 );

        release.countDown();
        awaitUntil( () -> generated.get() == 3 );
        awaitUntil( () -> store.load( KEY.id() ).generating() == null && store.load( KEY.id() ).queued() == null );
        sleep( 100 );
        assertThat( generated ).hasValue( 3 );
        assertThat( text( node.cache.get( KEY, true ) ) ).contains( "https://example.com/3" );
    }

    @Test
    void generationsForDifferentUsersOfSiteAreNotQueuedBehindEachOther()
        throws Exception
    {
        final ExecutorService pool = Executors.newCachedThreadPool();
        executors.add( pool );
        final Node node = node( Duration.ZERO, pool );
        final SitemapCache.Key editor =
            new SitemapCache.Key( KEY.repositoryId(), KEY.branch(), Set.of( "user:system:editor", "role:system.everyone" ), PARAMS );
        final CountDownLatch blocked = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        source.set( ( sink, cancelled ) -> {
            if ( ContextAccessor.current().getAuthInfo().getPrincipals().contains( PrincipalKey.from( "user:system:editor" ) ) )
            {
                blocked.countDown();
                await( release );
            }
            return generate( sink, cancelled );
        } );

        final Future<Sitemap> editorSitemap = requests.submit( () -> node.cache.get( editor, false ) );
        assertThat( blocked.await( 5, TimeUnit.SECONDS ) ).isTrue();
        final Future<Sitemap> anonymousSitemap = requests.submit( () -> node.cache.get( KEY, false ) );
        awaitUntil( () -> store.load( KEY.id() ) != null );

        assertThat( store.load( KEY.id() ).generating() ).isNotNull();
        assertThat( store.load( editor.id() ).queued() ).isNull();
        assertThat( store.load( KEY.id() ).queued() ).isNull();

        release.countDown();
        assertThat( editorSitemap.get( 5, TimeUnit.SECONDS ).size() ).isEqualTo( 1 );
        assertThat( anonymousSitemap.get( 5, TimeUnit.SECONDS ).size() ).isEqualTo( 1 );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void waitsForFirstGenerationOnAnotherNode()
        throws Exception
    {
        assertThat( store.claimNew( KEY.id(), KEY, false, "other", clock.instant() ) ).isTrue();
        final Node node = node();

        final Future<Sitemap> waiting = requests.submit( () -> node.cache.get( KEY, false ) );
        awaitUntil( () -> node.cache.waiting() == 1 );
        node.awaitIdle();
        assertThat( waiting ).isNotDone();
        assertThat( generated ).hasValue( 0 );

        store.save( KEY.id(), KEY, false, SitemapTest.write( temp.resolve( "other" ), PARAMS,
                                                             List.of( new SitemapEntry( "/elsewhere", SitemapParams.SITE_CONTENT_TYPE, null ) ), 10,
                                                             Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES ), null, clock.instant(),
                    clock.instant() );
        node.cache.onEvent( storedEvent( "node.updated", KEY.id() ) );

        assertThat( text( waiting.get( 5, TimeUnit.SECONDS ) ) ).contains( "https://example.com/elsewhere" );
        assertThat( generated ).hasValue( 0 );
    }

    @Test
    void generatesAgainWhenContentChangesWhileGenerating()
    {
        final ExecutorService pool = Executors.newCachedThreadPool();
        executors.add( pool );
        final Node node = node( Duration.ZERO, pool );
        node.cache.get( KEY, true );
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        source.set( ( sink, cancelled ) -> {
            source.set( this::generate );
            started.countDown();
            await( release );
            return generate( sink, cancelled );
        } );

        clock.advance( Duration.ofSeconds( 1 ) );
        node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );
        await( started );
        clock.advance( Duration.ofSeconds( 1 ) );
        node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );
        node.awaitIdle();
        clock.advance( Duration.ofSeconds( 1 ) );
        release.countDown();

        awaitUntil( () -> generated.get() == 3 );
        awaitUntil( () -> store.load( KEY.id() ).generating() == null && store.load( KEY.id() ).generatedAt() != null );
        node.awaitIdle();
        sleep( 100 );
        assertThat( generated ).hasValue( 3 );
        assertThat( store.load( KEY.id() ).obsolete() ).isNull();
    }

    @Test
    void generationOffloadedToAnotherNodeAnswersWaitingRequests()
        throws Exception
    {
        final Node worker = node();
        final Node node = node( Duration.ZERO, worker.executor(), worker.cache() );

        final Future<Sitemap> waiting = requests.submit( () -> node.cache.get( KEY, false ) );
        awaitUntil( () -> store.load( KEY.id() ) != null && store.load( KEY.id() ).sitemap() != null );
        assertThat( waiting ).isNotDone();

        node.cache.onEvent( storedEvent( "node.updated", KEY.id() ) );
        assertThat( text( waiting.get( 5, TimeUnit.SECONDS ) ) ).contains( "https://example.com/1" );
        assertThat( generated ).hasValue( 1 );
    }

    @Test
    void regenSitemapIsGeneratedAgainWithoutRequestAlsoWhenNotRequestedForLong()
    {
        final Node origin = node();
        final Node other = node();
        other.cache.get( KEY, true );
        clock.advance( Duration.ofDays( 7 ) );

        origin.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );

        awaitUntil( () -> generated.get() == 2 );
    }

    @Test
    void sitemapGeneratedBeforeClusterStartedIsGeneratedAgainOnRequest()
    {
        clock.set( CLUSTER_STARTED.minusSeconds( 60 ) );
        node().cache.get( KEY, true );
        clock.set( CLUSTER_STARTED.plusSeconds( 60 ) );

        final Node node = node();
        node.cache.get( KEY, true );
        awaitUntil( () -> generated.get() == 2 );
        node.awaitIdle();

        node.cache.get( KEY, true );
        node.awaitIdle();
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void sitemapIsGeneratedAgainWhenScheduledContentChanges()
    {
        final Node node = node();
        source.set( ( sink, cancelled ) -> {
            source.set( this::generate );
            generate( sink, cancelled );
            return clock.instant().plusSeconds( 3600 );
        } );
        final Sitemap first = node.cache.get( KEY, false );
        assertThat( store.load( KEY.id() ).validUntil() ).isEqualTo( clock.instant().plusSeconds( 3600 ) );

        clock.advance( Duration.ofSeconds( 3600 ) );
        assertThat( node.cache.get( KEY, false ) ).isSameAs( first );
        awaitUntil( () -> generated.get() == 2 );
    }

    @Test
    void failedFirstGenerationIsNotStored()
    {
        final Node node = node();
        source.set( ( sink, cancelled ) -> {
            throw new IllegalStateException( "boom" );
        } );

        assertThatThrownBy( () -> node.cache.get( KEY, false ) ).isInstanceOf( IllegalStateException.class ).hasMessage( "boom" );
        assertThat( store.load( KEY.id() ) ).isNull();

        source.set( this::generate );
        assertThat( node.cache.get( KEY, false ).size() ).isEqualTo( 1 );
    }

    @Test
    void failedSubmissionOfFirstGenerationIsNotKeptForNextRequest()
    {
        final Node node = node();
        submitFailure.set( new IllegalStateException( "no task" ) );

        assertThatThrownBy( () -> node.cache.get( KEY, false ) ).isInstanceOf( IllegalStateException.class ).hasMessage( "no task" );

        assertThat( node.cache.get( KEY, false ).size() ).isEqualTo( 1 );
    }

    @Test
    void failedRegenerationKeepsPreviousSitemapAndReleasesClaim()
    {
        final Node node = node();
        final Sitemap first = node.cache.get( KEY, false );
        final AtomicInteger failures = new AtomicInteger();
        source.set( ( sink, cancelled ) -> {
            failures.incrementAndGet();
            throw new IllegalStateException( "boom" );
        } );

        clock.advance( Duration.ofSeconds( 1 ) );
        node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", false ) );
        assertThat( node.cache.get( KEY, false ) ).isSameAs( first );
        node.awaitIdle();

        assertThat( failures.get() ).isEqualTo( 1 );
        assertThat( store.load( KEY.id() ).sitemap() ).isNotNull();
        assertThat( store.load( KEY.id() ).generating() ).isNull();
        assertThat( node.cache.get( KEY, false ) ).isSameAs( first );
    }

    @Test
    void queuesAllGenerationsAndThrottlesOnlyWaitingRequests()
        throws Exception
    {
        final Node node = node();
        final CountDownLatch release = new CountDownLatch( 1 );
        source.set( ( sink, cancelled ) -> {
            await( release );
            return generate( sink, cancelled );
        } );

        final List<Future<Sitemap>> waiting = new ArrayList<>();
        for ( int i = 0; i < SitemapCache.MAX_WAITING; i++ )
        {
            final SitemapCache.Key key = key( "/site-" + i );
            waiting.add( requests.submit( () -> node.cache.get( key, false ) ) );
        }
        awaitUntil( () -> node.cache.waiting() == SitemapCache.MAX_WAITING );

        assertThatThrownBy( () -> node.cache.get( key( "/one-too-many" ), false ) ).isInstanceOf( SitemapBusyException.class );

        release.countDown();
        for ( final Future<Sitemap> future : waiting )
        {
            assertThat( future.get( 5, TimeUnit.SECONDS ).size() ).isEqualTo( 1 );
        }
        awaitUntil( () -> store.load( key( "/one-too-many" ).id() ) != null && store.load( key( "/one-too-many" ).id() ).sitemap() != null );
        assertThat( node.cache.get( key( "/one-too-many" ), false ).size() ).isEqualTo( 1 );
    }

    @Test
    void deletesSitemapsGeneratedOnRequestMoreThanRetentionAgoExceptThoseBeingGenerated()
    {
        final Node node = node();
        final SitemapCache.Key old = key( "/old" );
        final SitemapCache.Key busy = key( "/busy" );
        final SitemapCache.Key regen = key( "/regen" );
        node.cache.get( old, false );
        node.cache.get( busy, false );
        node.cache.get( regen, true );
        assertThat( store.claim( store.load( busy.id() ), "other", clock.instant().plus( SitemapCache.RETENTION ).plusSeconds( 1 ),
                                 SitemapCache.STALE_GENERATION ) ).isTrue();

        clock.advance( SitemapCache.RETENTION.plusSeconds( 1 ) );
        node.cache.get( KEY, false );
        node.awaitIdle();

        awaitUntil( () -> store.load( old.id() ) == null );
        assertThat( store.load( busy.id() ) ).isNotNull();
        assertThat( store.load( regen.id() ) ).isNotNull();
        assertThat( store.load( KEY.id() ) ).isNotNull();
        assertThat( text( node.cache.get( old, false ) ) ).contains( "https://example.com/" );
        assertThat( store.load( old.id() ) ).isNotNull();
    }

    @Test
    void evictsSitemapsOfSiteBeyondLimitWithPortsFirstThenLeastRecentlyRequested()
    {
        final Node node = node();
        final SitemapCache.Key withoutPort = siteKey( "https://example.com" );
        node.cache.get( withoutPort, false );
        for ( int port = 0; port < SitemapCache.MAX_PER_SITE; port++ )
        {
            clock.advance( Duration.ofSeconds( 1 ) );
            node.cache.get( hostKey( port ), false );
        }
        node.awaitIdle();

        assertThat( store.ofSite( withoutPort ) ).hasSize( SitemapCache.MAX_PER_SITE );
        assertThat( store.load( withoutPort.id() ) ).isNotNull();
        assertThat( store.load( hostKey( 0 ).id() ) ).isNull();
        assertThat( store.load( hostKey( 1 ).id() ) ).isNotNull();

        final SitemapCache.Key otherUser = new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "user:system:editor" ),
                                                                 withoutPort.params() );
        node.cache.get( otherUser, false );
        node.awaitIdle();
        assertThat( store.ofSite( withoutPort ) ).hasSize( SitemapCache.MAX_PER_SITE );
    }

    @Test
    void generatesUncachedSitemapInCallingThreadAndDeletesItLater()
        throws IOException
    {
        final Node node = node( Duration.ofMillis( 200 ) );

        final Sitemap sitemap = node.cache.uncached( KEY, AuthenticationInfo.unAuthenticated(), this::generate );

        assertThat( sitemap.directory() ).isDirectory();
        assertThat( text( sitemap ) ).contains( "https://example.com/1" );
        assertThat( store.size() ).isZero();
        awaitUntil( () -> !Files.exists( sitemap.directory() ) );
    }

    @Test
    void stopHaltsRunningGenerationAndReleasesClaim()
        throws Exception
    {
        final Node node = node();
        final CountDownLatch started = new CountDownLatch( 1 );
        source.set( ( sink, cancelled ) -> {
            started.countDown();
            awaitUntil( cancelled );
            return null;
        } );
        final Future<Sitemap> waiting = requests.submit( () -> node.cache.get( KEY, false ) );
        await( started );

        node.cache.deactivate();

        assertThatThrownBy( () -> waiting.get( 5, TimeUnit.SECONDS ) ).isInstanceOf( ExecutionException.class )
            .hasCauseInstanceOf( CancellationException.class );
        assertThat( store.load( KEY.id() ) ).isNull();
    }

    @Test
    void generatesSitemapsOfDifferentProjectsInParallel()
        throws Exception
    {
        final ExecutorService pool = Executors.newCachedThreadPool();
        executors.add( pool );
        final Node node = node( Duration.ZERO, pool );
        final CountDownLatch blocked = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        source.set( ( sink, cancelled ) -> {
            if ( ContextAccessor.current().getRepositoryId().toString().equals( "com.enonic.cms.slow" ) )
            {
                blocked.countDown();
                await( release );
            }
            return generate( sink, cancelled );
        } );

        final Future<Sitemap> slow = requests.submit( () -> node.cache.get( repoKey( "com.enonic.cms.slow" ), false ) );
        assertThat( blocked.await( 5, TimeUnit.SECONDS ) ).isTrue();

        assertThat( node.cache.get( repoKey( "com.enonic.cms.fast" ), false ).size() ).isEqualTo( 1 );

        release.countDown();
        assertThat( slow.get( 5, TimeUnit.SECONDS ).size() ).isEqualTo( 1 );
    }

    @Test
    void waitsGracePeriodBeforeGeneratingInBackgroundOnceForSeveralChanges()
    {
        regenerationDelay = Duration.ofMillis( 300 );
        final Node node = node();
        node.cache.get( KEY, true );

        node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );
        node.awaitIdle();
        node.cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master", true ) );
        node.awaitIdle();
        assertThat( generated ).hasValue( 1 );

        awaitUntil( () -> generated.get() == 2 );
        sleep( 400 );
        node.awaitIdle();
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void backgroundSitemapIsGeneratedAgainWhenScheduledContentChanges()
    {
        final Node node = node();
        source.set( ( sink, cancelled ) -> {
            source.set( this::generate );
            generate( sink, cancelled );
            return clock.instant().plusMillis( 200 );
        } );

        node.cache.get( KEY, true );
        clock.advance( Duration.ofSeconds( 1 ) );

        awaitUntil( () -> generated.get() == 2 );
        assertThat( store.load( KEY.id() ).validUntil() ).isNull();
    }

    @Test
    void endsTaskAtOnceWhenSitemapIsUpToDate()
    {
        final Node node = node();
        node.cache.get( KEY, false );

        final ProgressReporter progress = mock( ProgressReporter.class );
        node.cache.runTask( KEY, false, null, progress );
        assertThat( generated ).hasValue( 1 );
        verify( progress ).progress( argThat( params -> "Sitemap is up to date".equals( params.getMessage() ) ) );

        node.cache.runTask( KEY, false, clock.instant(), null );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void reportsProgressOfTask()
    {
        source.set( ( sink, cancelled ) -> {
            sink.progress( 0, 2 );
            sink.accept( new SitemapEntry( "/a", SitemapParams.SITE_CONTENT_TYPE, null ) );
            sink.accept( new SitemapEntry( "/b", SitemapParams.SITE_CONTENT_TYPE, null ) );
            sink.progress( 2, 2 );
            return null;
        } );
        final List<String> reports = new ArrayList<>();
        final ProgressReporter progress = mock( ProgressReporter.class );
        doAnswer( invocation -> {
            final ProgressReportParams params = invocation.getArgument( 0 );
            reports.add( params.getMessage() != null ? params.getMessage() : params.getCurrent() + "/" + params.getTotal() );
            return null;
        } ).when( progress ).progress( any( ProgressReportParams.class ) );

        node().cache.runTask( KEY, false, null, progress );

        assertThat( reports ).containsExactly( "Reading contents", "0/2", "2/2", "Storing 2 URLs in 1 file", "Generated 2 URLs in 1 file" );
    }

    @Test
    void keyIdIsDigestOfKey()
    {
        assertThat( KEY.id() ).matches( "[0-9a-f]{64}" ).isEqualTo( new SitemapCache.Key( KEY.repositoryId(), KEY.branch(),
                                                                                           Set.of( "role:system.everyone" ),
                                                                                           PARAMS ).id() );
        assertThat( siteKey( "https://example.com:8443" ).id() ).isNotEqualTo( siteKey( "https://example.com" ).id() );
        assertThat( SitemapCache.Key.restore( KEY.store() ) ).isEqualTo( KEY );
    }

    private Node node()
    {
        return node( Duration.ZERO );
    }

    private Node node( final Duration fileRetention )
    {
        return node( fileRetention, null, null );
    }

    private Node node( final Duration fileRetention, final Executor taskExecutor )
    {
        return node( fileRetention, taskExecutor, null );
    }

    /**
     * A cluster node. Its generation tasks run on the given executor and node, by default its own.
     */
    private Node node( final Duration fileRetention, final Executor taskExecutor, final SitemapCache taskNode )
    {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executors.add( executor );
        final AtomicReference<SitemapCache> self = new AtomicReference<>();
        final SitemapCache.Tasks tasks = ( key, regen, changedAt ) -> {
            final RuntimeException failure = submitFailure.getAndSet( null );
            if ( failure != null )
            {
                throw failure;
            }
            ( taskExecutor != null ? taskExecutor : executor ).execute(
                () -> ( taskNode != null ? taskNode : self.get() ).runTask( key, regen, changedAt, null ) );
        };
        final SitemapCache cache = new SitemapCache( store, key -> ( sink, cancelled ) -> source.get().write( sink, cancelled ), tasks,
                                                     executor, clock, CLUSTER_STARTED, regenerationDelay, fileRetention,
                                                     temp.resolve( "node-" + nodes.size() ) );
        self.set( cache );
        nodes.add( cache );
        return new Node( cache, executor );
    }

    private Instant generate( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        sink.accept( new SitemapEntry( "/" + generated.incrementAndGet(), SitemapParams.SITE_CONTENT_TYPE, null ) );
        return null;
    }

    private static String text( final Sitemap sitemap )
    {
        try
        {
            return new String( sitemap.root().read(), StandardCharsets.UTF_8 );
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( e );
        }
    }

    private static SitemapCache.Key key( final String sitePath )
    {
        return new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone" ),
                                     new SitemapParams( sitePath, "https://example.com", List.of(), List.of() ) );
    }

    private static SitemapCache.Key siteKey( final String siteUrl )
    {
        return new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone" ),
                                     new SitemapParams( "/mysite", siteUrl, List.of(), List.of() ) );
    }

    private static SitemapCache.Key hostKey( final int port )
    {
        return siteKey( "https://example.com:" + ( 8000 + port ) );
    }

    private static SitemapCache.Key repoKey( final String repo )
    {
        return new SitemapCache.Key( repo, "master", Set.of(), PARAMS );
    }

    private static Event nodeEvent( final String type, final String repo, final String branch, final boolean localOrigin )
    {
        return Event.create( type )
            .localOrigin( localOrigin )
            .value( "nodes", List.of( Map.of( "id", "1", "path", "/content/a", "repo", repo, "branch", branch ) ) )
            .build();
    }

    private static Event storedEvent( final String type, final String id )
    {
        return Event.create( type )
            .localOrigin( false )
            .value( "nodes", List.of(
                Map.of( "id", "1", "path", "/" + id, "repo", RepositorySitemapStore.REPOSITORY.toString(), "branch", "master" ) ) )
            .build();
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

    private record Node(SitemapCache cache, ScheduledExecutorService executor)
    {
        void awaitIdle()
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
    }

    private static final class MutableClock
        extends Clock
    {
        private volatile Instant now;

        MutableClock( final Instant now )
        {
            this.now = now;
        }

        void advance( final Duration duration )
        {
            now = now.plus( duration );
        }

        void set( final Instant instant )
        {
            now = instant;
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

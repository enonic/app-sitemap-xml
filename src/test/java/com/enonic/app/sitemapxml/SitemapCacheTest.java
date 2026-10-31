package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.enonic.xp.event.Event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SitemapCacheTest
{
    private static final SitemapParams PARAMS = new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10, 10 );

    private static final SitemapCache.Key KEY = new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone" ), PARAMS );

    private final AtomicInteger generated = new AtomicInteger();

    @Test
    void cachesUntilInvalidatedByNodeEvent()
    {
        final SitemapCache cache = new SitemapCache();

        cache.get( KEY, this::generate );
        cache.get( KEY, this::generate );
        assertThat( generated ).hasValue( 1 );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.other", "master" ) );
        cache.onEvent( nodeEvent( "node.updated", "com.enonic.cms.project", "draft" ) );
        cache.get( KEY, this::generate );
        assertThat( generated ).hasValue( 1 );

        cache.onEvent( nodeEvent( "node.pushed", "com.enonic.cms.project", "master" ) );
        cache.get( KEY, this::generate );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void invalidatedByRepositoryEvent()
    {
        final SitemapCache cache = new SitemapCache();
        cache.get( KEY, this::generate );

        cache.onEvent( Event.create( "repository.deleted" ).value( "id", "com.enonic.cms.project" ).build() );

        assertThat( cache.size() ).isZero();
    }

    @Test
    void invalidatedByPermissionsEvent()
    {
        final SitemapCache cache = new SitemapCache();
        cache.get( KEY, this::generate );

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
        final MutableClock clock = new MutableClock( Instant.parse( "2024-01-01T00:00:00Z" ) );
        final SitemapCache cache = new SitemapCache( clock );
        final Instant validUntil = Instant.parse( "2024-01-01T01:00:00Z" );

        cache.get( KEY, () -> generate( validUntil ) );
        clock.now = validUntil.minusSeconds( 1 );
        cache.get( KEY, () -> generate( validUntil ) );
        assertThat( generated ).hasValue( 1 );

        clock.now = validUntil;
        cache.get( KEY, () -> generate( validUntil ) );
        assertThat( generated ).hasValue( 2 );
    }

    @Test
    void failedGenerationIsNotCached()
    {
        final SitemapCache cache = new SitemapCache();

        assertThatThrownBy( () -> cache.get( KEY, () -> {
            throw new IllegalStateException( "boom" );
        } ) ).isInstanceOf( IllegalStateException.class ).hasMessage( "boom" );

        cache.get( KEY, this::generate );
        assertThat( generated ).hasValue( 1 );
    }

    @Test
    void concurrentRequestsShareOneGeneration()
        throws Exception
    {
        final SitemapCache cache = new SitemapCache();
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final ExecutorService executor = Executors.newFixedThreadPool( 4 );
        try
        {
            final Future<Sitemap> first = executor.submit( () -> cache.get( KEY, () -> {
                started.countDown();
                await( release );
                return generate();
            } ) );
            started.await();
            final List<Future<Sitemap>> others =
                List.of( executor.submit( () -> cache.get( KEY, this::generate ) ), executor.submit( () -> cache.get( KEY, this::generate ) ) );
            release.countDown();

            final Sitemap sitemap = first.get( 5, TimeUnit.SECONDS );
            for ( final Future<Sitemap> other : others )
            {
                assertThat( other.get( 5, TimeUnit.SECONDS ) ).isSameAs( sitemap );
            }
            assertThat( generated ).hasValue( 1 );
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    void evictsOldestWhenFull()
    {
        final MutableClock clock = new MutableClock( Instant.parse( "2024-01-01T00:00:00Z" ) );
        final SitemapCache cache = new SitemapCache( clock );
        for ( int i = 0; i <= SitemapCache.MAX_ENTRIES; i++ )
        {
            clock.now = clock.now.plusSeconds( 1 );
            cache.get( new SitemapCache.Key( "repo" + i, "master", Set.of(), PARAMS ), this::generate );
        }

        assertThat( cache.size() ).isEqualTo( SitemapCache.MAX_ENTRIES );
        cache.get( new SitemapCache.Key( "repo" + SitemapCache.MAX_ENTRIES, "master", Set.of(), PARAMS ), this::generate );
        cache.get( new SitemapCache.Key( "repo0", "master", Set.of(), PARAMS ), this::generate );
        assertThat( generated ).hasValue( SitemapCache.MAX_ENTRIES + 2 );
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

    private static Event nodeEvent( final String type, final String repo, final String branch )
    {
        return Event.create( type ).value( "nodes", List.of( Map.of( "id", "1", "path", "/content/a", "repo", repo, "branch", branch ) ) ).build();
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
        private Instant now;

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

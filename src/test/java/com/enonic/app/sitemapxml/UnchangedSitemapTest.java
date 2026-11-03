package com.enonic.app.sitemapxml;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generations that find no contents changed since the previous sitemap, and read nothing.
 */
class UnchangedSitemapTest
{
    private static final Instant T0 = Instant.parse( "2024-06-01T12:00:00Z" );

    private static final SitemapCache.Key KEY =
        new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone", SitemapCache.Key.ANONYMOUS ),
                              new SitemapParams( "/mysite", "https://example.com", List.of(), List.of() ) );

    @TempDir
    private Path temp;

    private final MutableClock clock = new MutableClock( T0 );

    private final InMemorySitemapStore store = new InMemorySitemapStore();

    private final Contents contents = new Contents();

    private ScheduledExecutorService scheduler;

    private SitemapCache cache;

    private Duration waitBeforeRead = Duration.ZERO;

    @BeforeEach
    void setUp()
    {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        cache = new SitemapCache( store, key -> {
            // as a generation waiting for another one of the project
            clock.advance( waitBeforeRead );
            return contents;
        }, ( key, changedAt ) -> {
        }, scheduler, clock, T0.minusSeconds( 3600 ), Duration.ZERO, Duration.ofMinutes( 1 ), temp.resolve( "cache" ), key -> true );
        for ( int i = 0; i < 3; i++ )
        {
            contents.add( "/item-" + i );
        }
    }

    @AfterEach
    void tearDown()
    {
        cache.deactivate();
        scheduler.shutdownNow();
    }

    @Test
    void sitemapIsReadOnlyWhenContentsChanged()
    {
        generate();
        assertThat( contents.calls ).containsExactly( "write" );
        assertThat( sitemap().count() ).isEqualTo( 3 );

        // nothing changed: nothing read, the version kept
        final String version = store.load( KEY.id() ).version();
        generate();
        assertThat( contents.calls ).containsExactly( "count" );
        assertThat( store.load( KEY.id() ).version() ).isEqualTo( version );

        // a change since the last generation: read whole
        contents.touch( "/item-1" );
        generate();
        assertThat( contents.calls ).containsExactly( "count", "write" );

        // a new content
        contents.add( "/item-3" );
        generate();
        assertThat( contents.calls ).containsExactly( "count", "write" );
        assertThat( sitemap().count() ).isEqualTo( 4 );

        // a deleted content leaves no change time, but makes the count lower
        contents.remove( "/item-0" );
        generate();
        assertThat( contents.calls ).containsExactly( "count", "write" );
        assertThat( SitemapTest.text( sitemap().root() ) ).doesNotContain( "/item-0" );
    }

    @Test
    void contentUnpublishedAfterItWasReadIsLeftOutNextTime()
    {
        // removed right after it was read: the sitemap lists it, the count includes it
        contents.removeAfterWrite = "/item-1";
        generate();
        assertThat( SitemapTest.text( sitemap().root() ) ).contains( "/item-1" );
        assertThat( sitemap().count() ).isEqualTo( 3 );

        generate();
        assertThat( contents.calls ).containsExactly( "count", "write" );
        assertThat( SitemapTest.text( sitemap().root() ) ).doesNotContain( "/item-1" );
    }

    @Test
    void contentChangedWhileItWasReadIsReadAgainNextTime()
    {
        // changed right after the generation started, but read before: the next one must read it again
        contents.touchAfterWrite = "/item-1";
        generate();

        generate();
        assertThat( contents.calls ).containsExactly( "count", "write" );
    }

    @Test
    void sitemapIsReadWholeWhenScheduledContentGoesOnlineOrOffline()
    {
        contents.nextChange = T0.plusSeconds( 15 );
        generate();
        contents.nextChange = null;

        generate();
        assertThat( contents.calls ).containsExactly( "write" );
    }

    @Test
    void sitemapReadWholeWithTheSameFilesKeepsTheVersionAndMovesTheCutoff()
    {
        generate();
        final String version = store.load( KEY.id() ).version();

        // changed the same day: the XML has dates only, so the files stay the same
        contents.touch( "/item-1" );
        generate();
        assertThat( contents.calls ).containsExactly( "count", "write" );
        assertThat( store.load( KEY.id() ).version() ).isEqualTo( version );

        // the change is before the generation that found the files unchanged
        generate();
        assertThat( contents.calls ).containsExactly( "count" );
        assertThat( store.load( KEY.id() ).version() ).isEqualTo( version );
    }

    @Test
    void sitemapIsReadWholeWhenScheduledContentGoesOnlineWhileTheGenerationWaits()
    {
        contents.nextChange = T0.plusSeconds( 35 );
        generate();
        contents.nextChange = null;

        // claimed before the scheduled change, read after it
        waitBeforeRead = Duration.ofSeconds( 10 );
        generate();
        assertThat( contents.calls ).containsExactly( "write" );
    }

    private void generate()
    {
        contents.calls.clear();
        clock.advance( Duration.ofSeconds( 10 ) );
        cache.runTask( KEY, clock.instant(), null );
        clock.advance( Duration.ofSeconds( 10 ) );
    }

    private Sitemap sitemap()
    {
        return Objects.requireNonNull( store.load( KEY.id() ).sitemap() );
    }

    /**
     * Contents with the times of their last change, as the content API counts and reads them.
     */
    private final class Contents
        implements SitemapCache.Source
    {
        private final List<Item> items = new ArrayList<>();

        private final List<String> calls = new ArrayList<>();

        private long read;

        private @Nullable String removeAfterWrite;

        private @Nullable String touchAfterWrite;

        private @Nullable Instant nextChange;

        void add( final String path )
        {
            items.add( new Item( path, clock.instant() ) );
        }

        void touch( final String path )
        {
            final Item item = items.stream().filter( i -> i.path().equals( path ) ).findFirst().orElseThrow();
            items.set( items.indexOf( item ), new Item( path, clock.instant() ) );
        }

        void remove( final String path )
        {
            items.removeIf( item -> item.path().equals( path ) );
        }

        @Override
        public @Nullable Instant write( final SitemapSink sink, final BooleanSupplier cancelled )
        {
            calls.add( "write" );
            final List<Item> sorted = items.stream().sorted( Comparator.comparing( Item::path ) ).toList();
            sorted.forEach( item -> sink.accept( new SitemapEntry( item.path(), SitemapParams.SITE_CONTENT_TYPE, item.changed() ) ) );
            read = sorted.size();
            if ( removeAfterWrite != null )
            {
                remove( removeAfterWrite );
                removeAfterWrite = null;
            }
            if ( touchAfterWrite != null )
            {
                // the change is timed within the margin before the generation started
                final Item item = items.stream().filter( i -> i.path().equals( touchAfterWrite ) ).findFirst().orElseThrow();
                items.set( items.indexOf( item ), new Item( item.path(), clock.instant().minusSeconds( 1 ) ) );
                touchAfterWrite = null;
            }
            return nextChange;
        }

        @Override
        public boolean counts()
        {
            return true;
        }

        @Override
        public SitemapQuery.Count count( final Instant since )
        {
            calls.add( "count" );
            return new SitemapQuery.Count( items.size(), items.stream().filter( item -> !item.changed().isBefore( since ) ).count() );
        }

        @Override
        public long read()
        {
            return read;
        }

        @Override
        public @Nullable Instant nextChange()
        {
            return nextChange;
        }
    }

    private record Item(String path, Instant changed)
    {
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

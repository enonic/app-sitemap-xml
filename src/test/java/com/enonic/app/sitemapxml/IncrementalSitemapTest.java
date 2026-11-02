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
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generations that write only the groups of files whose contents changed.
 */
class IncrementalSitemapTest
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

    @BeforeEach
    void setUp()
    {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        cache = new SitemapCache( store, key -> contents, ( key, changedAt ) -> {
        }, scheduler, clock, T0.minusSeconds( 3600 ), Duration.ZERO, Duration.ofMinutes( 1 ), temp.resolve( "cache" ), key -> true );
        cache.urlsPerFile( urlsPerFile );
        for ( int i = 0; i < 7; i++ )
        {
            contents.add( "/item-" + i, T0.minusSeconds( 70 - 10L * i ) );
        }
    }

    @AfterEach
    void tearDown()
    {
        cache.deactivate();
        scheduler.shutdownNow();
    }

    @Test
    void writesOnlyGroupsWhoseContentsChanged()
    {
        generate();
        assertThat( contents.calls ).containsExactly( "write", "counts" );
        final Sitemap first = sitemap();
        assertThat( first.fileNumbers() ).containsExactly( 1, 2, 3, 4 );
        assertThat( first.groupStarts() ).containsExactly( null, T0.minusSeconds( 50 ), T0.minusSeconds( 30 ), T0.minusSeconds( 10 ) );
        assertThat( first.groupCounts() ).containsExactly( 2L, 2L, 2L, 1L );

        // nothing changed: nothing read, the version kept
        final String version = store.load( KEY.id() ).version();
        generate();
        assertThat( contents.calls ).containsExactly( "counts" );
        assertThat( store.load( KEY.id() ).version() ).isEqualTo( version );

        // an edit in the second group: only it is read again, under its number
        contents.touch( "/item-3" );
        generate();
        assertThat( contents.calls ).containsExactly( "counts", "range " + T0.minusSeconds( 50 ), "counts" );
        final Sitemap edited = sitemap();
        assertThat( edited.fileNumbers() ).containsExactly( 1, 2, 3, 4 );
        assertThat( digest( edited, 1 ) ).isEqualTo( digest( first, 1 ) );
        assertThat( digest( edited, 3 ) ).isEqualTo( digest( first, 3 ) );
        // read again, but the XML holds dates: an edit the same day gives the same file, which keeps its modification time
        assertThat( digest( edited, 2 ) ).isEqualTo( digest( first, 2 ) );
        assertThat( edited.lastModified() ).isEqualTo( first.lastModified() );
        assertThat( text( edited, 2 ) ).contains( "/item-2", "/item-3" );

        // new contents go to the last group, which grows into a file of a new number
        contents.add( "/new-1", clock.instant() );
        contents.add( "/new-2", clock.instant().plusMillis( 1 ) );
        contents.add( "/new-3", clock.instant().plusMillis( 2 ) );
        generate();
        assertThat( contents.calls ).containsExactly( "counts", "range " + T0.minusSeconds( 10 ), "counts" );
        final Sitemap grown = sitemap();
        assertThat( grown.fileNumbers() ).containsExactly( 1, 2, 3, 4, 5 );
        assertThat( text( grown, 4 ) ).contains( "/item-6", "/new-1" );
        assertThat( text( grown, 5 ) ).contains( "/new-2", "/new-3" );
        assertThat( digest( grown, 2 ) ).isEqualTo( digest( edited, 2 ) );
        assertThat( grown.size() ).isEqualTo( 10 );

        // a group whose contents are gone leaves its number out
        contents.remove( "/item-2" );
        contents.remove( "/item-3" );
        generate();
        assertThat( contents.calls ).containsExactly( "counts", "range " + T0.minusSeconds( 50 ), "counts" );
        final Sitemap shrunk = sitemap();
        assertThat( shrunk.fileNumbers() ).containsExactly( 1, 3, 4, 5 );
        assertThat( shrunk.size() ).isEqualTo( 8 );
        assertThat( SitemapTest.text( shrunk.root() ) ).contains( "sitemap-1.xml", "sitemap-3.xml", "sitemap-4.xml", "sitemap-5.xml" )
            .doesNotContain( "sitemap-2.xml" );
        assertThat( shrunk.file( 2 ) ).isEmpty();
        assertThat( SitemapTest.text( shrunk.file( 5 ).orElseThrow() ) ).contains( "/new-3" );
    }

    @Test
    void sitemapIsWrittenWholeWhenItFitsInOneFile()
    {
        urlsPerFile = 10;
        cache.urlsPerFile( urlsPerFile );
        generate();
        assertThat( sitemap().fileNumbers() ).containsExactly( 1 );

        contents.touch( "/item-3" );
        generate();
        assertThat( contents.calls ).containsExactly( "counts", "write", "counts" );
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

    private static String digest( final Sitemap sitemap, final int number )
    {
        return sitemap.fileDigest( number );
    }

    private static String text( final Sitemap sitemap, final int number )
    {
        return SitemapTest.text( sitemap.file( number ).orElseThrow() );
    }

    /**
     * Contents with their creation times and the times of their last change, as the content API counts and reads them.
     */
    private final class Contents
        implements SitemapCache.Source
    {
        private final List<Item> items = new ArrayList<>();

        private final List<String> calls = new ArrayList<>();

        void add( final String path, final Instant created )
        {
            items.add( new Item( path, created, clock.instant() ) );
        }

        void touch( final String path )
        {
            final Item item = items.stream().filter( i -> i.path().equals( path ) ).findFirst().orElseThrow();
            items.set( items.indexOf( item ), new Item( path, item.created(), clock.instant() ) );
        }

        void remove( final String path )
        {
            items.removeIf( item -> item.path().equals( path ) );
        }

        @Override
        public @Nullable Instant write( final SitemapSink sink, final BooleanSupplier cancelled )
        {
            calls.add( "write" );
            // as the content API reads it: by path when it fits in one file
            if ( items.size() <= urlsPerFile )
            {
                items.stream().sorted( Comparator.comparing( Item::path ) ).forEach( item -> sink.accept( item.entry( false ) ) );
            }
            else
            {
                sorted().forEach( item -> sink.accept( item.entry( true ) ) );
            }
            return null;
        }

        @Override
        public boolean ranges()
        {
            return true;
        }

        @Override
        public List<SitemapQuery.RangeCount> counts( final List<@Nullable Instant> starts, final Instant since )
        {
            calls.add( "counts" );
            final List<SitemapQuery.RangeCount> counts = new ArrayList<>();
            for ( int i = 0; i < starts.size(); i++ )
            {
                final Instant from = starts.get( i );
                final Instant to = i + 1 < starts.size() ? starts.get( i + 1 ) : null;
                final List<Item> in = items.stream().filter( item -> in( item, from, to ) ).toList();
                counts.add( new SitemapQuery.RangeCount( in.size(), in.stream().filter( item -> !item.changed().isBefore( since ) ).count() ) );
            }
            return counts;
        }

        @Override
        public void writeRange( final @Nullable Instant from, final @Nullable Instant to, final SitemapSink sink,
                                final BooleanSupplier cancelled )
        {
            calls.add( "range " + from );
            sorted().filter( item -> in( item, from, to ) ).forEach( item -> sink.accept( item.entry( true ) ) );
        }

        private Stream<Item> sorted()
        {
            return items.stream().sorted( Comparator.comparing( Item::created ).thenComparing( Item::path ) );
        }

        private static boolean in( final Item item, final @Nullable Instant from, final @Nullable Instant to )
        {
            return ( from == null || !item.created().isBefore( from ) ) && ( to == null || item.created().isBefore( to ) );
        }
    }

    private int urlsPerFile = 2;

    private record Item(String path, Instant created, Instant changed)
    {
        SitemapEntry entry( final boolean creationOrder )
        {
            return new SitemapEntry( path, SitemapParams.SITE_CONTENT_TYPE, changed, creationOrder ? created : null );
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

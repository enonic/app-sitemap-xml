package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.io.ByteSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SitemapTest
{
    static final Instant NOW = Instant.parse( "2024-06-01T00:00:00Z" );

    private static final List<ContentTypeSetting> CONTENT_TYPES = List.of( new ContentTypeSetting( "base:folder", "daily", "0.8" ) );

    @TempDir
    private Path temp;

    @Test
    void singleFileIsUrlset()
    {
        final Sitemap sitemap = write( params( "https://example.com/" ), 10,
                                       List.of( new SitemapEntry( "", "portal:site", Instant.parse( "2024-02-02T09:01:33.169Z" ) ),
                                                new SitemapEntry( "/news/a b&c", "base:folder", null ) ) );

        assertThat( sitemap.isIndex() ).isFalse();
        assertThat( text( sitemap.root() ) ).isEqualTo( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                                            "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">" +
                                                            "<url><loc>https://example.com/</loc><lastmod>2024-02-02</lastmod>" +
                                                            "<changefreq>hourly</changefreq><priority>1.0</priority></url>" +
                                                            "<url><loc>https://example.com/news/a%20b&amp;c</loc>" +
                                                            "<changefreq>daily</changefreq><priority>0.8</priority></url>" + "</urlset>" );
        assertThat( sitemap.file( 1 ) ).isEmpty();
    }

    @Test
    void splitsIntoIndexAndFiles()
    {
        final List<SitemapEntry> entries = IntStream.rangeClosed( 1, 5 )
            .mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", Instant.parse( "2024-01-0" + ( 6 - i ) + "T10:00:00Z" ) ) )
            .toList();
        final Sitemap sitemap = write( params( "https://example.com/site/project/master/mysite" ), 2, entries );

        assertThat( sitemap.fileCount() ).isEqualTo( 3 );
        assertThat( text( sitemap.root() ) ).isEqualTo( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                                            "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">" +
                                                            "<sitemap><loc>https://example.com/site/project/master/mysite/sitemap-1.xml</loc>" +
                                                            "<lastmod>2024-06-01T00:00:00Z</lastmod></sitemap>" +
                                                            "<sitemap><loc>https://example.com/site/project/master/mysite/sitemap-2.xml</loc>" +
                                                            "<lastmod>2024-06-01T00:00:00Z</lastmod></sitemap>" +
                                                            "<sitemap><loc>https://example.com/site/project/master/mysite/sitemap-3.xml</loc>" +
                                                            "<lastmod>2024-06-01T00:00:00Z</lastmod></sitemap>" + "</sitemapindex>" );
        assertThat( text( sitemap.file( 3 ).orElseThrow() ) ).contains( "<loc>https://example.com/site/project/master/mysite/item-5</loc>" )
            .doesNotContain( "item-4" );
        assertThat( sitemap.file( 0 ) ).isEmpty();
        assertThat( sitemap.file( 4 ) ).isEmpty();
    }

    @Test
    void emptySitemapIsEmptyUrlset()
    {
        final Sitemap sitemap = write( params( "https://example.com" ), 10, List.of() );

        assertThat( sitemap.fileCount() ).isEqualTo( 1 );
        assertThat( text( sitemap.root() ) ).endsWith( "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>" );
    }

    @Test
    void encodesPathSegmentsLikePortalUrls()
    {
        final StringBuilder out = new StringBuilder();
        SitemapRenderer.encodePathSegment( out, "æøå ~-._!$&'()*+,;=:@?#/%", false );

        assertThat( out ).hasToString( "%C3%A6%C3%B8%C3%A5%20~-._!$&'()*+,;=:@%3F%23%2F%25" );
    }

    @Test
    void headlessPathsArePercentEncodedAndSafeInXml()
    {
        final StringBuilder out = new StringBuilder();
        SitemapRenderer.encodePathSegment( out, "æ ~-._!$&'()*+,;=:@<>\"", true );
        assertThat( out ).hasToString( "%C3%A6%20~-._!$%26%27()*+,;=:@%3C%3E%22" );

        final Sitemap sitemap =
            write( headless(), 10, List.of( new SitemapEntry( "", "portal:site", null ), new SitemapEntry( "/a&b/æ 'c'", "base:folder", null ) ) );
        assertThat( sitemap.urlsetJson( 10 ) ).contains( "\"path\":\"/\"" ).contains( "\"path\":\"/a%26b/%C3%A6%20%27c%27\"" );
    }

    @Test
    void splitsFilesAtByteLimit()
        throws IOException
    {
        final List<SitemapEntry> entries =
            IntStream.rangeClosed( 1, 5 ).mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", null ) ).toList();
        final long urlBytes = SitemapRenderer.utf8Length(
            "<url><loc>https://example.com/item-1</loc><changefreq>daily</changefreq><priority>0.8</priority></url>" );
        final long overhead = write( params( "https://example.com" ), 10, List.of() ).root().size();
        final long maxBytes = overhead + 2 * urlBytes;

        final Sitemap sitemap = write( temp.resolve( "split" ), params( "https://example.com" ), entries, 10, maxBytes, 10 );

        assertThat( sitemap.fileCount() ).isEqualTo( 3 );
        for ( int number = 1; number <= 3; number++ )
        {
            assertThat( sitemap.file( number ).orElseThrow().size() ).isLessThanOrEqualTo( maxBytes );
        }
        assertThat( text( sitemap.file( 3 ).orElseThrow() ) ).contains( "item-5" );
    }

    @Test
    void leavesOutTooLongUrls()
    {
        final String longName = "a".repeat( Sitemap.MAX_LOC_LENGTH );
        final Sitemap sitemap = write( params( "https://example.com" ), 10, List.of( new SitemapEntry( "/ok", "base:folder", null ),
                                                                                     new SitemapEntry( "/" + longName, "base:folder",
                                                                                                       null ) ) );

        assertThat( sitemap.size() ).isEqualTo( 1 );
        assertThat( text( sitemap.root() ) ).contains( "https://example.com/ok" ).doesNotContain( longName );
    }

    @Test
    void headlessLeavesOutUrlsTooLongWithTheLongestBaseUrl()
    {
        final String fits = "a".repeat( Sitemap.MAX_LOC_LENGTH - Sitemap.MAX_BASE_URL_LENGTH - 1 );
        final String tooLong = "b".repeat( Sitemap.MAX_LOC_LENGTH - Sitemap.MAX_BASE_URL_LENGTH );
        final List<SitemapEntry> entries =
            List.of( new SitemapEntry( "/" + fits, "base:folder", null ), new SitemapEntry( "/" + tooLong, "base:folder", null ) );

        assertThat( write( params( "https://example.com" ), 10, entries ).size() ).isEqualTo( 2 );
        final Sitemap headless = write( headless(), 10, entries );
        assertThat( headless.size() ).isEqualTo( 1 );
        assertThat( headless.urlsetJson( 10 ) ).contains( fits ).doesNotContain( tooLong );
    }

    @Test
    void headlessFilesFitWhatNextJsWritesWithTheLongestBaseUrl()
    {
        final Instant lastmod = Instant.parse( "2024-01-01T10:00:00.123456789Z" );
        final List<SitemapEntry> entries =
            IntStream.range( 0, 50 ).mapToObj( i -> new SitemapEntry( "/item-" + i + "/æ ø", "base:folder", lastmod ) ).toList();
        final long maxFileBytes = 10_000;

        final Sitemap headless = write( temp.resolve( "headless" ), headless(), entries, 1_000, maxFileBytes, Sitemap.MAX_FILES );
        final Sitemap shortHost =
            write( temp.resolve( "short" ), params( "https://example.com" ), entries, 1_000, maxFileBytes, Sitemap.MAX_FILES );

        assertThat( headless.size() ).isEqualTo( 50 );
        assertThat( headless.fileCount() ).isGreaterThan( shortHost.fileCount() );
        final String baseUrl = "https://" + "a".repeat( Sitemap.MAX_BASE_URL_LENGTH - "https://".length() );
        for ( int number = 1; number <= headless.fileCount(); number++ )
        {
            final String json = headless.fileUrlsetJson( number ).orElseThrow();
            // as Next.js resolveSitemap writes the file: the base URL, the path as returned, the full time
            final StringBuilder xml = new StringBuilder( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" ).append(
                "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" );
            for ( final String path : json.split( "\"path\":\"" ) )
            {
                if ( path.startsWith( "/" ) )
                {
                    final String encoded = path.substring( 0, path.indexOf( '"' ) );
                    assertThat( encoded ).doesNotContain( " ", "æ", "ø" );
                    xml.append( "<url>\n<loc>" ).append( baseUrl ).append( encoded ).append( "</loc>\n" )
                        .append( "<lastmod>" ).append( lastmod ).append( "</lastmod>\n" )
                        .append( "<changefreq>monthly</changefreq>\n<priority>0.8</priority>\n</url>\n" );
                }
            }
            xml.append( "</urlset>\n" );
            assertThat( SitemapRenderer.utf8Length( xml ) ).isLessThanOrEqualTo( maxFileBytes );
        }
    }

    @Test
    void indexListsAtMostMaxFiles()
    {
        final List<SitemapEntry> entries =
            IntStream.range( 0, 7 ).mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", null ) ).toList();

        final Sitemap sitemap = write( temp.resolve( "capped" ), params( "https://example.com" ), entries, 2, Sitemap.MAX_FILE_BYTES, 3 );

        assertThat( sitemap.fileCount() ).isEqualTo( 3 );
        assertThat( sitemap.size() ).isEqualTo( 6 );
        assertThat( sitemap.file( 4 ) ).isEmpty();

        final Sitemap headless = write( temp.resolve( "headless" ), headless(), entries, 2, Sitemap.MAX_FILE_BYTES, 3 );
        assertThat( headless.fileCount() ).isEqualTo( 3 );
        assertThat( headless.urlsetJson( 10 ) ).doesNotContain( "item-6" );
    }

    @Test
    void deleteRemovesFiles()
    {
        final Sitemap sitemap = write( params( "https://example.com" ), 1, List.of( new SitemapEntry( "/a", "base:folder", null ),
                                                                                    new SitemapEntry( "/b", "base:folder", null ) ) );
        assertThat( sitemap.directory() ).isDirectory();

        sitemap.delete();

        assertThat( sitemap.directory() ).doesNotExist();
    }

    @Test
    void failedWriteLeavesNoFiles()
        throws IOException
    {
        final Path blocked = temp.resolve( "blocked" );
        Files.writeString( blocked, "not a directory" );

        assertThatThrownBy( () -> Sitemap.builder( blocked.resolve( "sitemap" ), params( "https://example.com" ) ) ).isInstanceOf(
            UncheckedIOException.class );
        assertThat( Files.exists( blocked.resolve( "sitemap" ) ) ).isFalse();
    }

    @Test
    void closingUnbuiltBuilderDeletesFiles()
    {
        final Path directory = temp.resolve( "unfinished" );
        try (Sitemap.Builder builder = Sitemap.builder( directory, params( "https://example.com" ) ))
        {
            builder.add( new SitemapEntry( "/a", "base:folder", null ) );
            assertThat( directory ).isDirectory();
        }

        assertThat( Files.exists( directory ) ).isFalse();
    }

    @Test
    void writesHugeSitemapWithoutLimit()
    {
        final Path directory = temp.resolve( "huge" );
        final Sitemap sitemap;
        try (Sitemap.Builder builder = Sitemap.builder( directory, params( "https://example.com" ) ))
        {
            for ( int i = 0; i < 120_000; i++ )
            {
                builder.add( new SitemapEntry( "/item-" + i, "base:folder", null ) );
            }
            sitemap = builder.build( null, NOW );
        }

        assertThat( sitemap.size() ).isEqualTo( 120_000 );
        assertThat( sitemap.fileCount() ).isEqualTo( 3 );
        assertThat( text( sitemap.file( 3 ).orElseThrow() ) ).contains( "/item-119999<" ).doesNotContain( "/item-99999<" );
    }

    @Test
    void gzipsRootAndFiles()
        throws IOException
    {
        final List<SitemapEntry> entries = IntStream.rangeClosed( 1, 3 )
            .mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", Instant.parse( "2024-01-0" + i + "T10:00:00Z" ) ) )
            .toList();
        final Sitemap sitemap = write( params( "https://example.com" ), 2, entries );

        assertThat( gunzip( sitemap.rootGzip() ) ).isEqualTo( text( sitemap.root() ).replace( ".xml<", ".xml.gz<" ) )
            .contains( "<loc>https://example.com/sitemap-1.xml.gz</loc>" );
        assertThat( gunzip( sitemap.rootEncoded() ) ).isEqualTo( text( sitemap.root() ) )
            .contains( "<loc>https://example.com/sitemap-1.xml</loc>" );
        assertThat( gunzip( sitemap.fileGzip( 2 ).orElseThrow() ) ).isEqualTo( text( sitemap.file( 2 ).orElseThrow() ) );
        assertThat( sitemap.fileGzip( 3 ) ).isEmpty();
        assertThat( sitemap.fileGzip( 0 ) ).isEmpty();
    }

    @Test
    void gzipsSingleFileSitemap()
        throws IOException
    {
        final Sitemap sitemap = write( params( "https://example.com" ), 10, List.of( new SitemapEntry( "/a", "base:folder", null ) ) );

        assertThat( gunzip( sitemap.rootGzip() ) ).isEqualTo( text( sitemap.root() ) );
        assertThat( gunzip( sitemap.rootEncoded() ) ).isEqualTo( text( sitemap.root() ) );
        assertThat( sitemap.fileGzip( 1 ) ).isEmpty();
    }

    private static String gunzip( final ByteSource source )
        throws IOException
    {
        try (GZIPInputStream in = new GZIPInputStream( source.openStream() ))
        {
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    @Test
    void storesOnlyGzipFilesAndKnowsPlainSizes()
        throws IOException
    {
        final List<SitemapEntry> entries = IntStream.rangeClosed( 1, 3 )
            .mapToObj( i -> new SitemapEntry( "/æøå-" + i, "base:folder", Instant.parse( "2024-01-0" + i + "T10:00:00Z" ) ) )
            .toList();
        final Sitemap sitemap = write( params( "https://example.com" ), 2, entries );

        try (var files = Files.list( sitemap.directory() ))
        {
            assertThat( files.map( path -> path.getFileName().toString() ) ).containsExactlyInAnyOrder( "sitemap.xml.gz",
                                                                                                       "sitemap-index.xml.gz",
                                                                                                       "sitemap-1.xml.gz",
                                                                                                       "sitemap-2.xml.gz" );
        }
        final Sitemap headless = write( headless(), 2, entries );
        try (var files = Files.list( headless.directory() ))
        {
            assertThat( files.map( path -> path.getFileName().toString() ) ).containsExactlyInAnyOrder( "urlset-1.jsonl.gz",
                                                                                                       "urlset-2.jsonl.gz" );
        }
        for ( final ByteSource plain : List.of( sitemap.root(), sitemap.file( 1 ).orElseThrow(), sitemap.file( 2 ).orElseThrow() ) )
        {
            assertThat( plain.sizeIfKnown().get() ).isEqualTo( plain.read().length );
        }
    }

    @Test
    void urlsetForHeadlessFollowsFileSplit()
    {
        final List<SitemapEntry> entries = List.of( new SitemapEntry( "", "portal:site", Instant.parse( "2024-01-03T10:00:00Z" ) ),
                                                    new SitemapEntry( "/a", "base:folder", Instant.parse( "2024-01-02T10:00:00Z" ) ),
                                                    new SitemapEntry( "/b c", "other:type", null ) );
        final SitemapParams params = new SitemapParams( "/mysite", "https://example.com",
                                                        List.of( new ContentTypeSetting( "base:folder", "daily", "0.8" ),
                                                                 new ContentTypeSetting( "other:type", "", "" ) ), List.of(), 10_000,
                                                        SitemapParams.Format.HEADLESS );
        final Sitemap sitemap = write( params, 2, entries );

        assertThat( sitemap.lastModified() ).containsExactly( NOW, NOW );
        assertThat( sitemap.urlsetJson( 10 ) ).isEqualTo(
            "[{\"path\":\"/\",\"lastmod\":\"2024-01-03T10:00:00Z\",\"changefreq\":\"hourly\",\"priority\":\"1.0\"}," +
                "{\"path\":\"/a\",\"lastmod\":\"2024-01-02T10:00:00Z\",\"changefreq\":\"daily\",\"priority\":\"0.8\"}," +
                "{\"path\":\"/b%20c\"}]" );
        assertThat( sitemap.urlsetJson( 2 ) ).doesNotContain( "/b%20c" ).contains( "\"/a\"" );
        assertThat( sitemap.urlsetJson( 0 ) ).isEqualTo( "[]" );
        assertThat( sitemap.fileUrlsetJson( 2 ) ).contains( "[{\"path\":\"/b%20c\"}]" );
        assertThat( sitemap.fileUrlsetJson( 3 ) ).isEmpty();
        assertThat( sitemap.fileUrlsetJson( 0 ) ).isEmpty();
    }

    @Test
    void filesKeepTheirModificationTimeOnlyWhileTheirContentIsTheSame()
    {
        final List<SitemapEntry> entries = IntStream.rangeClosed( 1, 5 )
            .mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", Instant.parse( "2024-01-0" + ( 6 - i ) + "T10:00:00Z" ) ) )
            .toList();
        final Sitemap first = write( params( "https://example.com" ), 2, entries );
        final Instant later = NOW.plusSeconds( 3600 );

        final Sitemap same = build( params( "https://example.com" ), 2, entries, first, later );
        assertThat( same.lastModified() ).containsExactly( NOW, NOW, NOW );

        final List<SitemapEntry> withoutOldest = entries.subList( 0, 4 );
        final Sitemap changed = build( params( "https://example.com" ), 2, withoutOldest, same, later );
        assertThat( changed.fileCount() ).isEqualTo( 2 );
        assertThat( changed.lastModified() ).containsExactly( NOW, NOW );

        final List<SitemapEntry> withoutNewest = entries.subList( 1, 5 );
        final Sitemap shifted = build( params( "https://example.com" ), 2, withoutNewest, same, later );
        assertThat( shifted.lastModified() ).containsExactly( later, later );
        assertThat( text( shifted.root() ) ).contains( "<lastmod>2024-06-01T01:00:00Z</lastmod>" )
            .doesNotContain( "<lastmod>2024-06-01T00:00:00Z</lastmod>" );
    }

    @Test
    void headlessFileChangesWhenOnlyTimesChangeWithinTheDayButXmlFileDoesNot()
    {
        final List<SitemapEntry> entries = List.of( new SitemapEntry( "/a", "base:folder", Instant.parse( "2024-01-05T10:00:00Z" ) ),
                                                    new SitemapEntry( "/b", "base:folder", Instant.parse( "2024-01-04T10:00:00Z" ) ) );
        final Sitemap first = write( params( "https://example.com" ), 10, entries );
        final List<SitemapEntry> edited = List.of( new SitemapEntry( "/a", "base:folder", Instant.parse( "2024-01-05T11:00:00Z" ) ),
                                                   entries.get( 1 ) );
        final Instant later = NOW.plusSeconds( 3600 );

        final Sitemap same = build( params( "https://example.com" ), 10, entries, first, later );
        final Sitemap changed = build( params( "https://example.com" ), 10, edited, same, later );

        assertThat( same.lastModified() ).containsExactly( NOW );
        assertThat( changed.rootETag( false ) ).isEqualTo( first.rootETag( false ) );
        assertThat( changed.lastModified() ).containsExactly( NOW );

        final Sitemap headless = write( headless(), 10, entries );
        assertThat( build( headless(), 10, entries, headless, later ).lastModified() ).containsExactly( NOW );
        assertThat( build( headless(), 10, edited, headless, later ).lastModified() ).containsExactly( later );
    }

    @Test
    void entityTagsAreDigestsOfContentAndSurviveRestore()
    {
        final List<SitemapEntry> entries =
            IntStream.rangeClosed( 1, 5 ).mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", null ) ).toList();
        final Sitemap split = write( params( "https://example.com" ), 2, entries );
        final Sitemap again = write( params( "https://example.com" ), 2, entries );

        assertThat( split.rootETag( false ) ).matches( "\"[0-9a-f]{32}\"" ).isEqualTo( again.rootETag( false ) );
        final String plain = split.rootETag( false );
        assertThat( split.rootETag( true ) ).isEqualTo( plain.substring( 0, plain.length() - 1 ) + "-gzip\"" );
        assertThat( split.rootGzipETag() ).matches( "\"[0-9a-f]{32}-gzip\"" ).isNotEqualTo( split.rootETag( true ) );
        assertThat( split.fileETag( 1, false ) ).isNotEqualTo( split.fileETag( 2, false ) ).isEqualTo( again.fileETag( 1, false ) );
        assertThat( split.fileETag( 4, false ) ).isEmpty();

        final Sitemap restored = Sitemap.restore( split.directory(), split.store() );
        assertThat( restored.rootETag( false ) ).isEqualTo( split.rootETag( false ) );
        assertThat( restored.rootGzipETag() ).isEqualTo( split.rootGzipETag() );
        assertThat( restored.fileETag( 3, true ) ).isEqualTo( split.fileETag( 3, true ) );

        final Sitemap single = write( params( "https://example.com" ), 10, entries );
        assertThat( single.rootETag( false ) ).isNotNull().isNotEqualTo( split.rootETag( false ) );
        assertThat( single.rootGzipETag() ).isEqualTo( single.rootETag( true ) );
        assertThat( write( params( "https://other.example.com" ), 10, entries ).rootETag( false ) ).isNotEqualTo( single.rootETag( false ) );
    }

    @Test
    void urlsetForHeadlessOfSingleAndEmptySitemap()
    {
        final Sitemap single = write( headless(), 10, List.of( new SitemapEntry( "/a", "base:folder", null ) ) );
        assertThat( single.lastModified() ).hasSize( 1 );
        assertThat( single.fileUrlsetJson( 1 ) ).contains( "[{\"path\":\"/a\",\"changefreq\":\"daily\",\"priority\":\"0.8\"}]" );

        final Sitemap empty = write( headless(), 10, List.of() );
        assertThat( empty.lastModified() ).hasSize( 1 );
        assertThat( empty.urlsetJson( 10 ) ).isEqualTo( "[]" );
        assertThat( empty.fileUrlsetJson( 1 ) ).contains( "[]" );
    }

    @Test
    void rootKeepsItsOwnModificationTimeWhichNeverGoesBack()
    {
        final List<SitemapEntry> entries =
            IntStream.range( 0, 5 ).mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", null ) ).toList();
        final Sitemap first = write( params( "https://example.com" ), 2, entries );
        assertThat( first.rootLastModified() ).isEqualTo( NOW );

        // the same files again: the root keeps its time
        final Instant later = NOW.plusSeconds( 60 );
        final Sitemap same = build( params( "https://example.com" ), 2, entries, first, later );
        assertThat( same.rootLastModified() ).isEqualTo( NOW );

        // the last file changes: it and the root get the time
        final List<SitemapEntry> changed = new ArrayList<>( entries );
        changed.set( 4, new SitemapEntry( "/changed", "base:folder", null ) );
        final Sitemap edited = build( params( "https://example.com" ), 2, changed, same, later );
        assertThat( edited.lastModified() ).containsExactly( NOW, NOW, later );
        assertThat( edited.rootLastModified() ).isEqualTo( later );

        // that file leaves the index: the latest of the files is older, the root is newer
        final Instant latest = later.plusSeconds( 60 );
        final Sitemap shrunk = build( params( "https://example.com" ), 2, entries.subList( 0, 4 ), edited, latest );
        assertThat( shrunk.lastModified() ).containsExactly( NOW, NOW );
        assertThat( shrunk.rootLastModified() ).isEqualTo( latest );
        assertThat( Sitemap.restore( temp, shrunk.withCount( 4, latest ).store() ) )
            .satisfies( restored -> {
                assertThat( restored.rootLastModified() ).isEqualTo( latest );
                assertThat( restored.count() ).isEqualTo( 4 );
                assertThat( restored.cutoff() ).isEqualTo( latest );
            } );
    }

    @Test
    void utf8Length()
    {
        assertThat( SitemapRenderer.utf8Length( "aæ€😀" ) ).isEqualTo( "aæ€😀".getBytes( StandardCharsets.UTF_8 ).length );
    }

    private Sitemap write( final SitemapParams params, final int urlsPerFile, final List<SitemapEntry> entries )
    {
        return write( temp.resolve( "sitemap-" + System.nanoTime() ), params, entries, urlsPerFile, Sitemap.MAX_FILE_BYTES,
                      Sitemap.MAX_FILES );
    }

    static Sitemap write( final Path directory, final SitemapParams params, final List<SitemapEntry> entries, final int urlsPerFile,
                          final long maxFileBytes, final int maxFiles )
    {
        try (Sitemap.Builder builder = Sitemap.builder( directory, params, urlsPerFile, maxFileBytes, maxFiles ))
        {
            entries.forEach( builder::add );
            return builder.build( null, NOW );
        }
    }

    private Sitemap build( final SitemapParams params, final int urlsPerFile, final List<SitemapEntry> entries, final Sitemap previous,
                           final Instant now )
    {
        try (Sitemap.Builder builder = Sitemap.builder( temp.resolve( "sitemap-" + System.nanoTime() ), params, urlsPerFile,
                                                        Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES ))
        {
            entries.forEach( builder::add );
            return builder.build( previous, now );
        }
    }

    static String text( final ByteSource source )
    {
        try
        {
            return source.asCharSource( StandardCharsets.UTF_8 ).read();
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
    }

    private static SitemapParams params( final String siteUrl )
    {
        return new SitemapParams( "/mysite", siteUrl, CONTENT_TYPES, List.of(), 10_000 );
    }

    private static SitemapParams headless()
    {
        return params( "" ).withFormat( SitemapParams.Format.HEADLESS );
    }
}

package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.io.ByteSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SitemapTest
{
    private static final List<ContentTypeSetting> CONTENT_TYPES = List.of( new ContentTypeSetting( "base:folder", "daily", "0.8" ) );

    @TempDir
    private Path temp;

    @Test
    void singleFileIsUrlset()
    {
        final Sitemap sitemap = write( params( "https://example.com/", 10 ),
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
            .collect( Collectors.toList() );
        final Sitemap sitemap = write( params( "https://example.com/site/project/master/mysite", 2 ), entries );

        assertThat( sitemap.fileCount() ).isEqualTo( 3 );
        assertThat( text( sitemap.root() ) ).isEqualTo( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                                            "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">" +
                                                            "<sitemap><loc>https://example.com/site/project/master/mysite/sitemap-1.xml</loc>" +
                                                            "<lastmod>2024-01-05</lastmod></sitemap>" +
                                                            "<sitemap><loc>https://example.com/site/project/master/mysite/sitemap-2.xml</loc>" +
                                                            "<lastmod>2024-01-03</lastmod></sitemap>" +
                                                            "<sitemap><loc>https://example.com/site/project/master/mysite/sitemap-3.xml</loc>" +
                                                            "<lastmod>2024-01-01</lastmod></sitemap>" + "</sitemapindex>" );
        assertThat( text( sitemap.file( 3 ).orElseThrow() ) ).contains( "<loc>https://example.com/site/project/master/mysite/item-5</loc>" )
            .doesNotContain( "item-4" );
        assertThat( sitemap.file( 0 ) ).isEmpty();
        assertThat( sitemap.file( 4 ) ).isEmpty();
    }

    @Test
    void emptySitemapIsEmptyUrlset()
    {
        final Sitemap sitemap = write( params( "https://example.com", 10 ), List.of() );

        assertThat( sitemap.fileCount() ).isEqualTo( 1 );
        assertThat( text( sitemap.root() ) ).endsWith( "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>" );
        assertThat( text( sitemap.json() ) ).isEqualTo( "[]" );
    }

    @Test
    void json()
    {
        final Sitemap sitemap = write( params( "https://example.com", 1 ),
                                       List.of( new SitemapEntry( "/a\"b", "base:folder", Instant.parse( "2024-02-02T09:01:33Z" ) ),
                                                new SitemapEntry( "/c", "base:folder", null ) ) );

        assertThat( text( sitemap.json() ) ).isEqualTo(
            "[{\"changefreq\":\"daily\",\"lastmod\":\"2024-02-02T09:01:33Z\",\"priority\":\"0.8\",\"loc\":\"https://example.com/a%22b\"}," +
                "{\"changefreq\":\"daily\",\"priority\":\"0.8\",\"loc\":\"https://example.com/c\"}]" );
    }

    @Test
    void encodesPathSegmentsLikePortalUrls()
    {
        final StringBuilder out = new StringBuilder();
        SitemapRenderer.encodePathSegment( out, "æøå ~-._!$&'()*+,;=:@?#/%" );

        assertThat( out ).hasToString( "%C3%A6%C3%B8%C3%A5%20~-._!$&'()*+,;=:@%3F%23%2F%25" );
    }

    @Test
    void splitsFilesAtByteLimit()
        throws IOException
    {
        final List<SitemapEntry> entries =
            IntStream.rangeClosed( 1, 5 )
                .mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", null ) )
                .collect( Collectors.toList() );
        final long urlBytes = SitemapRenderer.utf8Length(
            "<url><loc>https://example.com/item-1</loc><changefreq>daily</changefreq><priority>0.8</priority></url>" );
        final long overhead = write( params( "https://example.com", 10 ), List.of() ).root().size();
        final long maxBytes = overhead + 2 * urlBytes;

        final Sitemap sitemap = Sitemap.write( temp.resolve( "split" ), params( "https://example.com", 10 ), entries, maxBytes, 10 );

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
        final Sitemap sitemap = write( params( "https://example.com", 10 ), List.of( new SitemapEntry( "/ok", "base:folder", null ),
                                                                                     new SitemapEntry( "/" + longName, "base:folder",
                                                                                                       null ) ) );

        assertThat( sitemap.size() ).isEqualTo( 1 );
        assertThat( text( sitemap.root() ) ).contains( "https://example.com/ok" ).doesNotContain( longName );
        assertThat( text( sitemap.json() ) ).doesNotContain( longName );
    }

    @Test
    void indexListsAtMostMaxFiles()
    {
        final List<SitemapEntry> entries =
            IntStream.range( 0, 7 )
                .mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", null ) )
                .collect( Collectors.toList() );

        final Sitemap sitemap =
            Sitemap.write( temp.resolve( "capped" ), params( "https://example.com", 2 ), entries, Sitemap.MAX_FILE_BYTES, 3 );

        assertThat( sitemap.fileCount() ).isEqualTo( 3 );
        assertThat( sitemap.size() ).isEqualTo( 6 );
        assertThat( sitemap.file( 4 ) ).isEmpty();
        assertThat( text( sitemap.json() ) ).doesNotContain( "item-6" );
    }

    @Test
    void deleteRemovesFiles()
    {
        final Sitemap sitemap = write( params( "https://example.com", 1 ), List.of( new SitemapEntry( "/a", "base:folder", null ),
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

        assertThatThrownBy( () -> Sitemap.write( blocked.resolve( "sitemap" ), params( "https://example.com", 1 ), List.of() ) )
            .isInstanceOf( UncheckedIOException.class );
        assertThat( Files.exists( blocked.resolve( "sitemap" ) ) ).isFalse();
    }

    @Test
    void gzipsRootAndFiles()
        throws IOException
    {
        final List<SitemapEntry> entries = IntStream.rangeClosed( 1, 3 )
            .mapToObj( i -> new SitemapEntry( "/item-" + i, "base:folder", Instant.parse( "2024-01-0" + i + "T10:00:00Z" ) ) )
            .collect( Collectors.toList() );
        final Sitemap sitemap = write( params( "https://example.com", 2 ), entries );

        assertThat( gunzip( sitemap.rootGzip() ) ).isEqualTo( text( sitemap.root() ).replace( ".xml<", ".xml.gz<" ) )
            .contains( "<loc>https://example.com/sitemap-1.xml.gz</loc>" );
        assertThat( gunzip( sitemap.fileGzip( 2 ).orElseThrow() ) ).isEqualTo( text( sitemap.file( 2 ).orElseThrow() ) );
        assertThat( sitemap.fileGzip( 3 ) ).isEmpty();
        assertThat( sitemap.fileGzip( 0 ) ).isEmpty();
    }

    @Test
    void gzipsSingleFileSitemap()
        throws IOException
    {
        final Sitemap sitemap = write( params( "https://example.com", 10 ), List.of( new SitemapEntry( "/a", "base:folder", null ) ) );

        assertThat( gunzip( sitemap.rootGzip() ) ).isEqualTo( text( sitemap.root() ) );
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
    void utf8Length()
    {
        assertThat( SitemapRenderer.utf8Length( "aæ€😀" ) ).isEqualTo( "aæ€😀".getBytes( StandardCharsets.UTF_8 ).length );
    }

    private Sitemap write( final SitemapParams params, final List<SitemapEntry> entries )
    {
        return Sitemap.write( temp.resolve( "sitemap-" + System.nanoTime() ), params, entries );
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

    private static SitemapParams params( final String siteUrl, final int urlsPerFile )
    {
        return new SitemapParams( "/mysite", siteUrl, CONTENT_TYPES, List.of(), 100, urlsPerFile );
    }
}

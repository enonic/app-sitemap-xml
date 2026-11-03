package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.io.ByteSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConvertedSitemapTest
{
    private static final List<ContentTypeSetting> CONTENT_TYPES =
        List.of( new ContentTypeSetting( "base:folder", "daily", "0.8" ), new ContentTypeSetting( "other:type", "", "" ) );

    private static final String SITE_URL = "https://www.example.com/mysite";

    @TempDir
    private Path temp;

    @Test
    void singleFileIsTheSameAsTheSitemapGeneratedForTheHost()
        throws IOException
    {
        final List<SitemapEntry> entries = entries( 3 );
        final Sitemap xml = write( "xml", xml(), entries, 10 );
        final ConvertedSitemap converted = convert( entries, 10, SITE_URL );

        assertThat( SitemapTest.text( converted.root() ) ).isEqualTo( SitemapTest.text( xml.root() ) );
        assertThat( gunzip( converted.rootEncoded() ) ).isEqualTo( SitemapTest.text( xml.root() ) );
        assertThat( gunzip( converted.rootGzip() ) ).isEqualTo( SitemapTest.text( xml.root() ) );
        assertThat( converted.file( 1 ) ).isEmpty();
        assertThat( converted.fileETag( 1, false ) ).isEmpty();
    }

    @Test
    void indexAndFilesAreTheSameAsTheSitemapGeneratedForTheHost()
        throws IOException
    {
        final List<SitemapEntry> entries = entries( 5 );
        final Sitemap xml = write( "xml", xml(), entries, 2 );
        final ConvertedSitemap converted = convert( entries, 2, SITE_URL );

        assertThat( SitemapTest.text( converted.root() ) ).isEqualTo( SitemapTest.text( xml.root() ) );
        assertThat( gunzip( converted.rootGzip() ) ).isEqualTo( gunzip( xml.rootGzip() ) );
        for ( int number = 1; number <= 3; number++ )
        {
            assertThat( SitemapTest.text( converted.file( number ).orElseThrow() ) ).isEqualTo(
                SitemapTest.text( xml.file( number ).orElseThrow() ) );
            assertThat( gunzip( converted.fileGzip( number ).orElseThrow() ) ).isEqualTo(
                SitemapTest.text( xml.file( number ).orElseThrow() ) );
        }
        assertThat( converted.file( 4 ) ).isEmpty();
        assertThat( converted.file( 0 ) ).isEmpty();
    }

    @Test
    void emptySitemapIsAnEmptyUrlset()
    {
        final Sitemap xml = write( "xml", xml(), List.of(), 10 );

        assertThat( SitemapTest.text( convert( List.of(), 10, SITE_URL ).root() ) ).isEqualTo( SitemapTest.text( xml.root() ) );
    }

    @Test
    void entityTagsFollowTheContentAndTheSiteUrl()
    {
        final List<SitemapEntry> entries = entries( 5 );
        final ConvertedSitemap converted = convert( entries, 2, SITE_URL );
        final ConvertedSitemap again = convert( entries, 2, SITE_URL );

        assertThat( converted.rootETag( false ) ).matches( "\"[0-9a-f]{32}\"" ).isEqualTo( again.rootETag( false ) );
        assertThat( converted.rootETag( true ) ).isEqualTo(
            converted.rootETag( false ).substring( 0, converted.rootETag( false ).length() - 1 ) + "-gzip\"" );
        assertThat( converted.rootGzipETag() ).matches( "\"[0-9a-f]{32}-gzip\"" ).isNotEqualTo( converted.rootETag( true ) );
        assertThat( converted.fileETag( 1, false ) ).isNotEqualTo( converted.fileETag( 2, false ) )
            .isEqualTo( again.fileETag( 1, false ) );

        final ConvertedSitemap otherHost = convert( entries, 2, "https://other.example.com/mysite" );
        assertThat( otherHost.rootETag( false ) ).isNotEqualTo( converted.rootETag( false ) );
        assertThat( otherHost.fileETag( 1, false ) ).isNotEqualTo( converted.fileETag( 1, false ) );

        final List<SitemapEntry> changed = List.of( entries.get( 0 ), entries.get( 1 ),
                                                    new SitemapEntry( "/changed", "base:folder", null ), entries.get( 3 ),
                                                    entries.get( 4 ) );
        final ConvertedSitemap edited = convert( changed, 2, SITE_URL );
        assertThat( edited.fileETag( 1, false ) ).isEqualTo( converted.fileETag( 1, false ) );
        assertThat( edited.fileETag( 2, false ) ).isNotEqualTo( converted.fileETag( 2, false ) );
        assertThat( edited.rootETag( false ) ).isNotEqualTo( converted.rootETag( false ) );
    }

    @Test
    void siteUrlLongerThanTheHeadlessSitemapAllowsIsRejected()
    {
        final String longest = "https://" + "a".repeat( Sitemap.MAX_BASE_URL_LENGTH - "https://".length() );

        assertThat( convert( entries( 1 ), 10, longest + "/" ).root() ).isNotNull();
        assertThatThrownBy( () -> convert( entries( 1 ), 10, longest + "a" ) ).isInstanceOf( InvalidSiteUrlException.class );

        // as many characters, but more bytes in the XML: escaped, or not ASCII
        final String start = "https://example.com/";
        final String fits = start + "a".repeat( Sitemap.MAX_BASE_URL_LENGTH - start.length() );
        final String escaped = start + "&" + "a".repeat( Sitemap.MAX_BASE_URL_LENGTH - start.length() - 1 );
        final String nonAscii = start + "æ" + "a".repeat( Sitemap.MAX_BASE_URL_LENGTH - start.length() - 1 );
        assertThat( convert( entries( 1 ), 10, fits ).root() ).isNotNull();
        assertThatThrownBy( () -> convert( entries( 1 ), 10, escaped ) ).isInstanceOf( InvalidSiteUrlException.class );
        assertThatThrownBy( () -> convert( entries( 1 ), 10, nonAscii ) ).isInstanceOf( InvalidSiteUrlException.class );
    }

    private List<SitemapEntry> entries( final int count )
    {
        return IntStream.range( 0, count )
            .mapToObj( i -> i == 0
                ? new SitemapEntry( "", "base:folder", Instant.parse( "2024-01-03T10:00:00.5Z" ) )
                : new SitemapEntry( "/item-" + i + "/æ ø", i % 2 == 0 ? "other:type" : "base:folder",
                                    i % 3 == 0 ? null : Instant.parse( "2024-01-0" + i + "T10:00:00Z" ) ) )
            .toList();
    }

    private ConvertedSitemap convert( final List<SitemapEntry> entries, final int urlsPerFile, final String siteUrl )
    {
        final SitemapParams headless = new SitemapParams( "/mysite", "", CONTENT_TYPES, List.of(), SitemapParams.Format.HEADLESS );
        return new ConvertedSitemap( write( "headless", headless, entries, urlsPerFile ), xml().withSiteUrl( siteUrl ) );
    }

    private static SitemapParams xml()
    {
        return new SitemapParams( "/mysite", SITE_URL, CONTENT_TYPES, List.of() );
    }

    private Sitemap write( final String name, final SitemapParams params, final List<SitemapEntry> entries, final int urlsPerFile )
    {
        return SitemapTest.write( temp.resolve( name + System.nanoTime() ), params, entries, urlsPerFile, Sitemap.MAX_FILE_BYTES,
                                  Sitemap.MAX_FILES );
    }

    private static String gunzip( final ByteSource gzip )
        throws IOException
    {
        try (var in = new java.util.zip.GZIPInputStream( gzip.openStream() ))
        {
            return new String( in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8 );
        }
    }
}

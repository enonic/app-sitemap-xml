package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.io.ByteSource;

import com.enonic.xp.data.PropertyTree;

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
    void plainFilesHaveTheirSizeKnownWithoutWritingThem()
        throws IOException
    {
        final List<SitemapEntry> entries = new ArrayList<>( entries( 7 ) );
        // a second entry of the site itself, as in a project, and one at the end of a file
        entries.add( new SitemapEntry( "", "other:type", null ) );
        for ( final String siteUrl : List.of( SITE_URL, "https://www.example.com/", "https://www.example.com", "http://localhost:8080/a&b",
                                              "https://xn--bl-0ia.example.com/blåbær/'x'", "https://www.example.com/a%20b" ) )
        {
            for ( final int urlsPerFile : new int[]{1, 3, 100} )
            {
                final ConvertedSitemap converted = convert( entries, urlsPerFile, siteUrl );
                assertSizeKnown( converted.root() );
                for ( int number = 1; converted.file( number ).isPresent(); number++ )
                {
                    assertSizeKnown( converted.file( number ).orElseThrow() );
                }
            }
        }
        assertSizeKnown( convert( List.of(), 10, SITE_URL ).root() );

        // stored with the sitemap
        final Sitemap headless = write( "stored", headless(), entries, 100 );
        final PropertyTree data = new PropertyTree();
        SitemapNodeData.writeSitemap( data.getRoot(), headless );
        final Sitemap stored =
            Objects.requireNonNull( SitemapNodeData.readSitemap( data.getRoot(), Sitemap.FileSource.directory( headless.directory() ) ) );
        assertSizeKnown( new ConvertedSitemap( stored, xml() ).root() );

        // a headless sitemap stored before has no known size, but is served
        final ObjectNode description = headless.store();
        description.withArray( "files" ).forEach( file -> ( (ObjectNode) file ).remove( List.of( "xmlBytes", "xmlUrls", "xmlRoots" ) ) );
        final ConvertedSitemap old =
            new ConvertedSitemap( Sitemap.restore( Sitemap.FileSource.directory( headless.directory() ), description ), xml() );
        assertThat( old.root().sizeIfKnown().isPresent() ).isFalse();
        assertThat( SitemapTest.text( old.root() ) ).isEqualTo( SitemapTest.text( convert( entries, 100, SITE_URL ).root() ) );
    }

    private static void assertSizeKnown( final ByteSource plain )
        throws IOException
    {
        assertThat( plain.sizeIfKnown().isPresent() ).isTrue();
        assertThat( plain.sizeIfKnown().get() ).isEqualTo( plain.read().length );
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
        return new ConvertedSitemap( write( "headless", headless(), entries, urlsPerFile ), xml().withSiteUrl( siteUrl ) );
    }

    private static SitemapParams headless()
    {
        return new SitemapParams( "/mysite", "", CONTENT_TYPES, List.of(), SitemapParams.Format.HEADLESS );
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

package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapTest
{
    private static final List<ContentTypeSetting> CONTENT_TYPES = List.of( new ContentTypeSetting( "base:folder", "daily", "0.8" ) );

    @Test
    void singleFileIsUrlset()
    {
        final Sitemap sitemap = new Sitemap( params( "https://example.com/", 10 ),
                                             List.of( new SitemapEntry( "", "portal:site", Instant.parse( "2024-02-02T09:01:33.169Z" ) ),
                                                      new SitemapEntry( "/news/a b&c", "base:folder", null ) ) );

        assertThat( sitemap.isIndex() ).isFalse();
        assertThat( sitemap.root() ).isEqualTo( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
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
        final Sitemap sitemap = new Sitemap( params( "https://example.com/site/project/master/mysite", 2 ), entries );

        assertThat( sitemap.fileCount() ).isEqualTo( 3 );
        assertThat( sitemap.root() ).isEqualTo( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                                    "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">" +
                                                    "<sitemap><loc>https://example.com/site/project/master/mysite/sitemap-1.xml</loc>" +
                                                    "<lastmod>2024-01-05</lastmod></sitemap>" +
                                                    "<sitemap><loc>https://example.com/site/project/master/mysite/sitemap-2.xml</loc>" +
                                                    "<lastmod>2024-01-03</lastmod></sitemap>" +
                                                    "<sitemap><loc>https://example.com/site/project/master/mysite/sitemap-3.xml</loc>" +
                                                    "<lastmod>2024-01-01</lastmod></sitemap>" + "</sitemapindex>" );
        assertThat( sitemap.file( 3 ) ).hasValueSatisfying(
            xml -> assertThat( xml ).contains( "<loc>https://example.com/site/project/master/mysite/item-5</loc>" )
                .doesNotContain( "item-4" ) );
        assertThat( sitemap.file( 0 ) ).isEmpty();
        assertThat( sitemap.file( 4 ) ).isEmpty();
    }

    @Test
    void emptySitemapIsEmptyUrlset()
    {
        final Sitemap sitemap = new Sitemap( params( "https://example.com", 10 ), List.of() );

        assertThat( sitemap.fileCount() ).isEqualTo( 1 );
        assertThat( sitemap.root() ).endsWith( "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>" );
    }

    @Test
    void json()
    {
        final Sitemap sitemap = new Sitemap( params( "https://example.com", 10 ),
                                             List.of( new SitemapEntry( "/a\"b", "base:folder", Instant.parse( "2024-02-02T09:01:33Z" ) ) ) );

        assertThat( sitemap.json() ).isEqualTo(
            "[{\"changefreq\":\"daily\",\"lastmod\":\"2024-02-02T09:01:33Z\",\"priority\":\"0.8\",\"loc\":\"https://example.com/a%22b\"}]" );
    }

    @Test
    void encodesPathSegmentsLikePortalUrls()
    {
        final StringBuilder out = new StringBuilder();
        Sitemap.encodePathSegment( out, "æøå ~-._!$&'()*+,;=:@?#/%" );

        assertThat( out ).hasToString( "%C3%A6%C3%B8%C3%A5%20~-._!$&'()*+,;=:@%3F%23%2F%25" );
    }

    private static SitemapParams params( final String siteUrl, final int urlsPerFile )
    {
        return new SitemapParams( "/mysite", siteUrl, CONTENT_TYPES, List.of(), 100, urlsPerFile );
    }
}

package com.enonic.app.sitemapxml;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SitemapParamsTest
{
    @Test
    void takesRequestSiteUrlOfHttpAndHttpsOnly()
    {
        assertThat( site( "https://WWW.Example.COM:8443/My%20Site?Q=A#F" ).forRequestSiteUrl().siteUrl() ).isEqualTo(
            "https://www.example.com:8443/My%20Site" );
        assertThat( site( "http://[ABCD::1]:8080/A#F" ).forRequestSiteUrl().siteUrl() ).isEqualTo( "http://[abcd::1]:8080/A" );
        final SitemapParams plain = site( "https://example.com" );
        assertThat( plain.forRequestSiteUrl() ).isSameAs( plain );
        for ( final String url : List.of( "ftp://example.com", "HTTPS://example.com", "Http://example.com", "https://user@example.com",
                                          "https://user:pass@example.com/site", "https:///site", "/relative", "example.com" ) )
        {
            assertThatThrownBy( () -> site( url ).forRequestSiteUrl() ).as( url ).isInstanceOf( InvalidSiteUrlException.class );
        }
    }

    @Test
    void detectsExplicitPort()
    {
        assertThat( site( "https://example.com:8443/site" ).hasExplicitPort() ).isTrue();
        assertThat( site( "http://[::1]:8080" ).hasExplicitPort() ).isTrue();
        assertThat( site( "https://example.com/a:b" ).hasExplicitPort() ).isFalse();
        assertThat( site( "http://[::1]/site" ).hasExplicitPort() ).isFalse();
        assertThat( site( "https://user:pass@example.com/site" ).hasExplicitPort() ).isFalse();
        assertThat( site( "/site" ).hasExplicitPort() ).isFalse();
    }

    @Test
    void addsSiteContentTypeAndDefaults()
    {
        final SitemapParams params = new SitemapParams( "/mysite", "https://example.com",
                                                        List.of( new ContentTypeSetting( "base:folder", "", "" ),
                                                                 new ContentTypeSetting( "", "daily", "0.1" ) ), List.of( "", "/x" ), 50_000 );

        assertThat( params.contentTypes() ).containsExactly( new ContentTypeSetting( "base:folder", "monthly", "0.5" ),
                                                             new ContentTypeSetting( "portal:site", "hourly", "1.0" ) );
        assertThat( params.ignorePatterns() ).containsExactly( "/x" );
    }

    @Test
    void keepsConfiguredSiteContentTypeAndLastDuplicate()
    {
        final SitemapParams params = new SitemapParams( "/mysite", "https://example.com",
                                                        List.of( new ContentTypeSetting( "portal:site", "weekly", "0.9" ),
                                                                 new ContentTypeSetting( "base:folder", "daily", "0.1" ),
                                                                 new ContentTypeSetting( "base:folder", "yearly", "0.2" ) ), List.of(), 50_000 );

        assertThat( params.contentTypes() ).containsExactly( new ContentTypeSetting( "portal:site", "weekly", "0.9" ),
                                                             new ContentTypeSetting( "base:folder", "yearly", "0.2" ) );
    }

    @Test
    void rejectsInvalidMaxItems()
    {
        assertThatThrownBy( () -> new SitemapParams( "/s", "u", List.of(), List.of(), 0 ) ).isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    void fromScriptMap()
    {
        final SitemapParams params = SitemapBean.toParams(
            Map.of( "sitePath", "/mysite", "siteUrl", "https://example.com", "contentTypes",
                    List.of( Map.of( "contentType", "base:folder", "changefreq", "daily", "priority", "0.3" ) ), "ignorePatterns",
                    List.of( "*/foo" ), "maxItems", 20.0 ) );

        assertThat( params ).isEqualTo( new SitemapParams( "/mysite", "https://example.com",
                                                           List.of( new ContentTypeSetting( "base:folder", "daily", "0.3" ) ),
                                                           List.of( "*/foo" ), 20 ) );
    }

    private static SitemapParams site( final String siteUrl )
    {
        return new SitemapParams( "/s", siteUrl, List.of(), List.of(), 10 );
    }
}

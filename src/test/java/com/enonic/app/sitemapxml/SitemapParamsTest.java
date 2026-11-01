package com.enonic.app.sitemapxml;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapParamsTest
{
    @Test
    void lowerCasesHostOnly()
    {
        assertThat( new SitemapParams( "/s", "https://WWW.Example.COM:8443/My%20Site?Q=A#F", List.of(), List.of() ).withLowerCaseHost().siteUrl() ).isEqualTo(
            "https://www.example.com:8443/My%20Site?Q=A#F" );
        assertThat( new SitemapParams( "/s", "https://User:Pass@Example.COM", List.of(), List.of() ).withLowerCaseHost().siteUrl() ).isEqualTo(
            "https://User:Pass@example.com" );
        assertThat( new SitemapParams( "/s", "http://[ABCD::1]:8080/A", List.of(), List.of() ).withLowerCaseHost().siteUrl() ).isEqualTo( "http://[abcd::1]:8080/A" );
        assertThat( new SitemapParams( "/s", "/Relative/Path", List.of(), List.of() ).withLowerCaseHost().siteUrl() ).isEqualTo( "/Relative/Path" );
    }

    @Test
    void detectsExplicitPort()
    {
        assertThat( new SitemapParams( "/s", "https://example.com:8443/site", List.of(), List.of() ).hasExplicitPort() ).isTrue();
        assertThat( new SitemapParams( "/s", "http://[::1]:8080", List.of(), List.of() ).hasExplicitPort() ).isTrue();
        assertThat( new SitemapParams( "/s", "https://example.com/a:b", List.of(), List.of() ).hasExplicitPort() ).isFalse();
        assertThat( new SitemapParams( "/s", "http://[::1]/site", List.of(), List.of() ).hasExplicitPort() ).isFalse();
        assertThat( new SitemapParams( "/s", "https://user:pass@example.com/site", List.of(), List.of() ).hasExplicitPort() ).isFalse();
        assertThat( new SitemapParams( "/s", "/site", List.of(), List.of() ).hasExplicitPort() ).isFalse();
    }

    @Test
    void addsSiteContentTypeAndDefaults()
    {
        final SitemapParams params = new SitemapParams( "/mysite", "https://example.com",
                                                        List.of( new ContentTypeSetting( "base:folder", "", "" ),
                                                                 new ContentTypeSetting( "", "daily", "0.1" ) ), List.of( "", "/x" ) );

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
                                                                 new ContentTypeSetting( "base:folder", "yearly", "0.2" ) ), List.of() );

        assertThat( params.contentTypes() ).containsExactly( new ContentTypeSetting( "portal:site", "weekly", "0.9" ),
                                                             new ContentTypeSetting( "base:folder", "yearly", "0.2" ) );
    }

    @Test
    void fromScriptMap()
    {
        final SitemapParams params = SitemapBean.toParams(
            Map.of( "sitePath", "/mysite", "siteUrl", "https://example.com", "contentTypes",
                    List.of( Map.of( "contentType", "base:folder", "changefreq", "daily", "priority", "0.3" ) ), "ignorePatterns",
                    List.of( "*/foo" ) ) );

        assertThat( params ).isEqualTo( new SitemapParams( "/mysite", "https://example.com",
                                                           List.of( new ContentTypeSetting( "base:folder", "daily", "0.3" ) ),
                                                           List.of( "*/foo" ) ) );
    }
}

package com.enonic.app.sitemapxml;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SitemapParamsTest
{
    @Test
    void lowerCasesHostOnly()
    {
        assertThat( new SitemapParams( "/s", "https://WWW.Example.COM:8443/My%20Site?Q=A#F", List.of(), List.of(), 10 ).withLowerCaseHost().siteUrl() ).isEqualTo(
            "https://www.example.com:8443/My%20Site?Q=A#F" );
        assertThat( new SitemapParams( "/s", "https://User:Pass@Example.COM", List.of(), List.of(), 10 ).withLowerCaseHost().siteUrl() ).isEqualTo(
            "https://User:Pass@example.com" );
        assertThat( new SitemapParams( "/s", "http://[ABCD::1]:8080/A", List.of(), List.of(), 10 ).withLowerCaseHost().siteUrl() ).isEqualTo( "http://[abcd::1]:8080/A" );
        assertThat( new SitemapParams( "/s", "/Relative/Path", List.of(), List.of(), 10 ).withLowerCaseHost().siteUrl() ).isEqualTo( "/Relative/Path" );
    }

    @Test
    void detectsExplicitPort()
    {
        assertThat( new SitemapParams( "/s", "https://example.com:8443/site", List.of(), List.of(), 10 ).hasExplicitPort() ).isTrue();
        assertThat( new SitemapParams( "/s", "http://[::1]:8080", List.of(), List.of(), 10 ).hasExplicitPort() ).isTrue();
        assertThat( new SitemapParams( "/s", "https://example.com/a:b", List.of(), List.of(), 10 ).hasExplicitPort() ).isFalse();
        assertThat( new SitemapParams( "/s", "http://[::1]/site", List.of(), List.of(), 10 ).hasExplicitPort() ).isFalse();
        assertThat( new SitemapParams( "/s", "https://user:pass@example.com/site", List.of(), List.of(), 10 ).hasExplicitPort() ).isFalse();
        assertThat( new SitemapParams( "/s", "/site", List.of(), List.of(), 10 ).hasExplicitPort() ).isFalse();
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
}

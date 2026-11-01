package com.enonic.app.sitemapxml;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapParamsTest
{
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

package com.enonic.app.sitemapxml;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.enonic.xp.app.ApplicationKey;
import com.enonic.xp.branch.Branch;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.portal.url.PortalScope;
import com.enonic.xp.portal.url.PortalUrlService;
import com.enonic.xp.project.ProjectName;
import com.enonic.xp.site.SiteConfig;
import com.enonic.xp.site.SiteConfigs;
import com.enonic.xp.web.vhost.VirtualHost;
import com.enonic.xp.web.vhost.VirtualHostService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SiteUrlsTest
{
    @Mock
    private PortalUrlService portalUrlService;

    @Mock
    private VirtualHostService virtualHostService;

    @Test
    void guillotineSitemapIsCurrentWhileItsOverrideIs()
    {
        scope( null, null );
        assertThat( current( "" ) ).isTrue();
        assertThat( current( "https://www.example.com" ) ).isFalse();

        scope( "https://www.example.com", null );
        assertThat( current( "https://www.example.com" ) ).isTrue();
        assertThat( current( "" ) ).isFalse();
    }

    @Test
    void sitemapWithOverrideIsCurrentWhileItsUrlStartsWithIt()
    {
        scope( "https://www.example.com", "https://base.example.com/" );

        assertThat( current( "https://www.example.com/site/project/master/mysite" ) ).isTrue();
        assertThat( current( "https://old.example.com/site/project/master/mysite" ) ).isFalse();
        assertThat( current( "https://base.example.com" ) ).isFalse();
    }

    @Test
    void overrideMatchesOnlyUpToWhereThePathStarts()
    {
        scope( "https://www.example.co", null );
        assertThat( current( "https://www.example.com/mysite" ) ).isFalse();
        assertThat( current( "https://www.example.co:8443/mysite" ) ).isFalse();
        assertThat( current( "https://www.example.co/mysite" ) ).isTrue();
        assertThat( current( "https://www.example.co/" ) ).isTrue();

        scope( "https://www.example.com/", null );
        assertThat( current( "https://www.example.com//mysite" ) ).isTrue();
    }

    @Test
    void sitemapWithBaseUrlIsCurrentWhileItIsTheBaseUrl()
    {
        scope( null, "https://base.example.com/" );
        virtualHosts( "www.example.com" );

        assertThat( current( "https://base.example.com" ) ).isTrue();
        assertThat( current( "https://old.example.com/" ) ).isFalse();
        assertThat( current( "https://www.example.com/mysite" ) ).isFalse();
    }

    @Test
    void sitemapOfVirtualHostIsCurrentWhileTheHostIsConfiguredExactly()
    {
        scope( null, null );
        virtualHosts( "www.example.com", "~.*\\.example\\.org" );

        assertThat( current( "https://www.example.com/mysite" ) ).isTrue();
        assertThat( current( "https://old.example.com/mysite" ) ).isFalse();
        assertThat( current( "https://a.example.org/mysite" ) ).isFalse();
    }

    @Test
    void sitemapIsCurrentWhenTheConfigurationCannotBeRead()
    {
        when( portalUrlService.portalScope( any() ) ).thenThrow( new IllegalStateException( "boom" ) );

        assertThat( current( "https://old.example.com/mysite" ) ).isTrue();
    }

    private boolean current( final String siteUrl )
    {
        return new SiteUrls( portalUrlService, virtualHostService ).isCurrent(
            new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone", "user:system:anonymous" ),
                                  new SitemapParams( "/mysite", siteUrl, List.of(), List.of() ) ) );
    }

    private void scope( final @Nullable String override, final @Nullable String baseUrl )
    {
        final List<SiteConfig> configs = new ArrayList<>();
        if ( override != null )
        {
            final PropertyTree config = new PropertyTree();
            config.setString( "overrideDomain", override );
            configs.add( SiteConfig.create().application( SitemapCache.APPLICATION ).config( config ).build() );
        }
        if ( baseUrl != null )
        {
            final PropertyTree config = new PropertyTree();
            config.setString( "baseUrl", baseUrl );
            configs.add( SiteConfig.create().application( ApplicationKey.PORTAL ).config( config ).build() );
        }
        when( portalUrlService.portalScope( any() ) ).thenReturn( PortalScope.create()
                                                                     .projectName( ProjectName.from( "project" ) )
                                                                     .branch( Branch.from( "master" ) )
                                                                     .path( ContentPath.from( "/mysite" ) )
                                                                     .sitePath( ContentPath.from( "/mysite" ) )
                                                                     .siteConfigs( SiteConfigs.from( configs ) )
                                                                     .build() );
    }

    private void virtualHosts( final String... hosts )
    {
        final List<VirtualHost> virtualHosts = new ArrayList<>();
        for ( final String host : hosts )
        {
            final VirtualHost virtualHost = mock( VirtualHost.class );
            when( virtualHost.getHost() ).thenReturn( host );
            virtualHosts.add( virtualHost );
        }
        when( virtualHostService.isEnabled() ).thenReturn( true );
        when( virtualHostService.getVirtualHosts() ).thenReturn( virtualHosts );
    }
}

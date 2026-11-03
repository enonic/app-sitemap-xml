package com.enonic.app.sitemapxml;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.project.Project;
import com.enonic.xp.project.ProjectName;
import com.enonic.xp.project.ProjectService;
import com.enonic.xp.site.Site;
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
    private ContentService contentService;

    @Mock
    private ProjectService projectService;

    @Mock
    private VirtualHostService virtualHostService;

    @Test
    void headlessSitemapIsCurrentWhileTheAppIsConfiguredAsItHasNoSiteUrl()
    {
        final SitemapCache.Key headless = key( "/mysite", "", SitemapParams.Format.HEADLESS );
        site( true, "https://www.example.com" );
        assertThat( siteUrls().isCurrent( headless ) ).isTrue();

        site( false, null );
        assertThat( siteUrls().isCurrent( headless ) ).isFalse();
    }

    @Test
    void sitemapIsNotCurrentOnceTheAppIsRemovedFromTheSiteOrTheSiteIsUnpublished()
    {
        site( false, null );
        assertThat( current( "https://www.example.com" ) ).isFalse();

        when( contentService.contentExists( ContentPath.from( "/mysite" ) ) ).thenReturn( false );
        assertThat( current( "https://www.example.com" ) ).isFalse();

        // a content that is not a site
        when( contentService.contentExists( ContentPath.from( "/mysite" ) ) ).thenReturn( true );
        when( contentService.getByPath( ContentPath.from( "/mysite" ) ) ).thenReturn( mock( Content.class ) );
        assertThat( current( "https://www.example.com" ) ).isFalse();
    }

    @Test
    void sitemapWithOverrideIsCurrentWhileItsUrlStartsWithIt()
    {
        site( true, "https://www.example.com" );

        assertThat( current( "https://www.example.com/site/project/master/mysite" ) ).isTrue();
        assertThat( current( "https://old.example.com/site/project/master/mysite" ) ).isFalse();
    }

    @Test
    void overrideMatchesOnlyUpToWhereThePathStarts()
    {
        site( true, "https://www.example.co" );
        assertThat( current( "https://www.example.com/mysite" ) ).isFalse();
        assertThat( current( "https://www.example.co:8443/mysite" ) ).isFalse();
        assertThat( current( "https://www.example.co/mysite" ) ).isTrue();
        assertThat( current( "https://www.example.co/" ) ).isTrue();

        site( true, "https://www.example.com/" );
        assertThat( current( "https://www.example.com//mysite" ) ).isTrue();
    }

    @Test
    void sitemapOfVirtualHostIsCurrentWhileTheHostIsConfiguredExactly()
    {
        site( true, null );
        virtualHosts( "www.example.com", "~.*\\.example\\.org" );

        assertThat( current( "https://www.example.com/mysite" ) ).isTrue();
        assertThat( current( "https://www.example.com:8443/mysite" ) ).isFalse();
        assertThat( current( "https://old.example.com/mysite" ) ).isFalse();
        assertThat( current( "https://a.example.org/mysite" ) ).isFalse();
    }

    @Test
    void urlOfTheServedVirtualHostCountsOnlyWhenItsHostIsExact()
    {
        virtualHosts( "www.example.com", "~.*\\.example\\.org" );
        final VirtualHost exact = virtualHostService.getVirtualHosts().get( 0 );
        final VirtualHost pattern = virtualHostService.getVirtualHosts().get( 1 );

        assertThat( siteUrls().isVirtualHostUrl( params( "https://www.example.com/mysite" ), exact ) ).isTrue();
        assertThat( siteUrls().isVirtualHostUrl( params( "https://WWW.example.com/mysite" ), exact ) ).isTrue();
        assertThat( siteUrls().isVirtualHostUrl( params( "https://www.example.com:8443/mysite" ), exact ) ).isFalse();
        assertThat( siteUrls().isVirtualHostUrl( params( "https://a.example.org/mysite" ), pattern ) ).isFalse();
        assertThat( siteUrls().isVirtualHostUrl( params( "https://www.example.com/mysite" ), null ) ).isFalse();
    }

    @Test
    void projectSitemapIsCurrentWhileTheAppIsConfiguredForTheProject()
    {
        final SitemapCache.Key headless = key( "", "", SitemapParams.Format.HEADLESS );
        when( projectService.get( ProjectName.from( "project" ) ) ).thenReturn(
            Project.create().name( ProjectName.from( "project" ) ).addSiteConfig( config( null ) ).build() );
        assertThat( siteUrls().isCurrent( headless ) ).isTrue();

        when( projectService.get( ProjectName.from( "project" ) ) ).thenReturn(
            Project.create().name( ProjectName.from( "project" ) ).build() );
        assertThat( siteUrls().isCurrent( headless ) ).isFalse();
    }

    @Test
    void sitemapIsCurrentWhenTheConfigurationCannotBeRead()
    {
        when( contentService.contentExists( any( ContentPath.class ) ) ).thenThrow( new IllegalStateException( "boom" ) );

        assertThat( current( "https://old.example.com/mysite" ) ).isTrue();
    }

    private SiteUrls siteUrls()
    {
        return new SiteUrls( contentService, projectService, virtualHostService );
    }

    private boolean current( final String siteUrl )
    {
        return siteUrls().isCurrent( key( "/mysite", siteUrl, SitemapParams.Format.XML ) );
    }

    private static SitemapCache.Key key( final String sitePath, final String siteUrl, final SitemapParams.Format format )
    {
        return new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone", "user:system:anonymous" ),
                                     new SitemapParams( sitePath, siteUrl, List.of(), List.of(), 10, format ) );
    }

    private static SitemapParams params( final String siteUrl )
    {
        return new SitemapParams( "/mysite", siteUrl, List.of(), List.of(), 10 );
    }

    private void site( final boolean app, final String override )
    {
        final Site site = mock( Site.class );
        when( site.getSiteConfigs() ).thenReturn( app ? SiteConfigs.from( config( override ) ) : SiteConfigs.empty() );
        when( contentService.contentExists( ContentPath.from( "/mysite" ) ) ).thenReturn( true );
        when( contentService.getByPath( ContentPath.from( "/mysite" ) ) ).thenReturn( site );
    }

    private static SiteConfig config( final String override )
    {
        final PropertyTree config = new PropertyTree();
        if ( override != null )
        {
            config.setString( "overrideDomain", override );
        }
        return SiteConfig.create().application( SitemapCache.APPLICATION ).config( config ).build();
    }

    private void virtualHosts( final String... hosts )
    {
        final List<VirtualHost> virtualHosts = new ArrayList<>();
        int i = 0;
        for ( final String host : hosts )
        {
            final VirtualHost virtualHost = mock( VirtualHost.class );
            when( virtualHost.getHost() ).thenReturn( host );
            when( virtualHost.getName() ).thenReturn( "vhost" + i++ );
            virtualHosts.add( virtualHost );
        }
        when( virtualHostService.isEnabled() ).thenReturn( true );
        when( virtualHostService.getVirtualHosts() ).thenReturn( virtualHosts );
    }
}

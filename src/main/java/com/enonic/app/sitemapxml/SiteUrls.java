package com.enonic.app.sitemapxml;

import java.util.Arrays;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.project.Project;
import com.enonic.xp.project.ProjectName;
import com.enonic.xp.project.ProjectService;
import com.enonic.xp.repository.RepositoryId;
import com.enonic.xp.security.RoleKeys;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.site.Site;
import com.enonic.xp.site.SiteConfig;
import com.enonic.xp.site.SiteConfigs;
import com.enonic.xp.web.vhost.VirtualHost;
import com.enonic.xp.web.vhost.VirtualHostHelper;
import com.enonic.xp.web.vhost.VirtualHostService;

/**
 * Where the site URL of a sitemap comes from: the app's "Base URL" override or the request, and whether a URL taken from the request is
 * that of a virtual host.
 */
final class SiteUrls
{
    private static final Logger LOG = LoggerFactory.getLogger( SiteUrls.class );

    private final ContentService contentService;

    private final ProjectService projectService;

    private final VirtualHostService virtualHostService;

    SiteUrls( final ContentService contentService, final ProjectService projectService, final VirtualHostService virtualHostService )
    {
        this.contentService = contentService;
        this.projectService = projectService;
        this.virtualHostService = virtualHostService;
    }

    /**
     * Whether the site URL taken from the request is that of the virtual host the request came through, and that virtual host matches
     * the host exactly (one of its hosts, not a {@code ~} pattern), without a port: such URLs come from the configuration, not from
     * whatever the client sent, so the sitemap can be generated again without a request.
     *
     * @param served the virtual host of the request, from {@link VirtualHostHelper}: its host is the request's host name for both an
     *               exact host and a pattern, so its configuration is looked up by name
     */
    boolean isVirtualHostUrl( final SitemapParams params, final VirtualHost served )
    {
        return served != null && isExactVirtualHostUrl( params, served.getName() );
    }

    /**
     * Whether the host of the site URL, without a port, is one of the exact hosts of the virtual host of the given name, or of any
     * virtual host when the name is null.
     */
    private boolean isExactVirtualHostUrl( final SitemapParams params, final String name )
    {
        final String host = params.hostWithoutPort();
        return host != null && virtualHostService.isEnabled() && virtualHostService.getVirtualHosts()
            .stream()
            .filter( virtualHost -> name == null || name.equals( virtualHost.getName() ) )
            .flatMap( virtualHost -> Arrays.stream( virtualHost.getHost().split( " " ) ) )
            .anyMatch( exact -> !exact.startsWith( "~" ) && exact.equalsIgnoreCase( host ) );
    }

    /**
     * Whether a request could still get the sitemap of the key as one generated again without a request: the app is still configured for
     * the site, or the project, and its site URL is the one the configuration gives now. A sitemap whose "Base URL" or virtual host
     * changed, or of a site the app was removed from, is not requested anymore. When the configuration can't be read, the sitemap counts
     * as current.
     */
    boolean isCurrent( final SitemapCache.Key key )
    {
        final Optional<SiteConfigs> siteConfigs = siteConfigs( RepositoryId.from( key.repositoryId() ), key.params().sitePath() );
        if ( siteConfigs.isEmpty() )
        {
            return true;
        }
        final SiteConfig siteConfig = siteConfigs.get().get( SitemapCache.APPLICATION );
        if ( siteConfig == null )
        {
            // the app was removed from the site, or the project
            return false;
        }
        if ( key.params().format() == SitemapParams.Format.HEADLESS )
        {
            // returns paths: no site URL in the key
            return true;
        }
        final String siteUrl = key.params().siteUrl();
        final String override = override( siteConfig );
        if ( !override.isEmpty() )
        {
            // the override followed by the site's path: it must end where a path starts, not in the middle of a host or a segment
            return siteUrl.startsWith( override ) &&
                ( override.endsWith( "/" ) || siteUrl.length() == override.length() || siteUrl.charAt( override.length() ) == '/' );
        }
        // no request here: any virtual host matching the host exactly
        return isExactVirtualHostUrl( key.params(), null );
    }

    /**
     * The app configurations of the published site at the given path, or of the project when the path is empty; empty when they can't
     * be read.
     */
    private Optional<SiteConfigs> siteConfigs( final RepositoryId repositoryId, final String sitePath )
    {
        try
        {
            return ContextBuilder.create()
                .repositoryId( repositoryId )
                .branch( ContentConstants.BRANCH_MASTER )
                .authInfo( AuthenticationInfo.copyOf( AuthenticationInfo.unAuthenticated() ).principals( RoleKeys.ADMIN ).build() )
                .build()
                .callWith( () -> {
                    if ( sitePath.isEmpty() )
                    {
                        final Project project = projectService.get( ProjectName.from( repositoryId ) );
                        return Optional.ofNullable( project ).map( p -> p.getSiteConfigs() == null ? SiteConfigs.empty() : p.getSiteConfigs() );
                    }
                    final Content content = contentService.contentExists( ContentPath.from( sitePath ) )
                        ? contentService.getByPath( ContentPath.from( sitePath ) )
                        : null;
                    // a site that is not published, or no longer a site, has no configuration of the app
                    return Optional.of( content instanceof Site site && site.getSiteConfigs() != null ? site.getSiteConfigs() : SiteConfigs.empty() );
                } );
        }
        catch ( RuntimeException e )
        {
            LOG.debug( "Could not read the configuration of {}", sitePath, e );
            return Optional.empty();
        }
    }

    private static String override( final SiteConfig siteConfig )
    {
        final String override = siteConfig.getConfig() == null ? null : siteConfig.getConfig().getString( "overrideDomain" );
        return override == null ? "" : override;
    }
}

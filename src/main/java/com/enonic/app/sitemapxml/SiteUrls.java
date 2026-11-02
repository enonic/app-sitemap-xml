package com.enonic.app.sitemapxml;

import java.util.Arrays;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.branch.Branch;
import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.portal.url.PortalScope;
import com.enonic.xp.portal.url.PortalScopeParams;
import com.enonic.xp.portal.url.PortalUrlService;
import com.enonic.xp.project.ProjectName;
import com.enonic.xp.repository.RepositoryId;
import com.enonic.xp.security.RoleKeys;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.site.SiteConfig;
import com.enonic.xp.site.SiteConfigs;
import com.enonic.xp.web.vhost.VirtualHostService;

/**
 * Where the site URL of a sitemap comes from: the app's "Base URL" override, the Base URL configured for the site in XP, or the request,
 * and whether a URL taken from the request is that of a virtual host.
 */
final class SiteUrls
{
    private static final Logger LOG = LoggerFactory.getLogger( SiteUrls.class );

    private final PortalUrlService portalUrlService;

    private final VirtualHostService virtualHostService;

    SiteUrls( final PortalUrlService portalUrlService, final VirtualHostService virtualHostService )
    {
        this.portalUrlService = portalUrlService;
        this.virtualHostService = virtualHostService;
    }

    /**
     * The Base URL configured in XP for the site at the given path, or for the project when the path is empty.
     */
    Optional<String> baseUrl( final RepositoryId repositoryId, final Branch branch, final String sitePath )
    {
        return scope( repositoryId, branch, sitePath ).map( PortalScope::getBaseUrl );
    }

    /**
     * Whether the site URL taken from the request is that of a virtual host matching its host exactly (not by a {@code ~} pattern),
     * without a port: such URLs come from the configuration, not from whatever the client sent, so the sitemap can be generated again
     * without a request.
     */
    boolean isVirtualHostUrl( final SitemapParams params )
    {
        final String host = params.hostWithoutPort();
        return host != null && virtualHostService.isEnabled() && virtualHostService.getVirtualHosts()
            .stream()
            .flatMap( virtualHost -> Arrays.stream( virtualHost.getHost().split( " " ) ) )
            .anyMatch( exact -> !exact.startsWith( "~" ) && exact.equalsIgnoreCase( host ) );
    }

    /**
     * Whether a request could still get the sitemap of the key as one generated again without a request: its site URL is the one the
     * configuration gives now. A sitemap whose "Base URL" or virtual host changed is not requested anymore. When the configuration can't
     * be read, the sitemap counts as current.
     */
    boolean isCurrent( final SitemapCache.Key key )
    {
        final Optional<PortalScope> scope = ContextBuilder.from( ContextAccessor.current() )
            .authInfo( AuthenticationInfo.copyOf( AuthenticationInfo.unAuthenticated() ).principals( RoleKeys.ADMIN ).build() )
            .build()
            .callWith( () -> scope( RepositoryId.from( key.repositoryId() ), ContentConstants.BRANCH_MASTER, key.params().sitePath() ) );
        if ( scope.isEmpty() )
        {
            return true;
        }
        final String siteUrl = key.params().siteUrl();
        final String override = override( scope.get().getSiteConfigs() );
        if ( !siteUrl.contains( "://" ) )
        {
            // Guillotine: the override, or nothing
            return siteUrl.equals( override );
        }
        if ( !override.isEmpty() )
        {
            // the override followed by the site's path: it must end where a path starts, not in the middle of a host or a segment
            return siteUrl.startsWith( override ) &&
                ( override.endsWith( "/" ) || siteUrl.length() == override.length() || siteUrl.charAt( override.length() ) == '/' );
        }
        final String baseUrl = scope.get().getBaseUrl();
        if ( baseUrl != null )
        {
            return siteUrl.equals( baseUrl );
        }
        return isVirtualHostUrl( key.params() );
    }

    private Optional<PortalScope> scope( final RepositoryId repositoryId, final Branch branch, final String sitePath )
    {
        try
        {
            return Optional.ofNullable( portalUrlService.portalScope( PortalScopeParams.create()
                                                                          .setProjectName( ProjectName.from( repositoryId ) )
                                                                          .setBranch( branch )
                                                                          .setContentPath(
                                                                              ContentPath.from( sitePath.isEmpty() ? "/" : sitePath ) )
                                                                          .build() ) );
        }
        catch ( RuntimeException e )
        {
            LOG.debug( "Could not resolve the portal scope of {}", sitePath, e );
            return Optional.empty();
        }
    }

    private static String override( final SiteConfigs siteConfigs )
    {
        final SiteConfig siteConfig = siteConfigs == null ? null : siteConfigs.get( SitemapCache.APPLICATION );
        final String override = siteConfig == null ? null : siteConfig.getConfig().getString( "overrideDomain" );
        return override == null ? "" : override;
    }
}

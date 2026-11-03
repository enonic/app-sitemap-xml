package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentNotFoundException;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.Context;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.portal.PortalRequest;
import com.enonic.xp.portal.PortalRequestAccessor;
import com.enonic.xp.security.PrincipalKey;
import com.enonic.xp.security.RoleKeys;
import com.enonic.xp.security.acl.AccessControlList;
import com.enonic.xp.security.acl.Permission;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.web.vhost.VirtualHost;
import com.enonic.xp.web.vhost.VirtualHostHelper;

public final class SitemapService
{
    private static final Logger LOG = LoggerFactory.getLogger( SitemapService.class );

    private final ContentService contentService;

    private final SitemapCache cache;

    private final SiteUrls siteUrls;

    public SitemapService( final ContentService contentService, final SitemapCache cache, final SiteUrls siteUrls )
    {
        this.contentService = contentService;
        this.cache = cache;
        this.siteUrls = siteUrls;
    }

    /**
     * Returns the sitemap of the published content visible to the principals of the request, cached for them; for a site readable by
     * everyone, the public sitemap. Without a site path, the sitemap lists the whole project. Requests on the draft branch (preview) get
     * a sitemap with the site URL only, or nothing for a project, which is not kept.
     *
     * @param siteUrlFromRequest true when the site URL of the params is taken from the request
     */
    public Sitemap sitemap( final SitemapParams requested, final boolean siteUrlFromRequest )
    {
        final Context context = ContextAccessor.current();
        return sitemap( context, key( context, requested, siteUrlFromRequest ) );
    }

    /**
     * The headless sitemap for Guillotine: paths relative to the site, the same for every host.
     */
    public Sitemap headless( final SitemapParams requested )
    {
        return sitemap( requested.withSiteUrl( "" ).withFormat( SitemapParams.Format.HEADLESS ), false );
    }

    private Sitemap sitemap( final Context context, final SitemapCache.Key key )
    {
        if ( ContentConstants.BRANCH_DRAFT.equals( context.getBranch() ) )
        {
            final SitemapCache.Source draft = key.params().sitePath().isEmpty() ? SitemapService::nothing : SitemapService::siteOnly;
            return cache.uncached( key, context.getAuthInfo(), draft );
        }
        return cache.get( key );
    }

    /**
     * The files of {@code sitemap.xml} for the request. A public sitemap whose URLs are taken from the request, as no "Base URL"
     * override or virtual host matching its host exactly gives them, is converted from the site's headless sitemap with the request's
     * site URL, instead of being generated for each host: the headless sitemap is the same for every host and generated again without a
     * request.
     *
     * @throws InvalidSiteUrlException when such a site URL is too long, see {@link ConvertedSitemap}
     */
    public SitemapFiles files( final SitemapParams requested, final boolean siteUrlFromRequest )
    {
        final Context context = ContextAccessor.current();
        final SitemapCache.Key key = key( context, requested, siteUrlFromRequest );
        if ( !ContentConstants.BRANCH_DRAFT.equals( context.getBranch() ) && key.isPublic() && siteUrlFromRequest &&
            !siteUrls.isVirtualHostUrl( key.params(), servedVirtualHost() ) )
        {
            final SitemapParams headless = key.params().withSiteUrl( "" ).withFormat( SitemapParams.Format.HEADLESS );
            return new ConvertedSitemap(
                cache.get( new SitemapCache.Key( key.repositoryId(), key.branch(), key.principals(), headless ) ), key.params() );
        }
        return sitemap( context, key );
    }

    /**
     * The key of the sitemap for the request, for the principals the sitemap is generated for.
     */
    private SitemapCache.Key key( final Context context, final SitemapParams requested, final boolean siteUrlFromRequest )
    {
        final String repositoryId = Objects.requireNonNull( context.getRepositoryId(), "No repository in context" ).toString();
        final SitemapParams params = siteUrlFromRequest ? requested.forRequestSiteUrl() : requested;
        final AuthenticationInfo authInfo = sitemapAuthInfo( context, params.sitePath() );
        return new SitemapCache.Key( repositoryId, ContentConstants.BRANCH_MASTER.getValue(), principals( authInfo ), params );
    }

    private static VirtualHost servedVirtualHost()
    {
        final PortalRequest request = PortalRequestAccessor.get();
        return request == null || request.getRawRequest() == null ? null : VirtualHostHelper.getVirtualHost( request.getRawRequest() );
    }

    /**
     * The principals the sitemap is generated and cached for: anonymous when the site, or the project, is readable by everyone, so that
     * logged-in users share the public sitemap, the request's otherwise.
     */
    private AuthenticationInfo sitemapAuthInfo( final Context context, final String sitePath )
    {
        final AuthenticationInfo authInfo = context.getAuthInfo();
        if ( !authInfo.isAuthenticated() )
        {
            return authInfo;
        }
        final AccessControlList permissions = sitePath.isEmpty() ? projectPermissions( context ) : sitePermissions( context, sitePath );
        return permissions != null && permissions.isAllowedFor( RoleKeys.EVERYONE, Permission.READ )
            ? AuthenticationInfo.unAuthenticated()
            : authInfo;
    }

    private AccessControlList sitePermissions( final Context context, final String sitePath )
    {
        try
        {
            return ContextBuilder.from( context )
                .branch( ContentConstants.BRANCH_MASTER )
                .build()
                .callWith( () -> contentService.getByPath( ContentPath.from( sitePath ) ) )
                .getPermissions();
        }
        catch ( ContentNotFoundException e )
        {
            return null;
        }
    }

    private AccessControlList projectPermissions( final Context context )
    {
        try
        {
            return ContextBuilder.from( context )
                .branch( ContentConstants.BRANCH_MASTER )
                .authInfo( AuthenticationInfo.copyOf( context.getAuthInfo() ).principals( RoleKeys.ADMIN ).build() )
                .build()
                .callWith( contentService::getRootPermissions );
        }
        catch ( RuntimeException e )
        {
            LOG.debug( "Could not read permissions of project {}", context.getRepositoryId(), e );
            return null;
        }
    }

    private static Set<String> principals( final AuthenticationInfo authInfo )
    {
        return authInfo.getPrincipals().stream().map( PrincipalKey::toString ).collect( Collectors.toUnmodifiableSet() );
    }

    private static Instant nothing( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        return null;
    }

    private static Instant siteOnly( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        sink.accept( new SitemapEntry( "", SitemapParams.SITE_CONTENT_TYPE, null ) );
        return null;
    }
}

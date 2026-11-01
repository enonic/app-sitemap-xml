package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.branch.Branch;
import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentNotFoundException;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.Context;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.portal.url.PortalScopeParams;
import com.enonic.xp.portal.url.PortalUrlService;
import com.enonic.xp.project.ProjectName;
import com.enonic.xp.repository.RepositoryId;
import com.enonic.xp.security.PrincipalKey;
import com.enonic.xp.security.RoleKeys;
import com.enonic.xp.security.acl.Permission;
import com.enonic.xp.security.auth.AuthenticationInfo;

public final class SitemapService
{
    private static final Logger LOG = LoggerFactory.getLogger( SitemapService.class );

    private final ContentService contentService;

    private final PortalUrlService portalUrlService;

    private final SitemapCache cache;

    private final Clock clock;

    public SitemapService( final ContentService contentService, final PortalUrlService portalUrlService, final SitemapCache cache,
                           final Clock clock )
    {
        this.contentService = contentService;
        this.portalUrlService = portalUrlService;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Returns the sitemap of the published content visible to the principals of the request, cached for them; for a site readable by
     * everyone, the public sitemap. Requests on the draft
     * branch (preview) get a sitemap with the site URL only, which is not kept.
     *
     * @param siteUrlFromRequest true when the site URL of the params is taken from the request: a Base URL configured for the site in
     *                           XP is then used instead, when there is one
     */
    public Sitemap sitemap( final SitemapParams requested, final boolean siteUrlFromRequest )
    {
        final Context context = ContextAccessor.current();
        final RepositoryId repositoryId = Objects.requireNonNull( context.getRepositoryId(), "No repository in context" );
        final Branch branch = ContentConstants.BRANCH_MASTER;

        final Optional<String> baseUrl = siteUrlFromRequest ? baseUrl( repositoryId, branch, requested.sitePath() ) : Optional.empty();
        final SitemapParams params =
            baseUrl.map( requested::withSiteUrl ).orElseGet( () -> siteUrlFromRequest ? requested.forRequestSiteUrl() : requested );
        final AuthenticationInfo authInfo = sitemapAuthInfo( context, params.sitePath() );
        final SitemapCache.Key key = new SitemapCache.Key( repositoryId.toString(), branch.getValue(), principals( authInfo ), params );

        if ( ContentConstants.BRANCH_DRAFT.equals( context.getBranch() ) )
        {
            return cache.uncached( key, context.getAuthInfo(), SitemapService::siteOnly );
        }
        final SitemapCache.Source source =
            ( sink, cancelled ) -> new SitemapQuery( contentService, params, clock.instant() ).execute( sink, cancelled );
        final boolean background = !siteUrlFromRequest || baseUrl.isPresent();
        return cache.get( key, background, authInfo, source );
    }

    /**
     * The principals the sitemap is generated and cached for: anonymous when the site is readable by everyone, so that logged-in users
     * share the public sitemap, the request's otherwise.
     */
    private AuthenticationInfo sitemapAuthInfo( final Context context, final String sitePath )
    {
        final AuthenticationInfo authInfo = context.getAuthInfo();
        if ( !authInfo.isAuthenticated() )
        {
            return authInfo;
        }
        try
        {
            final Content site = ContextBuilder.from( context )
                .branch( ContentConstants.BRANCH_MASTER )
                .build()
                .callWith( () -> contentService.getByPath( ContentPath.from( sitePath.isEmpty() ? "/" : sitePath ) ) );
            return site.getPermissions().isAllowedFor( RoleKeys.EVERYONE, Permission.READ ) ? AuthenticationInfo.unAuthenticated() : authInfo;
        }
        catch ( ContentNotFoundException e )
        {
            return authInfo;
        }
    }

    private static Set<String> principals( final AuthenticationInfo authInfo )
    {
        return authInfo.getPrincipals().stream().map( PrincipalKey::toString ).collect( Collectors.toUnmodifiableSet() );
    }

    private static @Nullable Instant siteOnly( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        sink.accept( new SitemapEntry( "", SitemapParams.SITE_CONTENT_TYPE, null ) );
        return null;
    }

    private Optional<String> baseUrl( final RepositoryId repositoryId, final Branch branch, final String sitePath )
    {
        try
        {
            return Optional.ofNullable( portalUrlService.portalScope( PortalScopeParams.create()
                                                                          .setProjectName( ProjectName.from( repositoryId ) )
                                                                          .setBranch( branch )
                                                                          .setContentPath( ContentPath.from(
                                                                              sitePath.isEmpty() ? "/" : sitePath ) )
                                                                          .build() ).getBaseUrl() );
        }
        catch ( RuntimeException e )
        {
            LOG.debug( "Could not resolve Base URL of {}", sitePath, e );
            return Optional.empty();
        }
    }
}

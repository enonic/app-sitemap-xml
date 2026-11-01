package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentNotFoundException;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.Context;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.security.PrincipalKey;
import com.enonic.xp.security.RoleKeys;
import com.enonic.xp.security.acl.Permission;
import com.enonic.xp.security.auth.AuthenticationInfo;

public final class SitemapService
{
    private final ContentService contentService;

    private final SitemapCache cache;

    private final Clock clock;

    public SitemapService( final ContentService contentService, final SitemapCache cache, final Clock clock )
    {
        this.contentService = contentService;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Returns the sitemap of the published content visible to the principals of the request, cached for them; for a site readable by
     * everyone, the public sitemap. Requests on the draft
     * branch (preview) get a sitemap with the site URL only, which is not kept.
     *
     * @param siteUrlFromRequest true when the site URL of the params is taken from the request: the sitemap is then generated on request
     *                           only. Otherwise ("Base URL" override, or Guillotine which returns paths) it is generated again in the
     *                           background when content changes.
     */
    public Sitemap sitemap( final SitemapParams requested, final boolean siteUrlFromRequest )
    {
        final SitemapParams params = siteUrlFromRequest ? requested.forRequestSiteUrl() : requested;
        final Context context = ContextAccessor.current();
        final String repositoryId = Objects.requireNonNull( context.getRepositoryId(), "No repository in context" ).toString();
        final AuthenticationInfo authInfo = sitemapAuthInfo( context, params.sitePath() );
        final SitemapCache.Key key =
            new SitemapCache.Key( repositoryId, ContentConstants.BRANCH_MASTER.getValue(), principals( authInfo ), params );

        if ( ContentConstants.BRANCH_DRAFT.equals( context.getBranch() ) )
        {
            return cache.uncached( key, context.getAuthInfo(), SitemapService::siteOnly );
        }
        final SitemapCache.Source source =
            ( sink, cancelled ) -> new SitemapQuery( contentService, params, clock.instant() ).execute( sink, cancelled );
        return cache.get( key, !siteUrlFromRequest, authInfo, source );
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

    private static Instant siteOnly( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        sink.accept( new SitemapEntry( "", SitemapParams.SITE_CONTENT_TYPE, null ) );
        return null;
    }
}

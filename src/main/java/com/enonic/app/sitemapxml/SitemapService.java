package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.Context;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.security.PrincipalKey;

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
     * Returns the sitemap of the published content visible to the principals of the request, cached for them. Requests on the draft
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
        final SitemapCache.Key key =
            new SitemapCache.Key( repositoryId, ContentConstants.BRANCH_MASTER.getValue(), principals( context ), params );

        if ( ContentConstants.BRANCH_DRAFT.equals( context.getBranch() ) )
        {
            return cache.uncached( key, context.getAuthInfo(), SitemapService::siteOnly );
        }
        final SitemapCache.Source source =
            ( sink, cancelled ) -> new SitemapQuery( contentService, params, clock.instant() ).execute( sink, cancelled );
        return cache.get( key, !siteUrlFromRequest, context.getAuthInfo(), source );
    }

    private static Set<String> principals( final Context context )
    {
        return context.getAuthInfo().getPrincipals().stream().map( PrincipalKey::toString ).collect( Collectors.toUnmodifiableSet() );
    }

    private static Instant siteOnly( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        sink.accept( new SitemapEntry( "", SitemapParams.SITE_CONTENT_TYPE, null ) );
        return null;
    }
}

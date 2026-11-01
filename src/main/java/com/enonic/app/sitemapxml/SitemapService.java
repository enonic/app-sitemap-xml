package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
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
     * @param siteUrlFromRequest true when the site URL of the params is taken from the request: the sitemap is then generated on request
     *                           only. Otherwise ("Base URL" override, or Guillotine which returns paths) it is generated again in the
     *                           background when content changes.
     */
    public Sitemap sitemap( final SitemapParams requested, final boolean siteUrlFromRequest )
    {
        final SitemapParams params = siteUrlFromRequest ? requested.withLowerCaseHost() : requested;
        final Context context = ContextAccessor.current();
        final String repositoryId = Objects.requireNonNull( context.getRepositoryId(), "No repository in context" ).toString();
        final String branch = ContentConstants.BRANCH_MASTER.getValue();
        final Set<String> principals =
            context.getAuthInfo().getPrincipals().stream().map( PrincipalKey::toString ).collect( Collectors.toUnmodifiableSet() );

        return cache.get( new SitemapCache.Key( repositoryId, branch, principals, params ), !siteUrlFromRequest, context.getAuthInfo(),
                          ( sink, cancelled ) -> new SitemapQuery( contentService, params, clock.instant() ).execute( sink, cancelled ) );
    }
}

package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    public Sitemap sitemap( final SitemapParams params )
    {
        final Context context = ContextAccessor.current();
        final String repositoryId = Objects.requireNonNull( context.getRepositoryId(), "No repository in context" ).toString();
        final String branch = Objects.requireNonNull( context.getBranch(), "No branch in context" ).getValue();
        final Set<String> principals =
            context.getAuthInfo().getPrincipals().stream().map( PrincipalKey::toString ).collect( Collectors.toUnmodifiableSet() );

        return cache.get( new SitemapCache.Key( repositoryId, branch, principals, params ), context.getAuthInfo(),
                          () -> new SitemapQuery( contentService, params, clock.instant() ).execute() );
    }
}

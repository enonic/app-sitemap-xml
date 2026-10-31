package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.event.Event;
import com.enonic.xp.event.EventListener;

@Component(immediate = true, service = {SitemapCache.class, EventListener.class})
public final class SitemapCache
    implements EventListener
{
    static final int MAX_ENTRIES = 100;

    private static final Logger LOG = LoggerFactory.getLogger( SitemapCache.class );

    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();

    private final Clock clock;

    public SitemapCache()
    {
        this( Clock.systemUTC() );
    }

    SitemapCache( final Clock clock )
    {
        this.clock = clock;
    }

    public Sitemap get( final Key key, final Supplier<SitemapContents> generator )
    {
        while ( true )
        {
            final Entry existing = entries.get( key );
            if ( existing != null && !existing.isExpired( clock.instant() ) )
            {
                return existing.await();
            }

            final Entry created = new Entry( clock.instant() );
            final boolean won = existing == null ? entries.putIfAbsent( key, created ) == null : entries.replace( key, existing, created );
            if ( !won )
            {
                continue;
            }

            evictOverflow();
            try
            {
                final SitemapContents contents = generator.get();
                created.complete( new Sitemap( key.params(), contents.entries() ), contents.validUntil() );
            }
            catch ( RuntimeException e )
            {
                entries.remove( key, created );
                created.fail( e );
                throw e;
            }
            return created.await();
        }
    }

    public void invalidate( final String repositoryId, final String branch )
    {
        if ( entries.keySet().removeIf( key -> key.repositoryId().equals( repositoryId ) && key.branch().equals( branch ) ) )
        {
            LOG.debug( "Invalidated sitemaps for repository {} branch {}", repositoryId, branch );
        }
    }

    public void invalidate( final String repositoryId )
    {
        if ( entries.keySet().removeIf( key -> key.repositoryId().equals( repositoryId ) ) )
        {
            LOG.debug( "Invalidated sitemaps for repository {}", repositoryId );
        }
    }

    public void invalidateAll()
    {
        entries.clear();
    }

    int size()
    {
        return entries.size();
    }

    @Override
    public void onEvent( final Event event )
    {
        final String type = event.getType();
        if ( type.startsWith( "node." ) )
        {
            if ( event.getData().get( "nodes" ) instanceof List<?> nodes )
            {
                for ( final Object node : nodes )
                {
                    if ( node instanceof Map<?, ?> map && map.get( "repo" ) instanceof String repo && map.get( "branch" ) instanceof String branch )
                    {
                        invalidate( repo, branch );
                    }
                }
            }
            else if ( event.getData().get( "repo" ) instanceof String repo && event.getData().get( "branch" ) instanceof String branch )
            {
                invalidate( repo, branch );
            }
        }
        else if ( type.startsWith( "repository." ) && event.getData().get( "id" ) instanceof String repo )
        {
            invalidate( repo );
        }
    }

    private void evictOverflow()
    {
        while ( entries.size() > MAX_ENTRIES )
        {
            entries.entrySet()
                .stream()
                .min( Comparator.comparing( e -> e.getValue().createdAt() ) )
                .ifPresent( oldest -> entries.remove( oldest.getKey(), oldest.getValue() ) );
        }
    }

    public record Key(String repositoryId, String branch, Set<String> principals, SitemapParams params)
    {
        public Key
        {
            principals = Set.copyOf( principals );
        }
    }

    private static final class Entry
    {
        private final CompletableFuture<Sitemap> sitemap = new CompletableFuture<>();

        private final Instant createdAt;

        private volatile @Nullable Instant validUntil;

        Entry( final Instant createdAt )
        {
            this.createdAt = createdAt;
        }

        Instant createdAt()
        {
            return createdAt;
        }

        boolean isExpired( final Instant now )
        {
            final Instant until = validUntil;
            return until != null && !now.isBefore( until );
        }

        void complete( final Sitemap value, final @Nullable Instant until )
        {
            this.validUntil = until;
            sitemap.complete( value );
        }

        void fail( final RuntimeException e )
        {
            sitemap.completeExceptionally( e );
        }

        Sitemap await()
        {
            try
            {
                return sitemap.join();
            }
            catch ( CompletionException e )
            {
                if ( e.getCause() instanceof RuntimeException cause )
                {
                    throw cause;
                }
                throw e;
            }
        }
    }
}

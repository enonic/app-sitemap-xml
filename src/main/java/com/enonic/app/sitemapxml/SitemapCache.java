package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.event.Event;
import com.enonic.xp.event.EventListener;
import com.enonic.xp.security.auth.AuthenticationInfo;

@Component(immediate = true, service = {SitemapCache.class, EventListener.class})
public final class SitemapCache
    implements EventListener
{
    static final int MAX_ENTRIES = 100;

    static final int MAX_IN_FLIGHT = 10;

    private static final Logger LOG = LoggerFactory.getLogger( SitemapCache.class );

    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();

    private final Semaphore inFlight = new Semaphore( MAX_IN_FLIGHT );

    private final ExecutorService generator;

    private final Clock clock;

    public SitemapCache()
    {
        this( Executors.newSingleThreadExecutor( runnable -> {
            final Thread thread = new Thread( runnable, "sitemapxml-generator" );
            thread.setDaemon( true );
            return thread;
        } ), Clock.systemUTC() );
    }

    SitemapCache( final ExecutorService generator, final Clock clock )
    {
        this.generator = generator;
        this.clock = clock;
    }

    @Deactivate
    public void deactivate()
    {
        generator.shutdownNow();
    }

    public Sitemap get( final Key key, final AuthenticationInfo authInfo, final Supplier<SitemapContents> query )
    {
        if ( !inFlight.tryAcquire() )
        {
            throw new SitemapBusyException();
        }
        try
        {
            while ( true )
            {
                final Instant now = clock.instant();
                final Entry existing = entries.get( key );
                if ( existing != null && !existing.isExpired( now ) )
                {
                    existing.lastAccess = now;
                    return await( existing.sitemap );
                }

                final Entry created = new Entry( now );
                final boolean won = existing == null ? entries.putIfAbsent( key, created ) == null : entries.replace( key, existing, created );
                if ( won )
                {
                    evictOverflow();
                    submit( key, created, authInfo, query );
                    return await( created.sitemap );
                }
            }
        }
        finally
        {
            inFlight.release();
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

    int size()
    {
        return entries.size();
    }

    int inFlight()
    {
        return MAX_IN_FLIGHT - inFlight.availablePermits();
    }

    @Override
    public void onEvent( final Event event )
    {
        final String type = event.getType();
        final Map<String, Object> data = event.getData();
        if ( type.startsWith( "node." ) )
        {
            final Object nodes = data.get( "nodes" );
            if ( nodes instanceof List )
            {
                for ( final Object node : (List<?>) nodes )
                {
                    if ( node instanceof Map )
                    {
                        invalidate( (Map<?, ?>) node );
                    }
                }
            }
            else
            {
                invalidate( data );
            }
        }
        else if ( type.startsWith( "repository." ) && data.get( "id" ) instanceof String )
        {
            invalidate( (String) data.get( "id" ) );
        }
    }

    private void invalidate( final Map<?, ?> node )
    {
        final Object repo = node.get( "repo" );
        final Object branch = node.get( "branch" );
        if ( repo instanceof String && branch instanceof String )
        {
            invalidate( (String) repo, (String) branch );
        }
    }

    private void submit( final Key key, final Entry entry, final AuthenticationInfo authInfo, final Supplier<SitemapContents> query )
    {
        try
        {
            generator.execute( () -> generate( key, entry, authInfo, query ) );
        }
        catch ( RejectedExecutionException e )
        {
            entries.remove( key, entry );
            entry.sitemap.completeExceptionally( e );
        }
    }

    private void generate( final Key key, final Entry entry, final AuthenticationInfo authInfo, final Supplier<SitemapContents> query )
    {
        try
        {
            final SitemapContents contents = ContextBuilder.create()
                .repositoryId( key.repositoryId() )
                .branch( key.branch() )
                .authInfo( authInfo )
                .build()
                .callWith( query::get );
            entry.validUntil = contents.validUntil();
            entry.sitemap.complete( new Sitemap( key.params(), contents.entries() ) );
        }
        catch ( RuntimeException e )
        {
            entries.remove( key, entry );
            entry.sitemap.completeExceptionally( e );
        }
    }

    private void evictOverflow()
    {
        while ( entries.size() > MAX_ENTRIES )
        {
            entries.entrySet()
                .stream()
                .min( Comparator.comparing( e -> e.getValue().lastAccess ) )
                .ifPresent( oldest -> entries.remove( oldest.getKey(), oldest.getValue() ) );
        }
    }

    private static Sitemap await( final CompletableFuture<Sitemap> future )
    {
        try
        {
            return future.join();
        }
        catch ( CompletionException e )
        {
            if ( e.getCause() instanceof RuntimeException )
            {
                throw (RuntimeException) e.getCause();
            }
            throw e;
        }
    }

    public static final class Key
    {
        private final String repositoryId;

        private final String branch;

        private final Set<String> principals;

        private final SitemapParams params;

        public Key( final String repositoryId, final String branch, final Set<String> principals, final SitemapParams params )
        {
            this.repositoryId = Objects.requireNonNull( repositoryId );
            this.branch = Objects.requireNonNull( branch );
            this.principals = Set.copyOf( principals );
            this.params = Objects.requireNonNull( params );
        }

        public String repositoryId()
        {
            return repositoryId;
        }

        public String branch()
        {
            return branch;
        }

        public SitemapParams params()
        {
            return params;
        }

        @Override
        public boolean equals( final Object o )
        {
            if ( this == o )
            {
                return true;
            }
            if ( !( o instanceof Key ) )
            {
                return false;
            }
            final Key key = (Key) o;
            return repositoryId.equals( key.repositoryId ) && branch.equals( key.branch ) && principals.equals( key.principals ) &&
                params.equals( key.params );
        }

        @Override
        public int hashCode()
        {
            return Objects.hash( repositoryId, branch, principals, params );
        }

        @Override
        public String toString()
        {
            return repositoryId + " " + branch + " " + params.sitePath();
        }
    }

    private static final class Entry
    {
        final CompletableFuture<Sitemap> sitemap = new CompletableFuture<>();

        volatile Instant validUntil;

        volatile Instant lastAccess;

        Entry( final Instant now )
        {
            this.lastAccess = now;
        }

        boolean isExpired( final Instant now )
        {
            final Instant until = validUntil;
            return until != null && !now.isBefore( until );
        }
    }
}

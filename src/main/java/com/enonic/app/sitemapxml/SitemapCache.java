package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
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

    static final Duration REGENERATION_DELAY = Duration.ofSeconds( 5 );

    static final Duration IDLE_TIMEOUT = Duration.ofHours( 1 );

    private static final Logger LOG = LoggerFactory.getLogger( SitemapCache.class );

    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();

    private final Semaphore inFlight = new Semaphore( MAX_IN_FLIGHT );

    private final ScheduledExecutorService generator;

    private final Clock clock;

    private final Duration regenerationDelay;

    public SitemapCache()
    {
        this( Executors.newSingleThreadScheduledExecutor( runnable -> {
            final Thread thread = new Thread( runnable, "sitemapxml-generator" );
            thread.setDaemon( true );
            return thread;
        } ), Clock.systemUTC(), REGENERATION_DELAY );
    }

    SitemapCache( final ScheduledExecutorService generator, final Clock clock, final Duration regenerationDelay )
    {
        this.generator = generator;
        this.clock = clock;
        this.regenerationDelay = regenerationDelay;
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
            final Instant now = clock.instant();
            final Entry entry = entries.computeIfAbsent( key, k -> new Entry( authInfo, query, now ) );
            entry.lastAccess = now;

            final Sitemap current = entry.sitemap;
            if ( current != null )
            {
                if ( entry.isStale( now ) )
                {
                    scheduleRegeneration( key, entry, Duration.ZERO );
                }
                return current;
            }

            if ( entry.firstSubmitted.compareAndSet( false, true ) )
            {
                evictOverflow();
                submit( () -> generateFirst( key, entry ), Duration.ZERO, entry.first );
            }
            return await( entry.first );
        }
        finally
        {
            inFlight.release();
        }
    }

    public void invalidate( final String repositoryId, final String branch )
    {
        entries.forEach( ( key, entry ) -> {
            if ( key.repositoryId().equals( repositoryId ) && key.branch().equals( branch ) )
            {
                invalidate( key, entry );
            }
        } );
    }

    public void invalidate( final String repositoryId )
    {
        entries.forEach( ( key, entry ) -> {
            if ( key.repositoryId().equals( repositoryId ) )
            {
                invalidate( key, entry );
            }
        } );
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
        if ( type.startsWith( "node." ) )
        {
            if ( event.getData().get( "nodes" ) instanceof List<?> nodes )
            {
                for ( final Object node : nodes )
                {
                    if ( node instanceof Map<?, ?> map && map.get( "repo" ) instanceof String repo &&
                        map.get( "branch" ) instanceof String branch )
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

    private void invalidate( final Key key, final Entry entry )
    {
        if ( entry.sitemap == null || entry.lastAccess.plus( IDLE_TIMEOUT ).isBefore( clock.instant() ) )
        {
            entries.remove( key, entry );
            LOG.debug( "Removed sitemap {}", key );
            return;
        }
        entry.version.incrementAndGet();
        scheduleRegeneration( key, entry, regenerationDelay );
    }

    private void scheduleRegeneration( final Key key, final Entry entry, final Duration delay )
    {
        if ( entry.regenerationScheduled.compareAndSet( false, true ) )
        {
            submit( () -> regenerate( key, entry ), delay, null );
        }
    }

    private void submit( final Runnable task, final Duration delay, final @Nullable CompletableFuture<Sitemap> onRejected )
    {
        try
        {
            generator.schedule( task, delay.toMillis(), TimeUnit.MILLISECONDS );
        }
        catch ( RejectedExecutionException e )
        {
            if ( onRejected != null )
            {
                onRejected.completeExceptionally( e );
            }
        }
    }

    private void generateFirst( final Key key, final Entry entry )
    {
        try
        {
            final long version = entry.version.get();
            final SitemapContents contents = generate( key, entry );
            entry.update( new Sitemap( key.params(), contents.entries() ), contents.validUntil(), version );
            entry.first.complete( entry.sitemap );
        }
        catch ( RuntimeException e )
        {
            entries.remove( key, entry );
            entry.first.completeExceptionally( e );
        }
    }

    private void regenerate( final Key key, final Entry entry )
    {
        entry.regenerationScheduled.set( false );
        if ( entries.get( key ) != entry )
        {
            return;
        }
        try
        {
            final long version = entry.version.get();
            final SitemapContents contents = generate( key, entry );
            entry.update( new Sitemap( key.params(), contents.entries() ), contents.validUntil(), version );
            LOG.debug( "Regenerated sitemap {}", key );
        }
        catch ( RuntimeException e )
        {
            LOG.warn( "Could not regenerate sitemap for {} {} {}", key.repositoryId(), key.branch(), key.params().sitePath(), e );
        }
    }

    private SitemapContents generate( final Key key, final Entry entry )
    {
        return ContextBuilder.create()
            .repositoryId( key.repositoryId() )
            .branch( key.branch() )
            .authInfo( entry.authInfo )
            .build()
            .callWith( entry.query::get );
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
            if ( e.getCause() instanceof RuntimeException cause )
            {
                throw cause;
            }
            throw e;
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
        final AuthenticationInfo authInfo;

        final Supplier<SitemapContents> query;

        final CompletableFuture<Sitemap> first = new CompletableFuture<>();

        final AtomicBoolean firstSubmitted = new AtomicBoolean();

        final AtomicBoolean regenerationScheduled = new AtomicBoolean();

        final AtomicLong version = new AtomicLong();

        volatile long generatedVersion = -1;

        volatile @Nullable Sitemap sitemap;

        volatile @Nullable Instant validUntil;

        volatile Instant lastAccess;

        Entry( final AuthenticationInfo authInfo, final Supplier<SitemapContents> query, final Instant now )
        {
            this.authInfo = authInfo;
            this.query = query;
            this.lastAccess = now;
        }

        boolean isStale( final Instant now )
        {
            final Instant until = validUntil;
            return generatedVersion != version.get() || ( until != null && !now.isBefore( until ) );
        }

        void update( final Sitemap value, final @Nullable Instant until, final long fromVersion )
        {
            this.validUntil = until;
            this.generatedVersion = fromVersion;
            this.sitemap = value;
        }
    }
}

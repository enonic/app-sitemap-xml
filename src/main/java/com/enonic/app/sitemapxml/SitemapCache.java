package com.enonic.app.sitemapxml;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.event.Event;
import com.enonic.xp.event.EventListener;
import com.enonic.xp.home.HomeDir;
import com.enonic.xp.security.auth.AuthenticationInfo;

/**
 * Generated sitemaps, kept until content of their project changes.
 * <p>
 * Sitemaps are generated one at a time, in the order they are requested. There is no limit on how many generations are queued, but
 * when more than {@value #MAX_WAITING} requests are already waiting for sitemaps that are not generated yet, further such requests get
 * {@link SitemapBusyException}. Requests for generated sitemaps are always answered.
 * <p>
 * A sitemap whose URLs do not depend on the request (the site has a Base URL) is generated again in the background a few seconds after
 * its content changes, and the previous one is served until then. A generation still running when the content changes again is
 * halted and started over. Other sitemaps are dropped when their content changes and generated again on the next request.
 */
@Component(immediate = true, service = {SitemapCache.class, EventListener.class})
public final class SitemapCache
    implements EventListener
{
    static final int MAX_ENTRIES = 100;

    static final int MAX_WAITING = 10;

    static final Duration REGENERATION_DELAY = Duration.ofSeconds( 5 );

    static final Duration IDLE_TIMEOUT = Duration.ofHours( 1 );

    static final Duration FILE_RETENTION = Duration.ofMinutes( 1 );

    private static final Logger LOG = LoggerFactory.getLogger( SitemapCache.class );

    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();

    private final Semaphore waiting = new Semaphore( MAX_WAITING );

    private final ScheduledExecutorService generator;

    private final Clock clock;

    private final Duration regenerationDelay;

    private final Duration fileRetention;

    private final Path storage;

    public SitemapCache()
    {
        this( Executors.newSingleThreadScheduledExecutor( runnable -> {
            final Thread thread = new Thread( runnable, "sitemapxml-generator" );
            thread.setDaemon( true );
            return thread;
        } ), Clock.systemUTC(), REGENERATION_DELAY, FILE_RETENTION, HomeDir.get().toPath().resolve( "work" ).resolve( "com.enonic.app.sitemapxml" ) );
    }

    SitemapCache( final ScheduledExecutorService generator, final Clock clock, final Duration regenerationDelay,
                  final Duration fileRetention, final Path storage )
    {
        this.generator = generator;
        this.clock = clock;
        this.regenerationDelay = regenerationDelay;
        this.fileRetention = fileRetention;
        this.storage = storage;
        Sitemap.delete( storage );
    }

    @Deactivate
    public void deactivate()
    {
        generator.shutdownNow();
        entries.values().forEach( entry -> entry.ready.completeExceptionally( new CancellationException( "Sitemaps stopped" ) ) );
        entries.clear();
        Sitemap.delete( storage );
    }

    /**
     * Returns the sitemap of the given key, generating it first when there is none yet.
     *
     * @param background whether the sitemap may be generated again in the background: its URLs do not depend on the request
     * @param authInfo   the generation runs with these principals
     * @param source     writes the entries of the sitemap
     * @throws SitemapBusyException when the sitemap is not generated yet and too many requests are already waiting
     */
    public Sitemap get( final Key key, final boolean background, final AuthenticationInfo authInfo, final Source source )
    {
        while ( true )
        {
            final Instant now = clock.instant();
            final Entry entry = entries.computeIfAbsent( key, k -> new Entry( background, authInfo, source, now ) );
            entry.lastAccess = now;

            final Sitemap current = entry.sitemap;
            if ( current != null )
            {
                if ( !entry.isExpired( now ) )
                {
                    return current;
                }
                if ( entry.background )
                {
                    generate( key, entry, Duration.ZERO, false );
                    return current;
                }
                remove( key, entry );
                continue;
            }

            if ( entries.size() > MAX_ENTRIES )
            {
                evictOverflow();
            }
            generate( key, entry, Duration.ZERO, false );
            if ( !waiting.tryAcquire() )
            {
                throw new SitemapBusyException();
            }
            try
            {
                return await( entry.ready );
            }
            finally
            {
                waiting.release();
            }
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

    int waiting()
    {
        return MAX_WAITING - waiting.availablePermits();
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
        if ( entry.sitemap == null )
        {
            // Requests are waiting for it: start over with the changed content.
            generate( key, entry, Duration.ZERO, true );
        }
        else if ( entry.background && !entry.lastAccess.plus( IDLE_TIMEOUT ).isBefore( clock.instant() ) )
        {
            generate( key, entry, regenerationDelay, true );
        }
        else
        {
            remove( key, entry );
            LOG.debug( "Removed sitemap {}", key );
        }
    }

    /**
     * Queues a generation of the entry after the given delay, unless one is already queued to start by then.
     *
     * @param restart whether content changed: a generation already running is then halted and queued again
     */
    private void generate( final Key key, final Entry entry, final Duration delay, final boolean restart )
    {
        synchronized ( entry )
        {
            final Generation current = entry.generation;
            if ( current != null )
            {
                if ( current.started ? !restart : current.startsWithin( delay ) )
                {
                    return;
                }
                current.cancel();
            }
            final Generation generation = new Generation( key, entry );
            entry.generation = generation;
            try
            {
                generation.future = generator.schedule( generation, delay.toMillis(), TimeUnit.MILLISECONDS );
            }
            catch ( RejectedExecutionException e )
            {
                entry.generation = null;
                entry.ready.completeExceptionally( e );
            }
        }
    }

    private void remove( final Key key, final Entry entry )
    {
        if ( entries.remove( key, entry ) )
        {
            synchronized ( entry )
            {
                final Generation generation = entry.generation;
                if ( generation != null )
                {
                    generation.cancel();
                    entry.generation = null;
                }
            }
            entry.ready.completeExceptionally( new CancellationException( "Sitemap removed" ) );
            deleteLater( entry.sitemap );
        }
    }

    private void deleteLater( final @Nullable Sitemap sitemap )
    {
        if ( sitemap == null )
        {
            return;
        }
        try
        {
            final Runnable delete = sitemap::delete;
            generator.schedule( delete, fileRetention.toMillis(), TimeUnit.MILLISECONDS );
        }
        catch ( RejectedExecutionException e )
        {
            sitemap.delete();
        }
    }

    /**
     * Removes the least recently used generated sitemaps. Sitemaps that requests are waiting for are kept.
     */
    private void evictOverflow()
    {
        entries.entrySet()
            .stream()
            .filter( e -> e.getValue().sitemap != null )
            .sorted( Map.Entry.comparingByValue( ( a, b ) -> a.lastAccess.compareTo( b.lastAccess ) ) )
            .limit( Math.max( 0, entries.size() - MAX_ENTRIES ) )
            .toList()
            .forEach( e -> remove( e.getKey(), e.getValue() ) );
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

    /**
     * Writes the entries of a sitemap.
     */
    @FunctionalInterface
    public interface Source
    {
        /**
         * @param sink      receives the entries
         * @param cancelled returns true once the generation is halted
         * @return when the sitemap must be generated again, or null
         */
        @Nullable Instant write( Consumer<SitemapEntry> sink, BooleanSupplier cancelled );
    }

    public record Key(String repositoryId, String branch, Set<String> principals, SitemapParams params)
    {
        public Key
        {
            principals = Set.copyOf( principals );
        }
    }

    private final class Generation
        implements Runnable
    {
        final Key key;

        final Entry entry;

        volatile @Nullable ScheduledFuture<?> future;

        volatile boolean started;

        volatile boolean cancelled;

        Generation( final Key key, final Entry entry )
        {
            this.key = key;
            this.entry = entry;
        }

        boolean startsWithin( final Duration delay )
        {
            final ScheduledFuture<?> scheduled = future;
            return scheduled != null && scheduled.getDelay( TimeUnit.MILLISECONDS ) <= delay.toMillis();
        }

        void cancel()
        {
            cancelled = true;
            final ScheduledFuture<?> scheduled = future;
            if ( scheduled != null && !started )
            {
                scheduled.cancel( false );
            }
        }

        @Override
        public void run()
        {
            synchronized ( entry )
            {
                if ( cancelled || entry.generation != this )
                {
                    return;
                }
                started = true;
            }
            try
            {
                final Instant validUntil;
                final Sitemap sitemap;
                try (Sitemap.Writer writer = Sitemap.writer( storage.resolve( UUID.randomUUID().toString() ), key.params() ))
                {
                    validUntil = ContextBuilder.create()
                        .repositoryId( key.repositoryId() )
                        .branch( key.branch() )
                        .authInfo( entry.authInfo )
                        .build()
                        .callWith( () -> entry.source.write( writer::add, () -> cancelled ) );
                    if ( cancelled )
                    {
                        throw new CancellationException();
                    }
                    sitemap = writer.finish();
                }
                publish( sitemap, validUntil );
            }
            catch ( CancellationException e )
            {
                LOG.debug( "Generation of sitemap {} halted", key );
            }
            catch ( RuntimeException e )
            {
                failed( e );
            }
        }

        private void publish( final Sitemap sitemap, final @Nullable Instant validUntil )
        {
            final Sitemap previous;
            synchronized ( entry )
            {
                if ( entry.generation == this )
                {
                    entry.generation = null;
                }
                if ( cancelled || entries.get( key ) != entry )
                {
                    sitemap.delete();
                    return;
                }
                previous = entry.sitemap;
                entry.validUntil = validUntil;
                entry.sitemap = sitemap;
            }
            entry.ready.complete( sitemap );
            deleteLater( previous );
            LOG.debug( "Generated sitemap {}", key );

            if ( entry.background && validUntil != null )
            {
                generate( key, entry, Duration.between( clock.instant(), validUntil ), false );
            }
        }

        private void failed( final RuntimeException e )
        {
            synchronized ( entry )
            {
                if ( entry.generation == this )
                {
                    entry.generation = null;
                }
            }
            if ( entry.sitemap == null )
            {
                entries.remove( key, entry );
                entry.ready.completeExceptionally( e );
            }
            else
            {
                LOG.warn( "Could not generate sitemap for {} {} {}", key.repositoryId(), key.branch(), key.params().sitePath(), e );
            }
        }
    }

    private static final class Entry
    {
        final boolean background;

        final AuthenticationInfo authInfo;

        final Source source;

        final CompletableFuture<Sitemap> ready = new CompletableFuture<>();

        volatile @Nullable Generation generation;

        volatile @Nullable Sitemap sitemap;

        volatile @Nullable Instant validUntil;

        volatile Instant lastAccess;

        Entry( final boolean background, final AuthenticationInfo authInfo, final Source source, final Instant now )
        {
            this.background = background;
            this.authInfo = authInfo;
            this.source = source;
            this.lastAccess = now;
        }

        boolean isExpired( final Instant now )
        {
            final Instant until = validUntil;
            return until != null && !now.isBefore( until );
        }
    }
}

package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.jr.ob.JSON;

import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.event.Event;
import com.enonic.xp.event.EventListener;
import com.enonic.xp.home.HomeDir;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.util.GenericValue;

/**
 * Generated sitemaps. A sitemap is served until a newer one of the same site replaces it, also when its content changed in the
 * meantime and across restarts: its files and a description of them are kept on disk.
 * <p>
 * Sitemaps are generated one at a time, in the order they are requested. There is no limit on how many generations are queued, but
 * when more than {@value #MAX_WAITING} requests are already waiting for sitemaps that are not generated yet, further such requests get
 * {@link SitemapBusyException}. Requests for generated sitemaps are always answered right away.
 * <p>
 * A sitemap whose URLs do not depend on the request (the site has a Base URL) is generated again in the background a few seconds after
 * its content changes, unless it was not requested for {@link #IDLE_TIMEOUT}. Other sitemaps, and all sitemaps kept from before a restart,
 * are generated again when they are next requested; the request gets the previous sitemap. A generation still running when the content
 * changes again is halted and started over.
 */
@Component(immediate = true, service = {SitemapCache.class, EventListener.class})
public final class SitemapCache
    implements EventListener
{
    static final int MAX_PER_SITE = 10;

    private static final Comparator<Map.Entry<Key, Entry>> EVICTION_ORDER =
        Comparator.<Map.Entry<Key, Entry>, Boolean>comparing( e -> !e.getKey().params().hasExplicitPort() )
            .thenComparing( e -> e.getValue().lastAccess );

    static final int MAX_WAITING = 10;

    static final Duration REGENERATION_DELAY = Duration.ofSeconds( 5 );

    static final Duration IDLE_TIMEOUT = Duration.ofHours( 1 );

    static final Duration FILE_RETENTION = Duration.ofMinutes( 1 );

    static final Duration STOP_TIMEOUT = Duration.ofSeconds( 30 );

    static final String ENTRY_FILE = "entry.json";

    private static final int FORMAT = 1;

    private static final Logger LOG = LoggerFactory.getLogger( SitemapCache.class );

    private static final SecureRandom RANDOM = new SecureRandom();

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
        } ), Clock.systemUTC(), REGENERATION_DELAY, FILE_RETENTION, HomeDir.get().toPath().resolve( "work" ).resolve( "cache" ).resolve( "com.enonic.app.sitemapxml" ) );
    }

    SitemapCache( final ScheduledExecutorService generator, final Clock clock, final Duration regenerationDelay,
                  final Duration fileRetention, final Path storage )
    {
        this.generator = generator;
        this.clock = clock;
        this.regenerationDelay = regenerationDelay;
        this.fileRetention = fileRetention;
        this.storage = storage;
        restore();
    }

    /**
     * Stops generating, and waits for a running generation to halt, so that a new instance finds no generation writing to disk.
     * Generated sitemaps stay on disk, to be served after a restart.
     */
    @Deactivate
    public void deactivate()
    {
        for ( final Entry entry : entries.values() )
        {
            final Generation generation = entry.generation;
            if ( generation != null )
            {
                generation.cancel();
            }
            entry.ready.completeExceptionally( new CancellationException( "Sitemaps stopped" ) );
        }
        entries.clear();
        generator.shutdownNow();
        try
        {
            if ( !generator.awaitTermination( STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS ) )
            {
                LOG.warn( "Sitemap generation still running after {}", STOP_TIMEOUT );
            }
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
        }
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
            final Entry entry = entries.computeIfAbsent( key, k -> new Entry( now ) );
            entry.lastAccess = now;
            entry.background = background;
            if ( entry.source == null )
            {
                entry.authInfo = authInfo;
                entry.source = source;
            }

            final Sitemap current = entry.sitemap;
            if ( current != null )
            {
                final Generation queued = entry.generation;
                if ( entry.isOutdated( now ) && ( queued == null || !queued.startsWithin( regenerationDelay ) ) )
                {
                    generate( key, entry, Duration.ZERO, false );
                }
                return current;
            }

            evictOverflow( key );
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

    /**
     * Generates a sitemap in the calling thread without keeping it: its files are deleted once requests are done reading them.
     *
     * @param authInfo the generation runs with these principals
     */
    public Sitemap uncached( final Key key, final AuthenticationInfo authInfo, final Source source )
    {
        try (Sitemap.Builder builder = Sitemap.builder( storage.resolve( uuid7( clock.millis() ) ), key.params() ))
        {
            ContextBuilder.create()
                .repositoryId( key.repositoryId() )
                .branch( key.branch() )
                .authInfo( authInfo )
                .build()
                .callWith( () -> source.write( builder::add, () -> false ) );
            final Sitemap sitemap = builder.build( null, clock.instant() );
            retire( sitemap );
            return sitemap;
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
        synchronized ( entry )
        {
            entry.changes++;
        }
        if ( entry.sitemap == null )
        {
            generate( key, entry, Duration.ZERO, true );
        }
        else if ( entry.generation != null ||
            entry.background && entry.source != null && !entry.lastAccess.plus( IDLE_TIMEOUT ).isBefore( clock.instant() ) )
        {
            generate( key, entry, regenerationDelay, true );
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
            if ( entry.source == null )
            {
                return;
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
            final Sitemap sitemap;
            synchronized ( entry )
            {
                entry.removed = true;
                final Generation generation = entry.generation;
                if ( generation != null )
                {
                    generation.cancel();
                    entry.generation = null;
                }
                sitemap = entry.sitemap;
            }
            entry.ready.completeExceptionally( new CancellationException( "Sitemap removed" ) );
            if ( sitemap != null )
            {
                retire( sitemap );
            }
            LOG.debug( "Removed sitemap {}", key );
        }
    }

    /**
     * Stops the sitemap from being restored after a restart, and deletes its files later: requests may still be reading them.
     */
    private void retire( final Sitemap sitemap )
    {
        deleteFile( sitemap.directory().resolve( ENTRY_FILE ) );
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
     * Writes the key and the description of the sitemap next to its files. The file appears at once and complete, so a sitemap
     * directory without it is an unfinished generation.
     */
    private static void persist( final Key key, final Entry entry, final Sitemap sitemap )
    {
        final Path file = sitemap.directory().resolve( ENTRY_FILE );
        final Path temporary = sitemap.directory().resolve( ENTRY_FILE + ".tmp" );
        try
        {
            final GenericValue.ObjectBuilder description = GenericValue.newObject()
                .put( "format", FORMAT )
                .put( "key", key.store() )
                .put( "background", entry.background )
                .put( "lastAccess", entry.lastAccess.toString() );
            final Instant validUntil = entry.validUntil;
            if ( validUntil != null )
            {
                description.put( "validUntil", validUntil.toString() );
            }
            description.put( "sitemap", sitemap.store() );
            try (OutputStream out = Files.newOutputStream( temporary ))
            {
                JSON.std.write( description.build().toRawJava(), out );
            }
            Files.move( temporary, file, StandardCopyOption.ATOMIC_MOVE );
        }
        catch ( IOException e )
        {
            LOG.warn( "Could not store sitemap {} on disk, it is served until restart", key, e );
            deleteFile( temporary );
        }
    }

    /**
     * Reads back the sitemaps kept on disk. Content may have changed since they were generated, so they are generated again when
     * requested. Files of unfinished generations are deleted, and of two sitemaps with the same key the older one: directories are
     * named by version 7 UUIDs, which sort by creation time.
     */
    private void restore()
    {
        if ( !Files.isDirectory( storage ) )
        {
            return;
        }
        final List<Path> directories;
        try (Stream<Path> list = Files.list( storage ))
        {
            directories = list.sorted().toList();
        }
        catch ( IOException e )
        {
            LOG.warn( "Could not read sitemaps in {}", storage, e );
            return;
        }

        for ( final Path directory : directories )
        {
            final Map.Entry<Key, Entry> restored = restore( directory );
            if ( restored == null )
            {
                Sitemap.delete( directory );
                continue;
            }
            final Entry older = entries.put( restored.getKey(), restored.getValue() );
            if ( older != null )
            {
                Objects.requireNonNull( older.sitemap ).delete();
            }
        }
        List.copyOf( entries.keySet() ).forEach( this::evictOverflow );
        LOG.debug( "Restored {} sitemaps", entries.size() );
    }

    private static Map.@Nullable Entry<Key, Entry> restore( final Path directory )
    {
        final Path file = directory.resolve( ENTRY_FILE );
        if ( !Files.isRegularFile( file ) )
        {
            return null;
        }
        try
        {
            final GenericValue description;
            try (InputStream in = Files.newInputStream( file ))
            {
                description = GenericValue.fromRawJava( JSON.std.mapFrom( in ) );
            }
            if ( description.property( "format" ).asInteger() != FORMAT )
            {
                return null;
            }
            final Key key = Key.restore( description.property( "key" ) );
            final Entry entry = new Entry( Instant.parse( description.property( "lastAccess" ).asString() ) );
            entry.background = description.property( "background" ).asBoolean();
            entry.validUntil =
                description.optional( "validUntil" ).map( validUntil -> Instant.parse( validUntil.asString() ) ).orElse( null );
            final Sitemap sitemap = Sitemap.restore( directory, description.property( "sitemap" ) );
            entry.sitemap = sitemap;
            entry.ready.complete( sitemap );
            return Map.entry( key, entry );
        }
        catch ( IOException | RuntimeException e )
        {
            LOG.debug( "Could not read sitemap in {}", directory, e );
            return null;
        }
    }

    /**
     * A version 7 UUID: its string form sorts by the given time.
     */
    static String uuid7( final long epochMilli )
    {
        final byte[] random = new byte[10];
        RANDOM.nextBytes( random );
        final long msb = epochMilli << 16 | ( random[0] & 0x0FL ) << 8 | random[1] & 0xFFL | 0x7000L;
        long lsb = 0;
        for ( int i = 2; i < random.length; i++ )
        {
            lsb = lsb << 8 | random[i] & 0xFFL;
        }
        return new UUID( msb, lsb & 0x3FFF_FFFF_FFFF_FFFFL | 0x8000_0000_0000_0000L ).toString();
    }

    private static void deleteFile( final Path file )
    {
        try
        {
            Files.deleteIfExists( file );
        }
        catch ( IOException e )
        {
            LOG.debug( "Could not delete {}", file, e );
        }
    }

    /**
     * Removes generated sitemaps of the site and principals of the given key beyond {@value #MAX_PER_SITE}: a site is requested
     * through a few hosts, ports and schemes, but they come from the request and can be made up. Sitemaps whose site URL names a port go first, then the
     * least recently used. Sitemaps that requests are waiting for are kept.
     */
    private void evictOverflow( final Key key )
    {
        final List<Map.Entry<Key, Entry>> site = entries.entrySet().stream().filter( e -> e.getKey().isSameSiteForSamePrincipals( key ) ).toList();
        site.stream()
            .filter( e -> e.getValue().sitemap != null )
            .sorted( EVICTION_ORDER )
            .limit( Math.max( 0, site.size() - MAX_PER_SITE ) )
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

        boolean isSameSiteForSamePrincipals( final Key other )
        {
            return repositoryId.equals( other.repositoryId ) && branch.equals( other.branch ) &&
                params.sitePath().equals( other.params.sitePath() ) && principals.equals( other.principals );
        }

        GenericValue store()
        {
            final GenericValue contentTypes = params.contentTypes()
                .stream()
                .map( setting -> GenericValue.newObject()
                    .put( "contentType", setting.contentType() )
                    .put( "changefreq", setting.changefreq() )
                    .put( "priority", setting.priority() )
                    .build() )
                .collect( GenericValue.listCollector() );
            return GenericValue.newObject()
                .put( "repository", repositoryId )
                .put( "branch", branch )
                .put( "principals", strings( new TreeSet<>( principals ) ) )
                .put( "sitePath", params.sitePath() )
                .put( "siteUrl", params.siteUrl() )
                .put( "contentTypes", contentTypes )
                .put( "ignorePatterns", strings( params.ignorePatterns() ) )
                .build();
        }

        static Key restore( final GenericValue key )
        {
            final List<ContentTypeSetting> contentTypes = key.property( "contentTypes" )
                .values()
                .stream()
                .map( setting -> new ContentTypeSetting( setting.property( "contentType" ).asString(),
                                                         setting.property( "changefreq" ).asString(),
                                                         setting.property( "priority" ).asString() ) )
                .toList();
            final SitemapParams params =
                new SitemapParams( key.property( "sitePath" ).asString(), key.property( "siteUrl" ).asString(), contentTypes,
                                   key.property( "ignorePatterns" ).toStringList() );
            return new Key( key.property( "repository" ).asString(), key.property( "branch" ).asString(),
                            Set.copyOf( key.property( "principals" ).toStringList() ), params );
        }

        private static GenericValue strings( final Collection<String> values )
        {
            return values.stream().map( GenericValue::stringValue ).collect( GenericValue.listCollector() );
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

        /**
         * Changes of the content the generation includes.
         */
        long changes;

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
                changes = entry.changes;
            }
            final Source source = entry.source;
            final AuthenticationInfo authInfo = entry.authInfo;
            try
            {
                if ( source == null || authInfo == null )
                {
                    throw new CancellationException();
                }
                final Instant validUntil;
                final Sitemap sitemap;
                try (Sitemap.Builder builder = Sitemap.builder( storage.resolve( uuid7( clock.millis() ) ), key.params() ))
                {
                    validUntil = ContextBuilder.create()
                        .repositoryId( key.repositoryId() )
                        .branch( key.branch() )
                        .authInfo( authInfo )
                        .build()
                        .callWith( () -> source.write( builder::add, () -> cancelled ) );
                    if ( cancelled )
                    {
                        throw new CancellationException();
                    }
                    sitemap = builder.build( entry.sitemap, clock.instant() );
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
                if ( cancelled || entry.removed )
                {
                    sitemap.delete();
                    return;
                }
                previous = entry.sitemap;
                entry.validUntil = validUntil;
                entry.sitemap = sitemap;
                entry.sitemapChanges = changes;
                persist( key, entry, sitemap );
            }
            entry.ready.complete( sitemap );
            if ( previous != null )
            {
                retire( previous );
            }
            evictOverflow( key );
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
        final CompletableFuture<Sitemap> ready = new CompletableFuture<>();

        /**
         * Source and principals of the latest request: entries restored from disk have none until requested.
         */
        volatile @Nullable Source source;

        volatile @Nullable AuthenticationInfo authInfo;

        volatile boolean background;

        volatile @Nullable Generation generation;

        volatile @Nullable Sitemap sitemap;

        volatile @Nullable Instant validUntil;

        volatile Instant lastAccess;

        /**
         * Number of changes of the content, and the number the sitemap includes: unknown for sitemaps restored from disk.
         */
        volatile long changes;

        volatile long sitemapChanges = -1;

        volatile boolean removed;

        Entry( final Instant now )
        {
            this.lastAccess = now;
        }

        boolean isOutdated( final Instant now )
        {
            final Instant until = validUntil;
            return sitemapChanges != changes || until != null && !now.isBefore( until );
        }
    }
}

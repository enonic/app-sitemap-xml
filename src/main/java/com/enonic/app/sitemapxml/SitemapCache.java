package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.event.Event;
import com.enonic.xp.event.EventListener;
import com.enonic.xp.home.HomeDir;
import com.enonic.xp.security.auth.AuthenticationInfo;

/**
 * Generated sitemaps. A sitemap is served until a newer one of the same site replaces it, also when its content changed in the
 * meantime and across restarts: its files and a description of them are kept on disk.
 * <p>
 * Sitemaps of a project are generated one at a time, in the order they are requested; different projects generate in parallel. There
 * is no limit on how many generations are queued, but when more than {@value #MAX_WAITING} requests are already waiting for sitemaps
 * that are not generated yet, further such requests get {@link SitemapBusyException}. Requests for generated sitemaps are always
 * answered right away.
 * <p>
 * A sitemap whose URLs do not depend on the request ("Base URL" override in the site configuration) is generated again in the background a few seconds after
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

    static final Duration GENERATOR_KEEP_ALIVE = Duration.ofMinutes( 1 );

    static final String ENTRY_FILE = "entry.json";

    private static final int FORMAT = 1;

    private static final Logger LOG = LoggerFactory.getLogger( SitemapCache.class );

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();

    private final Semaphore waiting = new Semaphore( MAX_WAITING );

    private final ScheduledExecutorService scheduler;

    private final Function<String, ExecutorService> generatorFactory;

    private final ConcurrentMap<String, ExecutorService> generators = new ConcurrentHashMap<>();

    private volatile boolean stopped;

    private final Clock clock;

    private final Duration regenerationDelay;

    private final Duration fileRetention;

    private final Path storage;

    public SitemapCache()
    {
        this( Executors.newSingleThreadScheduledExecutor( runnable -> {
            final Thread thread = new Thread( runnable, "sitemapxml-scheduler" );
            thread.setDaemon( true );
            return thread;
        } ), SitemapCache::newGenerator, Clock.systemUTC(), REGENERATION_DELAY, FILE_RETENTION, HomeDir.get().toPath().resolve( "work" ).resolve( "cache" ).resolve( "com.enonic.app.sitemapxml" ) );
    }

    SitemapCache( final ScheduledExecutorService scheduler, final Function<String, ExecutorService> generatorFactory, final Clock clock,
                  final Duration regenerationDelay, final Duration fileRetention, final Path storage )
    {
        this.scheduler = scheduler;
        this.generatorFactory = generatorFactory;
        this.clock = clock;
        this.regenerationDelay = regenerationDelay;
        this.fileRetention = fileRetention;
        this.storage = storage;
        restore();
    }

    /**
     * A single generator thread for the given repository: sitemaps of a project are generated one at a time, those of different
     * projects in parallel. The thread ends when idle.
     */
    static ExecutorService newGenerator( final String repositoryId )
    {
        final ThreadPoolExecutor executor =
            new ThreadPoolExecutor( 1, 1, GENERATOR_KEEP_ALIVE.toMillis(), TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                final Thread thread = new Thread( runnable, "sitemapxml-generator-" + repositoryId );
                thread.setDaemon( true );
                return thread;
            } );
        executor.allowCoreThreadTimeOut( true );
        return executor;
    }

    /**
     * Stops generating, and waits for running generations to halt, so that a new instance finds no generation writing to disk.
     * Generated sitemaps stay on disk, to be served after a restart.
     */
    @Deactivate
    public void deactivate()
    {
        stopped = true;
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
        scheduler.shutdownNow();
        generators.values().forEach( ExecutorService::shutdownNow );
        final long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
        try
        {
            for ( final ExecutorService executor : Stream.concat( Stream.of( scheduler ), generators.values().stream() )
                .collect( Collectors.toList() ) )
            {
                if ( !executor.awaitTermination( deadline - System.nanoTime(), TimeUnit.NANOSECONDS ) )
                {
                    LOG.warn( "Sitemap generation still running after {}", STOP_TIMEOUT );
                    return;
                }
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
            final Generation generation = new Generation( key, entry, delay );
            entry.generation = generation;
            try
            {
                if ( delay.isNegative() || delay.isZero() )
                {
                    generator( key ).execute( generation );
                }
                else
                {
                    generation.future = scheduler.schedule( generation::start, delay.toMillis(), TimeUnit.MILLISECONDS );
                }
            }
            catch ( RejectedExecutionException e )
            {
                entry.generation = null;
                entry.ready.completeExceptionally( e );
            }
        }
    }

    private ExecutorService generator( final Key key )
    {
        if ( stopped )
        {
            throw new RejectedExecutionException( "Sitemaps stopped" );
        }
        return generators.computeIfAbsent( key.repositoryId(), generatorFactory );
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
            scheduler.schedule( delete, fileRetention.toMillis(), TimeUnit.MILLISECONDS );
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
            final ObjectNode description = JsonNodeFactory.instance.objectNode();
            description.put( "format", FORMAT );
            description.set( "key", key.store() );
            description.put( "background", entry.background );
            description.put( "lastAccess", entry.lastAccess.toString() );
            final Instant validUntil = entry.validUntil;
            if ( validUntil != null )
            {
                description.put( "validUntil", validUntil.toString() );
            }
            description.set( "sitemap", sitemap.store() );
            MAPPER.writeValue( temporary.toFile(), description );
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
            directories = list.sorted().collect( Collectors.toList() );
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

    private static Map.Entry<Key, Entry> restore( final Path directory )
    {
        final Path file = directory.resolve( ENTRY_FILE );
        if ( !Files.isRegularFile( file ) )
        {
            return null;
        }
        try
        {
            final JsonNode description = MAPPER.readTree( file.toFile() );
            if ( description.required( "format" ).asInt() != FORMAT )
            {
                return null;
            }
            final Key key = Key.restore( description.required( "key" ) );
            final Entry entry = new Entry( Instant.parse( description.required( "lastAccess" ).asText() ) );
            entry.background = description.required( "background" ).asBoolean();
            final JsonNode validUntil = description.get( "validUntil" );
            entry.validUntil = validUntil == null ? null : Instant.parse( validUntil.asText() );
            final Sitemap sitemap = Sitemap.restore( directory, description.required( "sitemap" ) );
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
        final List<Map.Entry<Key, Entry>> site = entries.entrySet().stream().filter( e -> e.getKey().isSameSiteForSamePrincipals( key ) ).collect( Collectors.toList() );
        site.stream()
            .filter( e -> e.getValue().sitemap != null && e.getValue().ready.isDone() )
            .sorted( EVICTION_ORDER )
            .limit( Math.max( 0, site.size() - MAX_PER_SITE ) )
            .collect( Collectors.toList() )
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
            if ( e.getCause() instanceof RuntimeException )
            {
                throw (RuntimeException) e.getCause();
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
        Instant write( Consumer<SitemapEntry> sink, BooleanSupplier cancelled );
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

        public Set<String> principals()
        {
            return principals;
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

        boolean isSameSiteForSamePrincipals( final Key other )
        {
            return repositoryId.equals( other.repositoryId ) && branch.equals( other.branch ) &&
                params.sitePath().equals( other.params.sitePath() ) && principals.equals( other.principals );
        }

        ObjectNode store()
        {
            final ObjectNode key = JsonNodeFactory.instance.objectNode();
            key.put( "repository", repositoryId );
            key.put( "branch", branch );
            final ArrayNode principals = key.putArray( "principals" );
            new TreeSet<>( this.principals ).forEach( principals::add );
            key.put( "sitePath", params.sitePath() );
            key.put( "siteUrl", params.siteUrl() );
            final ArrayNode contentTypes = key.putArray( "contentTypes" );
            for ( final ContentTypeSetting setting : params.contentTypes() )
            {
                contentTypes.addObject()
                    .put( "contentType", setting.contentType() )
                    .put( "changefreq", setting.changefreq() )
                    .put( "priority", setting.priority() );
            }
            final ArrayNode ignorePatterns = key.putArray( "ignorePatterns" );
            params.ignorePatterns().forEach( ignorePatterns::add );
            key.put( "maxItems", params.maxItems() );
            return key;
        }

        static Key restore( final JsonNode key )
        {
            final List<ContentTypeSetting> contentTypes = new ArrayList<>();
            for ( final JsonNode setting : key.required( "contentTypes" ) )
            {
                contentTypes.add( new ContentTypeSetting( setting.required( "contentType" ).asText(),
                                                          setting.required( "changefreq" ).asText(),
                                                          setting.required( "priority" ).asText() ) );
            }
            final SitemapParams params =
                new SitemapParams( key.required( "sitePath" ).asText(), key.required( "siteUrl" ).asText(), contentTypes,
                                   strings( key.required( "ignorePatterns" ) ), key.required( "maxItems" ).asInt() );
            return new Key( key.required( "repository" ).asText(), key.required( "branch" ).asText(),
                            Set.copyOf( strings( key.required( "principals" ) ) ), params );
        }

        private static List<String> strings( final JsonNode array )
        {
            final List<String> strings = new ArrayList<>();
            for ( final JsonNode item : array )
            {
                strings.add( item.asText() );
            }
            return strings;
        }
    }

    private final class Generation
        implements Runnable
    {
        final Key key;

        final Entry entry;

        final long due;

        volatile ScheduledFuture<?> future;

        volatile boolean started;

        volatile boolean cancelled;

        /**
         * Changes of the content the generation includes.
         */
        long changes;

        Generation( final Key key, final Entry entry, final Duration delay )
        {
            this.key = key;
            this.entry = entry;
            this.due = System.nanoTime() + delay.toNanos();
        }

        boolean startsWithin( final Duration delay )
        {
            return due - System.nanoTime() <= delay.toNanos();
        }

        void start()
        {
            try
            {
                generator( key ).execute( this );
            }
            catch ( RejectedExecutionException e )
            {
                LOG.debug( "Generation of sitemap {} not started", key, e );
            }
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

        private void publish( final Sitemap sitemap, final Instant validUntil )
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
                if ( cancelled || entry.generation != this )
                {
                    LOG.debug( "Halted generation of sitemap {} failed", key, e );
                    return;
                }
                entry.generation = null;
                if ( entry.sitemap == null )
                {
                    entries.remove( key, entry );
                    entry.ready.completeExceptionally( e );
                    return;
                }
            }
            LOG.warn( "Could not generate sitemap for {} {} {}", key.repositoryId(), key.branch(), key.params().sitePath(), e );
        }
    }

    private static final class Entry
    {
        final CompletableFuture<Sitemap> ready = new CompletableFuture<>();

        /**
         * Source and principals of the latest request: entries restored from disk have none until requested.
         */
        volatile Source source;

        volatile AuthenticationInfo authInfo;

        volatile boolean background;

        volatile Generation generation;

        volatile Sitemap sitemap;

        volatile Instant validUntil;

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

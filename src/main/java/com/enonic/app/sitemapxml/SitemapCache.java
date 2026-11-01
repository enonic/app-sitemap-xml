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

import com.fasterxml.jackson.core.JsonGenerator;

import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.event.Event;
import com.enonic.xp.event.EventListener;
import com.enonic.xp.home.HomeDir;
import com.enonic.xp.security.auth.AuthenticationInfo;

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
    static final int MAX_ENTRIES = 100;

    static final int MAX_WAITING = 10;

    static final Duration REGENERATION_DELAY = Duration.ofSeconds( 5 );

    static final Duration IDLE_TIMEOUT = Duration.ofHours( 1 );

    static final Duration FILE_RETENTION = Duration.ofMinutes( 1 );

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
     * Stops generating. Generated sitemaps stay on disk, to be served after a restart.
     */
    @Deactivate
    public void deactivate()
    {
        generator.shutdownNow();
        entries.values().forEach( entry -> entry.ready.completeExceptionally( new CancellationException( "Sitemaps stopped" ) ) );
        entries.clear();
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
            try (JsonGenerator json = Sitemap.JSON.createGenerator( Files.newOutputStream( temporary ) ))
            {
                json.writeStartObject();
                json.writeNumberField( "format", FORMAT );
                json.writeFieldName( "key" );
                key.store( json );
                json.writeBooleanField( "background", entry.background );
                json.writeStringField( "lastAccess", entry.lastAccess.toString() );
                final Instant validUntil = entry.validUntil;
                if ( validUntil != null )
                {
                    json.writeStringField( "validUntil", validUntil.toString() );
                }
                json.writeFieldName( "sitemap" );
                sitemap.store( json );
                json.writeEndObject();
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
        evictOverflow();
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
            final Map<String, ?> description = Json.readObject( file );
            if ( Json.number( description, "format" ) != FORMAT )
            {
                return null;
            }
            final Key key = Key.restore( Json.object( description.get( "key" ) ) );
            final Entry entry = new Entry( Instant.parse( Json.string( description, "lastAccess" ) ) );
            entry.background = Json.bool( description, "background" );
            final String validUntil = Json.optionalString( description, "validUntil" );
            entry.validUntil = validUntil == null ? null : Instant.parse( validUntil );
            final Sitemap sitemap = Sitemap.restore( directory, Json.object( description.get( "sitemap" ) ) );
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

        void store( final JsonGenerator json )
            throws IOException
        {
            json.writeStartObject();
            json.writeStringField( "repository", repositoryId );
            json.writeStringField( "branch", branch );
            json.writeArrayFieldStart( "principals" );
            for ( final String principal : new TreeSet<>( principals ) )
            {
                json.writeString( principal );
            }
            json.writeEndArray();
            json.writeStringField( "sitePath", params.sitePath() );
            json.writeStringField( "siteUrl", params.siteUrl() );
            json.writeArrayFieldStart( "contentTypes" );
            for ( final ContentTypeSetting setting : params.contentTypes() )
            {
                json.writeStartObject();
                json.writeStringField( "contentType", setting.contentType() );
                json.writeStringField( "changefreq", setting.changefreq() );
                json.writeStringField( "priority", setting.priority() );
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeArrayFieldStart( "ignorePatterns" );
            for ( final String pattern : params.ignorePatterns() )
            {
                json.writeString( pattern );
            }
            json.writeEndArray();
            json.writeEndObject();
        }

        static Key restore( final Map<String, ?> key )
        {
            final List<ContentTypeSetting> contentTypes = new ArrayList<>();
            for ( final Object item : Json.list( key, "contentTypes" ) )
            {
                final Map<String, ?> setting = Json.object( item );
                contentTypes.add( new ContentTypeSetting( Json.string( setting, "contentType" ), Json.string( setting, "changefreq" ),
                                                          Json.string( setting, "priority" ) ) );
            }
            final SitemapParams params =
                new SitemapParams( Json.string( key, "sitePath" ), Json.string( key, "siteUrl" ), contentTypes,
                                   Json.strings( key, "ignorePatterns" ) );
            return new Key( Json.string( key, "repository" ), Json.string( key, "branch" ),
                            Set.copyOf( Json.strings( key, "principals" ) ), params );
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
                    sitemap = builder.build();
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

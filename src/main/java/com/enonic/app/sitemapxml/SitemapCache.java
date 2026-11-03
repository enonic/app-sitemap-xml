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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.enonic.xp.app.ApplicationKey;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.Context;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.event.Event;
import com.enonic.xp.event.EventListener;
import com.enonic.xp.home.HomeDir;
import com.enonic.xp.project.ProjectService;
import com.enonic.xp.security.PrincipalKey;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.web.vhost.VirtualHostService;

/**
 * Generated sitemaps of this cluster node. A sitemap is served until a newer one of the same key replaces it, also when its content
 * changed in the meantime and across restarts: its files and a description of them are kept on disk.
 * <p>
 * Sitemaps of a project are generated one at a time; different projects generate in parallel. There is no limit on how many generations
 * are queued, but when more than {@value #MAX_WAITING} requests are already waiting for sitemaps that are not generated yet, further such
 * requests get {@link SitemapBusyException}. Requests for generated sitemaps are always answered right away.
 * <p>
 * Public sitemaps (those of anonymous requests) are generated again in the background a few seconds after content that may change them
 * changed. A change while one is generated starts one more generation once it is done. Each generation first checks whether any content
 * changed since the previous one: if none did, nothing is read. Sitemaps of logged-in users are generated again on their next request,
 * which gets the previous sitemap, and are deleted after {@link #USER_RETENTION} without a request.
 */
@Component(immediate = true, service = {SitemapCache.class, EventListener.class})
public final class SitemapCache
    implements EventListener
{
    static final ApplicationKey APPLICATION = ApplicationKey.from( "com.enonic.app.sitemapxml" );

    /**
     * Sitemaps of logged-in users kept per site and principals: their site URLs come from the request.
     */
    static final int MAX_PER_SITE = 10;

    static final int MAX_WAITING = 10;

    static final Duration REGENERATION_DELAY = Duration.ofSeconds( 5 );

    static final Duration USER_RETENTION = Duration.ofDays( 1 );

    static final Duration CLEANUP_INTERVAL = Duration.ofHours( 1 );

    static final Duration FILE_RETENTION = Duration.ofMinutes( 1 );

    static final Duration STOP_TIMEOUT = Duration.ofSeconds( 30 );

    static final Duration GENERATOR_KEEP_ALIVE = Duration.ofMinutes( 1 );

    /**
     * Contents changed at most this long before a generation started count as changed for the next one: node times are stamped by the
     * clocks of the cluster nodes.
     */
    static final Duration CUTOFF_MARGIN = Duration.ofSeconds( 2 );

    static final String ENTRY_FILE = "entry.json";

    private static final int FORMAT = 2;

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

    private final Function<Key, Source> sources;

    private final Predicate<Key> current;

    @Activate
    public SitemapCache( @Reference final ContentService contentService, @Reference final ProjectService projectService,
                         @Reference final VirtualHostService virtualHostService )
    {
        this( Executors.newSingleThreadScheduledExecutor( runnable -> {
                  final Thread thread = new Thread( runnable, "sitemapxml-scheduler" );
                  thread.setDaemon( true );
                  return thread;
              } ), SitemapCache::newGenerator, Clock.systemUTC(), REGENERATION_DELAY, FILE_RETENTION,
              HomeDir.get().toPath().resolve( "work" ).resolve( "cache" ).resolve( "com.enonic.app.sitemapxml" ),
              query( contentService, Clock.systemUTC() ), new SiteUrls( contentService, projectService, virtualHostService )::isCurrent );
    }

    /**
     * @param sources the source of the contents of a key's sitemap, for one generation
     * @param current whether a public sitemap may still be requested, see {@link SiteUrls#isCurrent}
     */
    SitemapCache( final ScheduledExecutorService scheduler, final Function<String, ExecutorService> generatorFactory, final Clock clock,
                  final Duration regenerationDelay, final Duration fileRetention, final Path storage, final Function<Key, Source> sources,
                  final Predicate<Key> current )
    {
        this.scheduler = scheduler;
        this.generatorFactory = generatorFactory;
        this.clock = clock;
        this.regenerationDelay = regenerationDelay;
        this.fileRetention = fileRetention;
        this.storage = storage;
        this.sources = sources;
        this.current = current;
        restore();
        try
        {
            scheduler.scheduleWithFixedDelay( this::deleteUnused, CLEANUP_INTERVAL.toMillis(), CLEANUP_INTERVAL.toMillis(),
                                              TimeUnit.MILLISECONDS );
        }
        catch ( RejectedExecutionException e )
        {
            LOG.debug( "Sitemaps of logged-in users are not cleaned up", e );
        }
    }

    /**
     * The contents of a key's sitemap, from the content API.
     */
    static Function<Key, Source> query( final ContentService contentService, final Clock clock )
    {
        return key -> new QuerySource( new SitemapQuery( contentService, key.params(), clock.instant() ) );
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
            for ( final ExecutorService executor : Stream.concat( Stream.of( scheduler ), generators.values().stream() ).toList() )
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
     * Returns the sitemap of the given key, generating it first when there is none yet. A sitemap of a logged-in user that content
     * changed since is generated again, and the previous one returned meanwhile.
     *
     * @throws SitemapBusyException when the sitemap is not generated yet and too many requests are already waiting
     */
    public Sitemap get( final Key key )
    {
        final Instant now = clock.instant();
        final Entry entry = entries.computeIfAbsent( key, k -> new Entry( now ) );
        entry.lastAccess = now;

        final Sitemap current = entry.sitemap;
        if ( current != null )
        {
            final Generation queued = entry.generation;
            if ( entry.isOutdated( now ) && ( queued == null || !queued.startsWithin( regenerationDelay ) ) )
            {
                generate( key, entry, Duration.ZERO );
            }
            return current;
        }

        evictOverflow( key );
        generate( key, entry, Duration.ZERO );
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
        // changed paths per repository and branch, or per repository
        final Map<List<String>, Set<String>> changed = new LinkedHashMap<>();
        if ( type.startsWith( "node." ) )
        {
            final Object data = event.getData().get( "nodes" );
            for ( final Object node : data instanceof List<?> nodes ? nodes : List.of( event.getData() ) )
            {
                if ( node instanceof Map<?, ?> map && map.get( "repo" ) instanceof String repo &&
                    map.get( "branch" ) instanceof String branch )
                {
                    final Set<String> paths = changed.computeIfAbsent( List.of( repo, branch ), scope -> new LinkedHashSet<>() );
                    // the path before and after a move, or a push of a moved node
                    for ( final String field : List.of( "path", "newPath", "currentTargetPath" ) )
                    {
                        if ( map.get( field ) instanceof String path )
                        {
                            paths.add( changedPath( path ) );
                        }
                    }
                    if ( paths.isEmpty() )
                    {
                        paths.add( "/" );
                    }
                }
            }
        }
        else if ( type.startsWith( "repository." ) && event.getData().get( "id" ) instanceof String repo )
        {
            changed.put( List.of( repo ), Set.of( "/" ) );
        }
        changed.forEach( ( scope, paths ) -> entries.forEach( ( key, entry ) -> {
            if ( key.repositoryId().equals( scope.get( 0 ) ) && ( scope.size() == 1 || key.branch().equals( scope.get( 1 ) ) ) &&
                paths.stream().anyMatch( path -> affects( path, key ) ) )
            {
                contentChanged( key, entry );
            }
        } ) );
    }

    /**
     * The changed node path as changes are matched: at most two levels, as {@code /content/<name>}. A site deeper than that counts as
     * changed when anything under the same {@code /content/<name>} changes.
     */
    static String changedPath( final String path )
    {
        int end = path.indexOf( '/', 1 );
        end = end < 0 ? -1 : path.indexOf( '/', end + 1 );
        return end < 0 ? path : path.substring( 0, end );
    }

    /**
     * Whether a change at the given path, as {@link #changedPath} keeps it, may change the sitemap of the key: it is in the site (or the
     * project's content), or above it, or {@code /} for a change of the whole repository.
     */
    static boolean affects( final String path, final Key key )
    {
        final String root = "/content" + key.params().sitePath();
        return path.equals( "/" ) || path.equals( root ) || path.startsWith( root + "/" ) || root.startsWith( path + "/" );
    }

    private void contentChanged( final Key key, final Entry entry )
    {
        synchronized ( entry )
        {
            entry.changes++;
        }
        // a generation running now is followed by another one once it is done, see Generation#done
        if ( key.isPublic() && entry.sitemap != null )
        {
            generate( key, entry, regenerationDelay );
        }
    }

    /**
     * Queues a generation of the entry after the given delay, unless one is already running, or queued to start by then.
     */
    private void generate( final Key key, final Entry entry, final Duration delay )
    {
        synchronized ( entry )
        {
            if ( entry.removed )
            {
                return;
            }
            final Generation current = entry.generation;
            if ( current != null )
            {
                if ( current.started || current.startsWithin( delay ) )
                {
                    return;
                }
                current.cancel();
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
     * Deletes the sitemaps of logged-in users not requested for {@link #USER_RETENTION}.
     */
    void deleteUnused()
    {
        final Instant before = clock.instant().minus( USER_RETENTION );
        entries.forEach( ( key, entry ) -> {
            if ( !key.isPublic() && entry.ready.isDone() && entry.generation == null && entry.lastAccess.isBefore( before ) )
            {
                remove( key, entry );
            }
        } );
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
            description.put( "lastAccess", entry.lastAccess.toString() );
            final Instant validUntil = entry.validUntil;
            if ( validUntil != null )
            {
                description.put( "validUntil", validUntil.toString() );
            }
            description.set( "sitemap", sitemap.store() );
            MAPPER.writeValue( temporary.toFile(), description );
            Files.move( temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING );
        }
        catch ( IOException e )
        {
            LOG.warn( "Could not store sitemap {} on disk, it is served until restart", key, e );
            deleteFile( temporary );
        }
    }

    /**
     * Reads back the sitemaps kept on disk. Content may have changed since they were generated: public ones are generated again in the
     * background, others when requested. Files of unfinished generations and of earlier versions of the app are deleted, and of two
     * sitemaps with the same key the older one: directories are named by version 7 UUIDs, which sort by creation time.
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
        entries.forEach( ( key, entry ) -> {
            if ( key.isPublic() )
            {
                generate( key, entry, regenerationDelay );
            }
        } );
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
     * Removes sitemaps of logged-in users of the site and principals of the given key beyond {@value #MAX_PER_SITE}: their site URLs
     * come from the request and can be made up. Sitemaps whose site URL names a port go first, then the least recently used. Sitemaps
     * that requests are waiting for are kept.
     */
    private void evictOverflow( final Key key )
    {
        if ( key.isPublic() )
        {
            return;
        }
        final List<Map.Entry<Key, Entry>> site =
            entries.entrySet().stream().filter( e -> e.getKey().isSameSiteForSamePrincipals( key ) ).toList();
        site.stream()
            .filter( e -> e.getValue().sitemap != null && e.getValue().ready.isDone() )
            .sorted( java.util.Comparator.<Map.Entry<Key, Entry>, Boolean>comparing( e -> !e.getKey().params().hasExplicitPort() )
                         .thenComparing( e -> e.getValue().lastAccess ) )
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

    private static AuthenticationInfo authInfo( final Key key )
    {
        return AuthenticationInfo.copyOf( AuthenticationInfo.unAuthenticated() )
            .principals( key.principals().stream().map( PrincipalKey::from ).toList() )
            .build();
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

        /**
         * Counts the contents of the sitemap, and those of them changed since the given time; null when the source can't.
         */
        default SitemapQuery.Count count( final Instant since )
        {
            return null;
        }

        /**
         * The contents of the sitemap counted before {@link #write} read any, as {@link #count} counts them; -1 when unknown.
         */
        default long total()
        {
            return -1;
        }
    }

    record QuerySource(SitemapQuery query)
        implements Source
    {
        @Override
        public Instant write( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
        {
            return query.execute( sink, cancelled );
        }

        @Override
        public SitemapQuery.Count count( final Instant since )
        {
            return query.count( since );
        }

        @Override
        public long total()
        {
            return query.total();
        }
    }

    public record Key(String repositoryId, String branch, Set<String> principals, SitemapParams params)
    {
        static final String ANONYMOUS = "user:system:anonymous";

        public Key
        {
            Objects.requireNonNull( repositoryId );
            Objects.requireNonNull( branch );
            principals = Set.copyOf( principals );
            Objects.requireNonNull( params );
        }

        /**
         * Whether the sitemap is that of anonymous requests: it is generated again in the background when content changes.
         */
        public boolean isPublic()
        {
            return principals.contains( ANONYMOUS );
        }

        @Override
        public String toString()
        {
            return repositoryId + " " + branch + " " + params.sitePath() + " " + params.format().name().toLowerCase() +
                ( params.siteUrl().isEmpty() ? "" : " " + params.siteUrl() );
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
            key.put( "format", params.format().name().toLowerCase() );
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
                                   strings( key.required( "ignorePatterns" ) ), key.required( "maxItems" ).asInt(),
                                   SitemapParams.Format.valueOf( key.required( "format" ).asText().toUpperCase() ) );
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
            try
            {
                final Sitemap previous = entry.sitemap;
                if ( key.isPublic() && previous != null && !current.test( key ) )
                {
                    LOG.debug( "Sitemap {} is no longer configured", key );
                    remove( key, entry );
                    return;
                }
                final Instant startedAt = clock.instant();
                final Context context =
                    ContextBuilder.create().repositoryId( key.repositoryId() ).branch( key.branch() ).authInfo( authInfo( key ) ).build();
                final Source source = sources.apply( key );
                final Instant validUntil = entry.validUntil;
                final boolean scheduled = validUntil != null && !startedAt.isBefore( validUntil );
                if ( previous != null && previous.count() >= 0 && previous.cutoff() != null && !scheduled )
                {
                    final SitemapQuery.Count count = context.callWith( () -> source.count( previous.cutoff() ) );
                    if ( count != null && count.changed() == 0 && count.count() == previous.count() )
                    {
                        LOG.debug( "Sitemap {}: no contents changed", key );
                        unchanged( previous.withCount( previous.count(), startedAt.minus( CUTOFF_MARGIN ) ) );
                        return;
                    }
                }
                final Instant nextChange;
                final Sitemap sitemap;
                try (Sitemap.Builder builder = Sitemap.builder( storage.resolve( uuid7( clock.millis() ) ), key.params() ))
                {
                    nextChange = context.callWith( () -> source.write( builder::add, () -> cancelled ) );
                    if ( cancelled )
                    {
                        throw new CancellationException();
                    }
                    final Sitemap built = builder.build( previous, clock.instant() );
                    final long total = source.total();
                    sitemap = total >= 0 ? built.withCount( total, startedAt.minus( CUTOFF_MARGIN ) ) : built;
                }
                publish( sitemap, nextChange );
            }
            catch ( CancellationException e )
            {
                LOG.debug( "Generation of sitemap {} halted", key );
                done();
            }
            catch ( RuntimeException e )
            {
                failed( e );
            }
        }

        /**
         * Keeps the sitemap, with the time contents count as changed since moved to this generation: no content changed before it.
         */
        private void unchanged( final Sitemap sitemap )
        {
            synchronized ( entry )
            {
                if ( entry.generation == this )
                {
                    entry.generation = null;
                }
                if ( cancelled || entry.removed )
                {
                    return;
                }
                entry.sitemap = sitemap;
                entry.sitemapChanges = changes;
                persist( key, entry, sitemap );
            }
            followUp();
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

            if ( key.isPublic() && validUntil != null )
            {
                generate( key, entry, Duration.between( clock.instant(), validUntil ) );
            }
            followUp();
        }

        private void done()
        {
            synchronized ( entry )
            {
                if ( entry.generation == this )
                {
                    entry.generation = null;
                }
            }
        }

        /**
         * Generates a public sitemap again when content changed while it was generated.
         */
        private void followUp()
        {
            if ( key.isPublic() && entry.changes != changes )
            {
                generate( key, entry, regenerationDelay );
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
            LOG.warn( "Could not generate sitemap {}", key, e );
        }
    }

    private static final class Entry
    {
        final CompletableFuture<Sitemap> ready = new CompletableFuture<>();

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

package com.enonic.app.sitemapxml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.event.Event;
import com.enonic.xp.event.EventListener;
import com.enonic.xp.home.HomeDir;
import com.enonic.xp.node.NodeService;
import com.enonic.xp.repository.RepositoryService;
import com.enonic.xp.security.PrincipalKey;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.shared.SharedMap;
import com.enonic.xp.shared.SharedMapService;
import com.enonic.xp.task.SubmitLocalTaskParams;
import com.enonic.xp.task.TaskService;

/**
 * Generated sitemaps, kept in a {@link SitemapStore} shared by all cluster nodes. A sitemap is served until a newer one of the same key
 * replaces it, also when its content changed in the meantime and across restarts. Each node keeps what it read from the store in memory,
 * and reads it again when another node stores a newer version.
 * <p>
 * Sitemaps are generated in local tasks, so they show in XP's task list. Sitemaps of a project are generated one at a time on a node;
 * different projects in parallel. Before generating, a node claims the sitemap in the store; a sitemap another node is generating is not
 * generated again, unless that generation is older than {@link #STALE_GENERATION}. When more than {@value #MAX_WAITING} requests are
 * already waiting for sitemaps that were never generated, further such requests get {@link SitemapBusyException}. Requests for generated
 * sitemaps are always answered right away.
 * <p>
 * When content changes, only the node where it changed reacts: it marks the sitemaps of the project as obsolete, and generates again those
 * whose URLs do not depend on the request ("Base URL", or Guillotine) a few seconds later, unless they were not requested for
 * {@link #IDLE_TIMEOUT}. Other sitemaps are generated again on their next request, by whichever node gets it. A generation still running
 * when the content changes again is halted and started over. Sitemaps generated before the cluster started are generated again on their
 * next request, since content may have changed while no node was running.
 */
@Component(immediate = true, service = {SitemapCache.class, EventListener.class})
public final class SitemapCache
    implements EventListener
{
    static final int MAX_PER_SITE = 10;

    static final int MAX_WAITING = 10;

    static final Duration REGENERATION_DELAY = Duration.ofSeconds( 5 );

    static final Duration IDLE_TIMEOUT = Duration.ofHours( 1 );

    static final Duration STALE_GENERATION = Duration.ofMinutes( 10 );

    static final Duration TOUCH_INTERVAL = Duration.ofMinutes( 10 );

    static final Duration FILE_RETENTION = Duration.ofMinutes( 1 );

    static final Duration STOP_TIMEOUT = Duration.ofSeconds( 30 );

    static final String TASK_PREFIX = "com.enonic.app.sitemapxml:";

    private static final Comparator<SitemapStore.Stored> EVICTION_ORDER =
        Comparator.<SitemapStore.Stored, Boolean>comparing( stored -> !stored.key().params().hasExplicitPort() )
            .thenComparing( SitemapStore.Stored::lastAccess );

    private static final Logger LOG = LoggerFactory.getLogger( SitemapCache.class );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Key> keys = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Semaphore> projects = new ConcurrentHashMap<>();

    private final Semaphore waiting = new Semaphore( MAX_WAITING );

    private final Set<Generation> running = ConcurrentHashMap.newKeySet();

    private final SitemapStore store;

    private final Function<Key, Source> sources;

    private final Tasks tasks;

    private final ScheduledExecutorService scheduler;

    private final Clock clock;

    private final Instant clusterStarted;

    private final Duration regenerationDelay;

    private final Duration fileRetention;

    private final Path temp;

    private final String member = UUID.randomUUID().toString();

    private volatile boolean stopped;

    @Activate
    public SitemapCache( @Reference final NodeService nodeService, @Reference final RepositoryService repositoryService,
                         @Reference final ContentService contentService, @Reference final TaskService taskService,
                         @Reference final SharedMapService sharedMapService )
    {
        this( new RepositorySitemapStore( nodeService, repositoryService ), query( contentService ), Tasks.of( taskService ),
              Executors.newSingleThreadScheduledExecutor( runnable -> {
                  final Thread thread = new Thread( runnable, "sitemapxml-scheduler" );
                  thread.setDaemon( true );
                  return thread;
              } ), Clock.systemUTC(), clusterStarted( sharedMapService ), REGENERATION_DELAY, FILE_RETENTION,
              HomeDir.get().toPath().resolve( "work" ).resolve( "cache" ).resolve( "com.enonic.app.sitemapxml" ) );
    }

    private static Function<Key, Source> query( final ContentService contentService )
    {
        return key -> ( sink, cancelled ) -> new SitemapQuery( contentService, key.params(), Instant.now() ).execute( sink, cancelled );
    }

    SitemapCache( final SitemapStore store, final Function<Key, Source> sources, final Tasks tasks,
                  final ScheduledExecutorService scheduler, final Clock clock, final Instant clusterStarted,
                  final Duration regenerationDelay, final Duration fileRetention, final Path temp )
    {
        this.store = store;
        this.sources = sources;
        this.tasks = tasks;
        this.scheduler = scheduler;
        this.clock = clock;
        this.clusterStarted = clusterStarted;
        this.regenerationDelay = regenerationDelay;
        this.fileRetention = fileRetention;
        this.temp = temp;
        Sitemap.delete( temp );
    }

    /**
     * When the first node of the cluster that is running now started: sitemaps generated before were generated while changes may have
     * gone unnoticed. Kept in a shared map, which lives as long as any cluster node does.
     */
    private static Instant clusterStarted( final SharedMapService sharedMapService )
    {
        final String now = Instant.now().toString();
        final SharedMap<String, String> map = sharedMapService.getSharedMap( "com.enonic.app.sitemapxml" );
        final String started = map.modify( "started", value -> value != null ? value : now );
        return Instant.parse( started != null ? started : now );
    }

    /**
     * Stops generating, and waits for running generations to halt. Generated sitemaps stay in the store.
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
        scheduler.shutdownNow();
        final long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
        try
        {
            for ( final Generation generation : List.copyOf( running ) )
            {
                if ( !generation.finished.await( deadline - System.nanoTime(), TimeUnit.NANOSECONDS ) )
                {
                    LOG.warn( "Sitemap generation still running after {}", STOP_TIMEOUT );
                    break;
                }
            }
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
        }
        entries.clear();
        keys.clear();
        Sitemap.delete( temp );
    }

    /**
     * Returns the sitemap of the given key, generating it first when it was never generated.
     *
     * @param background whether the sitemap may be generated again in the background: its URLs do not depend on the request
     * @throws SitemapBusyException when the sitemap was never generated and too many requests are already waiting
     */
    public Sitemap get( final Key key, final boolean background )
    {
        while ( true )
        {
            final Instant now = clock.instant();
            final Entry entry = entry( key );
            entry.background = background;
            final SitemapStore.Stored stored = current( key, entry );
            if ( stored != null && stored.sitemap() != null )
            {
                touch( entry, stored, now );
                if ( shouldRegenerate( entry, stored, now ) )
                {
                    generate( key, entry, Duration.ZERO, false );
                }
                return stored.sitemap();
            }

            if ( stored == null || stored.generating() == null ||
                !stored.generating().since().plus( STALE_GENERATION ).isAfter( now ) )
            {
                generate( key, entry, Duration.ZERO, false );
            }
            if ( !waiting.tryAcquire() )
            {
                throw new SitemapBusyException();
            }
            try
            {
                final Sitemap sitemap = await( entry );
                if ( sitemap != null )
                {
                    return sitemap;
                }
            }
            finally
            {
                waiting.release();
            }
        }
    }

    /**
     * Generates a sitemap in the calling thread without storing it: its files are deleted once requests are done reading them.
     *
     * @param authInfo the generation runs with these principals
     */
    public Sitemap uncached( final Key key, final AuthenticationInfo authInfo, final Source source )
    {
        try (Sitemap.Builder builder = Sitemap.builder( temp.resolve( UUID.randomUUID().toString() ), key.params() ))
        {
            ContextBuilder.create()
                .repositoryId( key.repositoryId() )
                .branch( key.branch() )
                .authInfo( authInfo )
                .build()
                .callWith( () -> source.write( builder::add, () -> false ) );
            final Sitemap sitemap = builder.build( null, clock.instant() );
            try
            {
                final Runnable delete = sitemap::delete;
                scheduler.schedule( delete, fileRetention.toMillis(), TimeUnit.MILLISECONDS );
            }
            catch ( RejectedExecutionException e )
            {
                sitemap.delete();
            }
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

    String member()
    {
        return member;
    }

    @Override
    public void onEvent( final Event event )
    {
        final String type = event.getType();
        final Set<List<String>> changed = new LinkedHashSet<>();
        if ( type.startsWith( "node." ) )
        {
            if ( event.getData().get( "nodes" ) instanceof List<?> nodes )
            {
                for ( final Object node : nodes )
                {
                    if ( node instanceof Map<?, ?> map && map.get( "repo" ) instanceof String repo &&
                        map.get( "branch" ) instanceof String branch )
                    {
                        if ( repo.equals( RepositorySitemapStore.REPOSITORY.toString() ) )
                        {
                            if ( !event.isLocalOrigin() && map.get( "path" ) instanceof String path )
                            {
                                storedElsewhere( path.substring( path.lastIndexOf( '/' ) + 1 ), type );
                            }
                        }
                        else
                        {
                            changed.add( List.of( repo, branch ) );
                        }
                    }
                }
            }
        }
        else if ( type.startsWith( "repository." ) && event.getData().get( "id" ) instanceof String repo &&
            !repo.equals( RepositorySitemapStore.REPOSITORY.toString() ) )
        {
            changed.add( List.of( repo ) );
        }
        final Instant now = clock.instant();
        changed.forEach(
            change -> contentChanged( change.get( 0 ), change.size() > 1 ? change.get( 1 ) : null, event.isLocalOrigin(), now ) );
    }

    private Entry entry( final Key key )
    {
        return entries.computeIfAbsent( key, k -> {
            keys.put( k.id(), k );
            return new Entry();
        } );
    }

    /**
     * The stored sitemap of the key as this node knows it, read from the store the first time and after another node stored it.
     */
    private SitemapStore.@Nullable Stored current( final Key key, final Entry entry )
    {
        if ( entry.loaded )
        {
            return entry.stored;
        }
        synchronized ( entry )
        {
            if ( !entry.loaded )
            {
                final SitemapStore.Stored stored = store.load( key.id() );
                entry.stored = stored;
                entry.sitemapChanges = entry.changes;
                entry.idleWhenChanged = false;
                entry.loaded = true;
                if ( stored != null && stored.sitemap() != null )
                {
                    entry.ready.complete( stored.sitemap() );
                }
            }
            return entry.stored;
        }
    }

    /**
     * Whether a request for the stored sitemap should start generating it again. A sitemap generated again in the background is left to
     * the node where its content changed, unless it was generated before the cluster started, was not requested for
     * {@link #IDLE_TIMEOUT} when its content changed, or that node failed to generate it.
     */
    private boolean shouldRegenerate( final Entry entry, final SitemapStore.Stored stored, final Instant now )
    {
        final Generation queued = entry.generation;
        if ( queued != null && queued.startsWithin( regenerationDelay ) )
        {
            return false;
        }
        final Instant validUntil = stored.validUntil();
        final Instant generatedAt = stored.generatedAt();
        if ( validUntil != null && !now.isBefore( validUntil ) || generatedAt != null && generatedAt.isBefore( clusterStarted ) )
        {
            return true;
        }
        final Instant obsolete = stored.obsolete();
        if ( stored.background() )
        {
            return entry.idleWhenChanged && entry.sitemapChanges != entry.changes || obsolete != null &&
                ( !stored.lastAccess().plus( IDLE_TIMEOUT ).isAfter( obsolete ) ||
                    obsolete.plus( regenerationDelay ).plus( STALE_GENERATION ).isBefore( now ) );
        }
        return obsolete != null || entry.sitemapChanges != entry.changes;
    }

    private static Instant lastAccess( final Entry entry )
    {
        final SitemapStore.Stored stored = entry.stored;
        final Instant accessed = stored == null ? Instant.EPOCH : stored.lastAccess();
        final Instant requested = entry.requested;
        return requested != null && requested.isAfter( accessed ) ? requested : accessed;
    }

    private void touch( final Entry entry, final SitemapStore.Stored stored, final Instant now )
    {
        entry.requested = now;
        final Instant touched = entry.touched;
        if ( touched != null && touched.plus( TOUCH_INTERVAL ).isAfter( now ) )
        {
            return;
        }
        entry.touched = now;
        execute( () -> store.touch( stored, now ) );
    }

    /**
     * Another node stored, replaced or deleted a sitemap: read it again when it is next requested, or now if requests are waiting for it.
     */
    private void storedElsewhere( final String id, final String type )
    {
        final Key key = keys.get( id );
        final Entry entry = key == null ? null : entries.get( key );
        if ( entry == null )
        {
            return;
        }
        if ( type.equals( "node.deleted" ) )
        {
            remove( key, entry );
            return;
        }
        entry.loaded = false;
        if ( !entry.ready.isDone() )
        {
            execute( () -> current( key, entry ) );
        }
    }

    /**
     * Content changed in the repository, and branch if not null. All nodes take note for the sitemaps they know; the node where it changed
     * marks all sitemaps of the project as obsolete in the store and generates those in the background that are generated so.
     */
    private void contentChanged( final String repositoryId, final @Nullable String branch, final boolean localOrigin,
                                 final Instant changedAt )
    {
        final Set<Key> restarted = ConcurrentHashMap.newKeySet();
        entries.forEach( ( key, entry ) -> {
            if ( key.repositoryId().equals( repositoryId ) && ( branch == null || key.branch().equals( branch ) ) )
            {
                synchronized ( entry )
                {
                    entry.changes++;
                    entry.idleWhenChanged |= lastAccess( entry ).plus( IDLE_TIMEOUT ).isBefore( changedAt );
                }
                if ( entry.generation != null )
                {
                    generate( key, entry, regenerationDelay, true );
                    restarted.add( key );
                }
            }
        } );
        if ( localOrigin )
        {
            execute( () -> {
                for ( final SitemapStore.Stored stored : store.find( repositoryId, branch ) )
                {
                    final Instant startedAt = stored.startedAt();
                    if ( startedAt != null && startedAt.isAfter( changedAt ) )
                    {
                        continue;
                    }
                    store.markObsolete( stored, changedAt );
                    if ( stored.background() && stored.sitemap() != null && !restarted.contains( stored.key() ) &&
                        stored.lastAccess().plus( IDLE_TIMEOUT ).isAfter( changedAt ) )
                    {
                        final Entry entry = entry( stored.key() );
                        entry.background = true;
                        generate( stored.key(), entry, regenerationDelay, true );
                    }
                }
            } );
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
            if ( stopped )
            {
                return;
            }
            final Generation generation = new Generation( key, entry, delay );
            entry.generation = generation;
            try
            {
                if ( delay.isNegative() || delay.isZero() )
                {
                    generation.submit();
                }
                else
                {
                    generation.future = scheduler.schedule( generation::submit, delay.toMillis(), TimeUnit.MILLISECONDS );
                }
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
            keys.remove( key.id(), key );
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
            LOG.debug( "Removed sitemap {}", key );
        }
    }

    /**
     * Deletes stored sitemaps of the site and principals of the given key beyond {@value #MAX_PER_SITE}: a site is requested through a
     * few hosts, ports and schemes, but they come from the request and can be made up. Sitemaps whose site URL names a port go first,
     * then the least recently requested.
     */
    private void evictOverflow( final Key key )
    {
        final List<SitemapStore.Stored> site = store.ofSite( key );
        site.stream()
            .filter( stored -> stored.sitemap() != null )
            .sorted( EVICTION_ORDER )
            .limit( Math.max( 0, site.size() - MAX_PER_SITE ) )
            .toList()
            .forEach( stored -> {
                store.delete( stored.id() );
                final Entry entry = entries.get( stored.key() );
                if ( entry != null )
                {
                    remove( stored.key(), entry );
                }
            } );
    }

    private void execute( final Runnable runnable )
    {
        try
        {
            scheduler.execute( () -> {
                try
                {
                    runnable.run();
                }
                catch ( RuntimeException e )
                {
                    LOG.warn( "Could not update sitemaps", e );
                }
            } );
        }
        catch ( RejectedExecutionException e )
        {
            LOG.debug( "Sitemaps stopped", e );
        }
    }

    /**
     * Waits for the entry's sitemap, generated here or by another node. Returns null when the generation another node claimed did not
     * finish in time: it is then claimed again.
     */
    private @Nullable Sitemap await( final Entry entry )
    {
        try
        {
            return entry.ready.get( STALE_GENERATION.toMillis(), TimeUnit.MILLISECONDS );
        }
        catch ( TimeoutException e )
        {
            entry.loaded = false;
            return null;
        }
        catch ( ExecutionException e )
        {
            if ( e.getCause() instanceof RuntimeException cause )
            {
                throw cause;
            }
            throw new CompletionException( e.getCause() );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new CancellationException( "Interrupted" );
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
        @Nullable Instant write( Consumer<SitemapEntry> sink, BooleanSupplier cancelled );
    }

    /**
     * Runs generations as local tasks.
     */
    interface Tasks
    {
        void submit( String name, String description, Runnable work );

        static Tasks of( final TaskService taskService )
        {
            return ( name, description, work ) -> taskService.submitLocalTask( SubmitLocalTaskParams.create()
                                                                                   .name( name )
                                                                                   .description( description )
                                                                                   .runnableTask( ( id, progress ) -> work.run() )
                                                                                   .build() );
        }
    }

    public record Key(String repositoryId, String branch, Set<String> principals, SitemapParams params)
    {
        public Key
        {
            principals = Set.copyOf( principals );
        }

        /**
         * A digest of the key, naming its sitemap in the store.
         */
        String id()
        {
            try
            {
                final byte[] json = MAPPER.writeValueAsString( store() ).getBytes( StandardCharsets.UTF_8 );
                return HexFormat.of().formatHex( MessageDigest.getInstance( "SHA-256" ).digest( json ) );
            }
            catch ( JsonProcessingException | NoSuchAlgorithmException e )
            {
                throw new IllegalStateException( e );
            }
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
                                   strings( key.required( "ignorePatterns" ) ) );
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

        volatile @Nullable ScheduledFuture<?> future;

        volatile boolean started;

        volatile boolean cancelled;

        final CountDownLatch finished = new CountDownLatch( 1 );

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

        void submit()
        {
            if ( stopped )
            {
                return;
            }
            tasks.submit( TASK_PREFIX + key.id(), "Sitemap of " + key.repositoryId() + " " +
                ( key.params().sitePath().isEmpty() ? "/" : key.params().sitePath() ) + " " + key.params().siteUrl(), this );
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
            try
            {
                synchronized ( entry )
                {
                    if ( cancelled || stopped || entry.generation != this )
                    {
                        return;
                    }
                    started = true;
                    changes = entry.changes;
                    running.add( this );
                }
                final Semaphore project = projects.computeIfAbsent( key.repositoryId(), repository -> new Semaphore( 1, true ) );
                project.acquireUninterruptibly();
                try
                {
                    write();
                }
                finally
                {
                    project.release();
                }
            }
            finally
            {
                running.remove( this );
                finished.countDown();
            }
        }

        private void write()
        {
            final String id = key.id();
            final Instant now = clock.instant();
            final Instant startedAt = now;
            final SitemapStore.Stored previous = cancelled ? null : store.load( id );
            final boolean claimed = !cancelled && ( previous == null
                ? store.claimNew( id, key, entry.background, member, now )
                : store.claim( previous, member, now, STALE_GENERATION ) );
            if ( !claimed )
            {
                done();
                LOG.debug( "Sitemap {} is generated elsewhere", key );
                return;
            }
            try (Sitemap.Builder builder = Sitemap.builder( temp.resolve( UUID.randomUUID().toString() ), key.params() ))
            {
                final Instant validUntil = ContextBuilder.create()
                    .repositoryId( key.repositoryId() )
                    .branch( key.branch() )
                    .authInfo( authInfo( key ) )
                    .build()
                    .callWith( () -> sources.apply( key ).write( builder::add, () -> cancelled ) );
                if ( cancelled )
                {
                    throw new CancellationException();
                }
                final Sitemap local = builder.build( previous == null ? null : previous.sitemap(), clock.instant() );
                final SitemapStore.Stored stored;
                try
                {
                    stored = store.save( id, key, entry.background, local, validUntil, startedAt, clock.instant() );
                }
                finally
                {
                    local.delete();
                }
                publish( stored );
            }
            catch ( CancellationException e )
            {
                release( id );
                done();
                LOG.debug( "Generation of sitemap {} halted", key );
            }
            catch ( RuntimeException e )
            {
                release( id );
                failed( e );
            }
        }

        private void release( final String id )
        {
            try
            {
                store.release( id, member );
            }
            catch ( RuntimeException e )
            {
                LOG.debug( "Could not release sitemap {}", key, e );
            }
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

        private void publish( final SitemapStore.Stored stored )
        {
            synchronized ( entry )
            {
                if ( entry.generation == this )
                {
                    entry.generation = null;
                }
                entry.stored = stored;
                entry.loaded = true;
                entry.sitemapChanges = changes;
                entry.idleWhenChanged = false;
            }
            entry.ready.complete( stored.sitemap() );
            evictOverflow( key );
            LOG.debug( "Generated sitemap {}", key );

            final Instant validUntil = stored.validUntil();
            if ( stored.background() && validUntil != null )
            {
                generate( key, entry, Duration.between( clock.instant(), validUntil ), false );
            }
        }

        private void failed( final RuntimeException e )
        {
            done();
            final SitemapStore.Stored stored = entry.stored;
            if ( stored == null || stored.sitemap() == null )
            {
                entry.ready.completeExceptionally( e );
                remove( key, entry );
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

        volatile boolean background;

        volatile boolean loaded;

        volatile SitemapStore.@Nullable Stored stored;

        volatile @Nullable Generation generation;

        volatile @Nullable Instant touched;

        volatile @Nullable Instant requested;

        /**
         * Whether a background sitemap was not requested for {@link #IDLE_TIMEOUT} when its content changed: the node where it changed
         * leaves it to the next request.
         */
        volatile boolean idleWhenChanged;

        /**
         * Number of changes of the content seen by this node, and the number the stored sitemap includes.
         */
        volatile long changes;

        volatile long sitemapChanges;
    }
}

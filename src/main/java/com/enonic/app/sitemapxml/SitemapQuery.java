package com.enonic.app.sitemapxml;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.aggregation.BucketAggregation;
import com.enonic.xp.aggregation.DateHistogramBucket;
import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentId;
import com.enonic.xp.content.ContentIds;
import com.enonic.xp.content.ContentPublishInfo;
import com.enonic.xp.content.ContentQuery;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.content.FindContentIdsByQueryResult;
import com.enonic.xp.content.GetContentByIdsParams;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.data.PropertySet;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.data.ValueFactory;
import com.enonic.xp.index.IndexPath;
import com.enonic.xp.node.NodeQuery;
import com.enonic.xp.query.aggregation.DateHistogramAggregationQuery;
import com.enonic.xp.query.expr.DslExpr;
import com.enonic.xp.query.expr.FieldOrderExpr;
import com.enonic.xp.query.expr.OrderExpr;
import com.enonic.xp.query.expr.QueryExpr;
import com.enonic.xp.query.filter.Filter;
import com.enonic.xp.query.filter.RangeFilter;

/**
 * Finds the published contents of a sitemap with the content API. Queries only return ids, at most {@value #PAGE_SIZE} at a time; the
 * contents are then loaded in batches and streamed to the sitemap builder, so neither contents nor entries of the whole sitemap are held
 * in memory.
 * <p>
 * Each query only uses filters Elasticsearch evaluates in proportion to the contents it returns: the site is a single term on the path
 * hierarchy, time slices are numeric ranges.
 */
public final class SitemapQuery
{
    static final int FETCH_BATCH_SIZE = 1_000;

    /**
     * Most hits a plain search returns (Elasticsearch result window).
     */
    static final int PAGE_SIZE = 10_000;

    static final String TIMELINE = "timeline";

    static final List<Interval> INTERVALS =
        List.of( new Interval( "1d", Duration.ofDays( 1 ) ), new Interval( "1h", Duration.ofHours( 1 ) ),
                 new Interval( "1m", Duration.ofMinutes( 1 ) ), new Interval( "1s", Duration.ofSeconds( 1 ) ),
                 new Interval( "1ms", Duration.ofMillis( 1 ) ) );

    static final String IGNORE_PUBLISH_TIMES = "ignorePublishTimes";

    private static final String CONTENT_ROOT = "/content";

    private static final String PATH_HIERARCHY = "_path._path";

    private static final String BLOCK_ROBOTS_FIELD = "x.com-enonic-app-metafields.meta-data.blockRobots";

    private static final Logger LOG = LoggerFactory.getLogger( SitemapQuery.class );

    private static final IndexPath PATH = IndexPath.from( "_path" );

    private static final IndexPath CREATED_TIME = IndexPath.from( "createdTime" );

    /**
     * Time the node version was stored, also on publish: practically unique per content, so it splits contents sharing a creation time.
     */
    private static final IndexPath TIMESTAMP = IndexPath.from( "_ts" );

    static final List<IndexPath> DIMENSIONS = List.of( CREATED_TIME, TIMESTAMP );

    private static final List<OrderExpr> CREATION_ORDER =
        List.of( FieldOrderExpr.create( CREATED_TIME, OrderExpr.Direction.ASC ), FieldOrderExpr.create( PATH, OrderExpr.Direction.ASC ) );

    private static final IndexPath PUBLISH_FROM = IndexPath.from( "publish.from" );

    private static final IndexPath PUBLISH_TO = IndexPath.from( "publish.to" );

    private final ContentService contentService;

    private final SitemapParams params;

    private final Instant now;

    private final String contentRoot;


    public SitemapQuery( final ContentService contentService, final SitemapParams params, final Instant now )
    {
        this.contentService = contentService;
        this.params = params;
        this.now = now;
        this.contentRoot = CONTENT_ROOT + params.sitePath();
    }

    /**
     * Streams the entries of the sitemap to the given consumer.
     * <p>
     * A sitemap that fits in one file is read sorted by path, which compresses better. A larger one is read in the order the contents were
     * created, oldest first: new contents are added to the last file and edited ones stay in place, so the other files keep their content
     * and their modification time in the sitemap index. It is read in slices of at most {@value #PAGE_SIZE} contents: one query with a
     * date histogram of the creation times plans the slices, busy times are split by finer intervals down to milliseconds, then by the
     * time the contents were stored. Each slice is read with range filters, so each query only touches its own contents.
     *
     * @param cancelled checked between batches, the query stops with a {@link CancellationException} once it returns true
     * @return when the sitemap must be generated again because scheduled content goes online or offline, or null
     */
    public @Nullable Instant execute( final SitemapSink sink, final BooleanSupplier cancelled )
    {
        final FindContentIdsByQueryResult overview = histogram( List.of(), 0, 0 );
        final long total = overview.getTotalHits();
        final Progress progress = new Progress( sink, total );
        progress.report( 0 );
        if ( total <= Sitemap.MAX_URLS_PER_FILE )
        {
            emitByPath( sink, progress, cancelled );
        }
        else
        {
            final List<Slice> slices = new ArrayList<>();
            addSlices( overview, 0, 0, List.of(), slices );
            LOG.debug( "Sitemap of {}: {} contents read in {} slices", params.sitePath(), total, slices.size() );
            for ( final Slice slice : slices )
            {
                emit( find( slice.filters(), CREATION_ORDER, slice.count() > PAGE_SIZE ? NodeQuery.ALL_RESULTS_SIZE_FLAG : PAGE_SIZE ), sink,
                      progress, cancelled );
            }
        }

        return ContentConstants.BRANCH_MASTER.equals( ContextAccessor.current().getBranch() ) ? Stream.of( PUBLISH_FROM, PUBLISH_TO )
            .flatMap( field -> nextChange( field ).stream() )
            .min( Instant::compareTo )
            .orElse( null ) : null;
    }

    /**
     * Adds the slices of the histogram buckets in the result, oldest first. Consecutive buckets are merged into one slice while it holds
     * at most {@value #PAGE_SIZE} contents. A bucket holding more is split by the next finer interval, then by the next dimension. A
     * bucket that cannot be split further (more than {@value #PAGE_SIZE} contents created and stored in the same millisecond) is read in
     * one scrolled query.
     */
    private void addSlices( final FindContentIdsByQueryResult result, final int dimension, final int level, final List<Filter> outer,
                            final List<Slice> slices )
    {
        final IndexPath field = DIMENSIONS.get( dimension );
        final Duration interval = INTERVALS.get( level ).duration();
        final List<DateHistogramBucket> buckets = new ArrayList<>();
        if ( result.getAggregations().get( TIMELINE ) instanceof BucketAggregation timeline )
        {
            timeline.getBuckets().forEach( bucket -> buckets.add( (DateHistogramBucket) bucket ) );
        }
        buckets.sort( Comparator.comparing( DateHistogramBucket::getKeyAsInstant ) );

        Instant pendingFrom = null;
        Instant pendingTo = null;
        long pendingCount = 0;
        for ( final DateHistogramBucket bucket : buckets )
        {
            final Instant from = bucket.getKeyAsInstant();
            final Instant to = from.plus( interval );
            final long count = bucket.getDocCount();
            if ( count > PAGE_SIZE )
            {
                if ( pendingFrom != null )
                {
                    slices.add( new Slice( with( outer, range( field, pendingFrom, pendingTo ) ), pendingCount ) );
                    pendingFrom = null;
                }
                final List<Filter> filters = with( outer, range( field, from, to ) );
                if ( level + 1 < INTERVALS.size() )
                {
                    addSlices( histogram( filters, dimension, level + 1 ), dimension, level + 1, outer, slices );
                }
                else if ( dimension + 1 < DIMENSIONS.size() )
                {
                    addSlices( histogram( filters, dimension + 1, 0 ), dimension + 1, 0, filters, slices );
                }
                else
                {
                    slices.add( new Slice( filters, count ) );
                }
            }
            else if ( pendingFrom != null && pendingCount + count <= PAGE_SIZE )
            {
                pendingTo = to;
                pendingCount += count;
            }
            else
            {
                if ( pendingFrom != null )
                {
                    slices.add( new Slice( with( outer, range( field, pendingFrom, pendingTo ) ), pendingCount ) );
                }
                pendingFrom = from;
                pendingTo = to;
                pendingCount = count;
            }
        }
        if ( pendingFrom != null )
        {
            slices.add( new Slice( with( outer, range( field, pendingFrom, pendingTo ) ), pendingCount ) );
        }
    }

    private FindContentIdsByQueryResult histogram( final List<Filter> filters, final int dimension, final int level )
    {
        final ContentQuery.Builder query = ContentQuery.create()
            .queryExpr( QueryExpr.from( DslExpr.from( dsl() ) ) )
            .aggregationQuery( DateHistogramAggregationQuery.create( TIMELINE )
                                   .fieldName( DIMENSIONS.get( dimension ).getPath() )
                                   .interval( INTERVALS.get( level ).name() )
                                   .minDocCount( 1L )
                                   .build() )
            .size( 0 );
        filters.forEach( query::queryFilter );
        return contentService.find( query.build() );
    }

    private static RangeFilter range( final IndexPath field, final Instant from, final Instant to )
    {
        return RangeFilter.create()
            .fieldName( field.getPath() )
            .from( ValueFactory.newDateTime( from ) )
            .lt( ValueFactory.newDateTime( to ) )
            .build();
    }

    private static List<Filter> with( final List<Filter> filters, final Filter filter )
    {
        final List<Filter> result = new ArrayList<>( filters );
        result.add( filter );
        return List.copyOf( result );
    }

    /**
     * Reads all contents sorted by path, {@value #PAGE_SIZE} at a time: each page continues after the last path of the previous one.
     * Paths are compared as indexed, in lower case.
     */
    private void emitByPath( final SitemapSink sink, final Progress progress, final BooleanSupplier cancelled )
    {
        String after = null;
        while ( true )
        {
            final List<Filter> filters =
                after == null ? List.of() : List.of( RangeFilter.create().fieldName( PATH.getPath() ).gt( ValueFactory.newString( after ) ).build() );
            final List<ContentId> ids = find( filters, List.of( FieldOrderExpr.create( PATH, OrderExpr.Direction.ASC ) ), PAGE_SIZE );
            final String last = emit( ids, sink, progress, cancelled );
            if ( ids.size() < PAGE_SIZE || last == null )
            {
                return;
            }
            after = last;
        }
    }

    private List<ContentId> find( final List<Filter> filters, final List<OrderExpr> order, final int size )
    {
        final ContentQuery.Builder query = ContentQuery.create().queryExpr( QueryExpr.from( DslExpr.from( dsl() ), order ) ).size( size );
        filters.forEach( query::queryFilter );
        return contentService.find( query.build() ).getContentIds().stream().toList();
    }

    /**
     * Loads the contents in batches and passes the entries of those in the site to the sink.
     *
     * @return the indexed path of the last content loaded, or null if none was
     */
    private @Nullable String emit( final List<ContentId> ids, final SitemapSink sink, final Progress progress,
                                   final BooleanSupplier cancelled )
    {
        String last = null;
        for ( int start = 0; start < ids.size(); start += FETCH_BATCH_SIZE )
        {
            if ( cancelled.getAsBoolean() )
            {
                throw new CancellationException( "Sitemap generation of " + params.sitePath() + " cancelled" );
            }
            final List<ContentId> batch = ids.subList( start, Math.min( start + FETCH_BATCH_SIZE, ids.size() ) );
            final Map<ContentId, Content> contents = new HashMap<>();
            for ( final Content content : contentService.getByIds(
                GetContentByIdsParams.create().contentIds( ContentIds.from( batch ) ).build() ) )
            {
                contents.put( content.getId(), content );
            }
            for ( final ContentId id : batch )
            {
                final Content content = contents.get( id );
                if ( content == null )
                {
                    continue;
                }
                last = indexed( CONTENT_ROOT + content.getPath() );
                if ( isInSite( content.getPath().toString() ) )
                {
                    sink.accept( new SitemapEntry( content.getPath().toString().substring( params.sitePath().length() ),
                                                   content.getType().toString(), content.getModifiedTime() ) );
                }
            }
            progress.report( batch.size() );
        }
        return last;
    }

    private boolean isInSite( final String path )
    {
        return params.sitePath().isEmpty() || path.equals( params.sitePath() ) || path.startsWith( params.sitePath() + "/" );
    }

    private Optional<Instant> nextChange( final IndexPath field )
    {
        final Function<ContentPublishInfo, @Nullable Instant> value =
            PUBLISH_FROM.equals( field ) ? ContentPublishInfo::from : ContentPublishInfo::to;

        return ContextBuilder.from( ContextAccessor.current() ).attribute( IGNORE_PUBLISH_TIMES, true ).build().callWith( () -> {
            final ContentQuery query = ContentQuery.create()
                .queryExpr( QueryExpr.from( DslExpr.from( dsl() ), FieldOrderExpr.create( field, OrderExpr.Direction.ASC ) ) )
                .queryFilter( RangeFilter.create().fieldName( field.getPath() ).gt( ValueFactory.newDateTime( now ) ).build() )
                .size( 1 )
                .build();
            return contentService.find( query )
                .getContentIds()
                .stream()
                .findFirst()
                .map( contentService::getById )
                .map( Content::getPublishInfo )
                .map( value );
        } );
    }

    PropertyTree dsl()
    {
        final PropertyTree tree = new PropertyTree();
        final PropertySet bool = tree.addSet( "boolean" );

        final PropertySet in = bool.addSet( "must" ).addSet( "in" );
        in.addString( "field", "type" );
        params.contentTypeNames().forEach( type -> in.addString( "values", type ) );

        term( bool.addSet( "must" ), PATH_HIERARCHY, contentRoot );

        final PropertySet blockRobots = bool.addSet( "mustNot" ).addSet( "term" );
        blockRobots.addString( "field", BLOCK_ROBOTS_FIELD );
        blockRobots.addBoolean( "value", true );

        params.ignorePatterns().forEach( pattern -> like( bool.addSet( "mustNot" ), PATH.getPath(), ignorePath( contentRoot, pattern ) ) );

        return tree;
    }

    static String ignorePath( final String contentRoot, final String pattern )
    {
        final String trimmed = pattern.endsWith( "/" ) ? pattern.substring( 0, pattern.length() - 1 ) : pattern;
        if ( trimmed.startsWith( "*" ) )
        {
            return trimmed;
        }
        return contentRoot + ( trimmed.startsWith( "/" ) ? "" : "/" ) + trimmed;
    }

    private static String indexed( final String value )
    {
        return value.trim().toLowerCase();
    }

    private static void like( final PropertySet parent, final String field, final String value )
    {
        final PropertySet like = parent.addSet( "like" );
        like.addString( "field", field );
        like.addString( "value", value );
    }

    private static void term( final PropertySet parent, final String field, final String value )
    {
        final PropertySet term = parent.addSet( "term" );
        term.addString( "field", field );
        term.addString( "value", value );
    }

    record Interval(String name, Duration duration)
    {
    }

    /**
     * Contents matching the filters, at most {@value #PAGE_SIZE} unless they cannot be split further.
     */
    record Slice(List<Filter> filters, long count)
    {
    }

    /**
     * Counts the contents read and passes the count to the sink.
     */
    private static final class Progress
    {
        private final SitemapSink sink;

        private final long total;

        private long read;

        Progress( final SitemapSink sink, final long total )
        {
            this.sink = sink;
            this.total = total;
        }

        void report( final int count )
        {
            read += count;
            sink.progress( read, Math.max( read, total ) );
        }
    }
}

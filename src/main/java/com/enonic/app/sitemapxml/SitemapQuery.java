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
import java.util.function.Consumer;
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
import com.enonic.xp.query.aggregation.DateHistogramAggregationQuery;
import com.enonic.xp.query.expr.DslExpr;
import com.enonic.xp.query.expr.FieldOrderExpr;
import com.enonic.xp.query.expr.OrderExpr;
import com.enonic.xp.query.expr.QueryExpr;
import com.enonic.xp.query.filter.Filter;
import com.enonic.xp.query.filter.Filters;
import com.enonic.xp.query.filter.RangeFilter;

/**
 * Finds the published contents of a sitemap with the content API. The query only returns ids, the contents are then loaded in batches
 * and streamed to the sitemap writer, newest first, so neither contents nor entries of the whole sitemap are held in memory.
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

    private static final String BLOCK_ROBOTS_FIELD = "x.com-enonic-app-metafields.meta-data.blockRobots";

    private static final Logger LOG = LoggerFactory.getLogger( SitemapQuery.class );

    private static final IndexPath PATH = IndexPath.from( "_path" );

    private static final IndexPath MODIFIED_TIME = IndexPath.from( "modifiedTime" );

    private static final IndexPath PUBLISH_FROM = IndexPath.from( "publish.from" );

    private static final IndexPath PUBLISH_TO = IndexPath.from( "publish.to" );

    private final ContentService contentService;

    private final SitemapParams params;

    private final Instant now;

    public SitemapQuery( final ContentService contentService, final SitemapParams params, final Instant now )
    {
        this.contentService = contentService;
        this.params = params;
        this.now = now;
    }

    /**
     * Streams the entries of the sitemap to the given consumer.
     * <p>
     * A sitemap that fits in one file is read sorted by path, which compresses better. A larger one is read newest first, in ranges of
     * modification times holding at most {@value #PAGE_SIZE} contents each: one query with a date histogram of the modification times
     * plans the ranges, then each range is read with a range filter, so each query only touches its own contents. Contents modified in
     * the same millisecond beyond {@value #PAGE_SIZE} are read sorted by path, page by page. No query returns more than
     * {@value #PAGE_SIZE} hits.
     *
     * @param cancelled checked between batches, the query stops with a {@link CancellationException} once it returns true
     * @return when the sitemap must be generated again because scheduled content goes online or offline, or null
     */
    public @Nullable Instant execute( final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        final FindContentIdsByQueryResult overview = contentService.find( ContentQuery.create()
                                                                              .queryExpr( QueryExpr.from( DslExpr.from( dsl() ) ) )
                                                                              .aggregationQuery( timeline( 0 ) )
                                                                              .size( 0 )
                                                                              .build() );
        final long total = overview.getTotalHits();
        if ( total <= Sitemap.MAX_URLS_PER_FILE )
        {
            emitByPath( null, sink, cancelled );
        }
        else
        {
            final List<TimeRange> ranges = new ArrayList<>();
            addRanges( overview, 0, ranges );
            LOG.debug( "Sitemap of {}: {} contents read in {} ranges", params.sitePath(), total, ranges.size() );
            for ( final TimeRange range : ranges )
            {
                if ( range.count() > PAGE_SIZE )
                {
                    emitByPath( range, sink, cancelled );
                }
                else
                {
                    emit( find( range.filter(), FieldOrderExpr.create( MODIFIED_TIME, OrderExpr.Direction.DESC ) ), sink, cancelled );
                }
            }
        }

        return ContentConstants.BRANCH_MASTER.equals( ContextAccessor.current().getBranch() ) ? Stream.of( PUBLISH_FROM, PUBLISH_TO )
            .flatMap( field -> nextChange( field ).stream() )
            .min( Instant::compareTo )
            .orElse( null ) : null;
    }

    /**
     * Adds the ranges of the histogram buckets in the result, newest first. Consecutive buckets are merged into one range while it holds
     * at most {@value #PAGE_SIZE} contents; a bucket holding more is split by the next finer interval, down to milliseconds.
     */
    private void addRanges( final FindContentIdsByQueryResult result, final int level, final List<TimeRange> ranges )
    {
        final Duration interval = INTERVALS.get( level ).duration();
        final List<DateHistogramBucket> buckets = new ArrayList<>();
        if ( result.getAggregations().get( TIMELINE ) instanceof BucketAggregation timeline )
        {
            timeline.getBuckets().forEach( bucket -> buckets.add( (DateHistogramBucket) bucket ) );
        }
        buckets.sort( Comparator.comparing( DateHistogramBucket::getKeyAsInstant ).reversed() );

        TimeRange pending = null;
        for ( final DateHistogramBucket bucket : buckets )
        {
            final TimeRange range = new TimeRange( bucket.getKeyAsInstant(), bucket.getKeyAsInstant().plus( interval ), bucket.getDocCount() );
            if ( range.count() > PAGE_SIZE )
            {
                addPending( pending, ranges );
                pending = null;
                if ( level + 1 < INTERVALS.size() )
                {
                    addRanges( contentService.find( ContentQuery.create()
                                                        .queryExpr( QueryExpr.from( DslExpr.from( dsl() ) ) )
                                                        .queryFilter( range.filter() )
                                                        .aggregationQuery( timeline( level + 1 ) )
                                                        .size( 0 )
                                                        .build() ), level + 1, ranges );
                }
                else
                {
                    ranges.add( range );
                }
            }
            else if ( pending != null && pending.count() + range.count() <= PAGE_SIZE )
            {
                pending = new TimeRange( range.from(), pending.to(), pending.count() + range.count() );
            }
            else
            {
                addPending( pending, ranges );
                pending = range;
            }
        }
        addPending( pending, ranges );
    }

    private static void addPending( final @Nullable TimeRange pending, final List<TimeRange> ranges )
    {
        if ( pending != null )
        {
            ranges.add( pending );
        }
    }

    private static DateHistogramAggregationQuery timeline( final int level )
    {
        return DateHistogramAggregationQuery.create( TIMELINE )
            .fieldName( MODIFIED_TIME.getPath() )
            .interval( INTERVALS.get( level ).name() )
            .minDocCount( 1L )
            .build();
    }

    /**
     * Reads the contents (of the given time range, or all) sorted by path, {@value #PAGE_SIZE} at a time: each page continues after the
     * last path of the previous one. Paths are compared as indexed, in lower case.
     */
    private void emitByPath( final @Nullable TimeRange range, final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
    {
        String after = null;
        while ( true )
        {
            final Filters.Builder filters = Filters.create();
            if ( range != null )
            {
                filters.add( range.filter() );
            }
            if ( after != null )
            {
                filters.add( RangeFilter.create().fieldName( PATH.getPath() ).gt( ValueFactory.newString( after ) ).build() );
            }
            final List<ContentId> ids = find( filters.build(), FieldOrderExpr.create( PATH, OrderExpr.Direction.ASC ) );
            final String last = emit( ids, sink, cancelled );
            if ( ids.size() < PAGE_SIZE || last == null )
            {
                return;
            }
            after = last;
        }
    }

    private List<ContentId> find( final Filter filter, final OrderExpr order )
    {
        return find( Filters.from( filter ), order );
    }

    private List<ContentId> find( final Filters filters, final OrderExpr order )
    {
        final ContentQuery.Builder query =
            ContentQuery.create().queryExpr( QueryExpr.from( DslExpr.from( dsl() ), order ) ).size( PAGE_SIZE );
        filters.forEach( query::queryFilter );
        return contentService.find( query.build() ).getContentIds().stream().toList();
    }

    /**
     * Loads the contents in batches and passes the entries of those in the site to the sink.
     *
     * @return the indexed path of the last content loaded, or null if none was
     */
    private @Nullable String emit( final List<ContentId> ids, final Consumer<SitemapEntry> sink, final BooleanSupplier cancelled )
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
                // as XP indexes string values
                last = ( CONTENT_ROOT + content.getPath() ).trim().toLowerCase();
                if ( isInSite( content.getPath().toString() ) )
                {
                    sink.accept( new SitemapEntry( content.getPath().toString().substring( params.sitePath().length() ),
                                                   content.getType().toString(), content.getModifiedTime() ) );
                }
            }
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
        final String contentRoot = CONTENT_ROOT + params.sitePath();

        final PropertyTree tree = new PropertyTree();
        final PropertySet bool = tree.addSet( "boolean" );

        final PropertySet in = bool.addSet( "must" ).addSet( "in" );
        in.addString( "field", "type" );
        params.contentTypeNames().forEach( type -> in.addString( "values", type ) );

        final PropertySet inSite = bool.addSet( "must" ).addSet( "boolean" );
        like( inSite.addSet( "should" ), "_path", contentRoot + "/*" );
        final PropertySet isSite = inSite.addSet( "should" ).addSet( "term" );
        isSite.addString( "field", "_path" );
        isSite.addString( "value", contentRoot );

        final PropertySet blockRobots = bool.addSet( "mustNot" ).addSet( "term" );
        blockRobots.addString( "field", BLOCK_ROBOTS_FIELD );
        blockRobots.addBoolean( "value", true );

        params.ignorePatterns().forEach( pattern -> like( bool.addSet( "mustNot" ), "_path", ignorePath( contentRoot, pattern ) ) );

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

    private static void like( final PropertySet parent, final String field, final String value )
    {
        final PropertySet like = parent.addSet( "like" );
        like.addString( "field", field );
        like.addString( "value", value );
    }

    record Interval(String name, Duration duration)
    {
    }

    /**
     * Contents modified from {@code from} (inclusive) to {@code to} (exclusive).
     */
    record TimeRange(Instant from, Instant to, long count)
    {
        RangeFilter filter()
        {
            return RangeFilter.create()
                .fieldName( MODIFIED_TIME.getPath() )
                .from( ValueFactory.newDateTime( from ) )
                .lt( ValueFactory.newDateTime( to ) )
                .build();
        }
    }
}

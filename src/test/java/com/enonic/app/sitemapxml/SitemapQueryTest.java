package com.enonic.app.sitemapxml;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.enonic.xp.aggregation.Aggregation;
import com.enonic.xp.aggregation.Aggregations;
import com.enonic.xp.aggregation.Buckets;
import com.enonic.xp.aggregation.DateHistogramBucket;
import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentId;
import com.enonic.xp.content.ContentIds;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentPublishInfo;
import com.enonic.xp.content.ContentQuery;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.content.Contents;
import com.enonic.xp.content.FindContentIdsByQueryResult;
import com.enonic.xp.content.GetContentByIdsParams;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.data.PropertySet;
import com.enonic.xp.node.NodeQuery;
import com.enonic.xp.query.aggregation.AggregationQuery;
import com.enonic.xp.query.aggregation.DateHistogramAggregationQuery;
import com.enonic.xp.query.filter.Filter;
import com.enonic.xp.query.filter.RangeFilter;
import com.enonic.xp.schema.content.ContentTypeName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SitemapQueryTest
{
    private static final Instant NOW = Instant.parse( "2024-06-01T12:00:00Z" );

    @Mock
    private ContentService contentService;

    @Test
    void dsl()
    {
        final SitemapParams params =
            new SitemapParams( "/mysite", "https://example.com", List.of(), List.of( "/skip/", "*/foo", "bar", "/folder/*", "/x*" ), 10 );

        final PropertySet bool = new SitemapQuery( contentService, params, NOW ).dsl().getSet( "boolean" );
        final List<PropertySet> must = sets( bool, "must" );
        final List<PropertySet> mustNot = sets( bool, "mustNot" );

        assertThat( must.get( 0 ).getSet( "in" ).getStrings( "values" ) ).containsExactly( "portal:site" );
        assertThat( must.get( 1 ).getSet( "term" ).getString( "field" ) ).isEqualTo( "_path._path" );
        assertThat( must.get( 1 ).getSet( "term" ).getString( "value" ) ).isEqualTo( "/content/mysite" );
        assertThat( mustNot.get( 0 ).getSet( "term" ).getString( "field" ) ).isEqualTo( "x.com-enonic-app-metafields.meta-data.blockRobots" );
        assertThat( mustNot.get( 0 ).getSet( "term" ).getBoolean( "value" ) ).isTrue();
        assertThat( mustNot.subList( 1, 6 ) ).extracting( set -> set.getSet( "like" ).getString( "value" ) )
            .containsExactly( "/content/mysite/skip", "*/foo", "/content/mysite/bar", "/content/mysite/folder/*", "/content/mysite/x*" );
    }

    @Test
    void streamsContentsLoadedInBatchesNewestFirst()
    {
        final int count = SitemapQuery.FETCH_BATCH_SIZE + 5;
        final List<Content> contents = new ArrayList<>(
            IntStream.range( 0, count ).mapToObj( i -> content( "c" + i, "/mysite/item-" + i ) ).collect( Collectors.toList() ) );
        contents.add( content( "site", "/mysite" ) );
        contents.add( content( "other", "/mysiteother/b" ) );
        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn( result( contents ) );
        answerGetByIds( contents );

        final List<SitemapEntry> entries = new ArrayList<>();
        final Instant validUntil = draft( () -> new SitemapQuery( contentService, params(), NOW ).execute( entries::add, () -> false ) );

        assertThat( entries ).hasSize( count + 1 );
        assertThat( entries.get( 0 ) ).isEqualTo( new SitemapEntry( "/item-0", "base:folder", NOW ) );
        assertThat( entries.get( count - 1 ).path() ).isEqualTo( "/item-" + ( count - 1 ) );
        assertThat( entries.get( entries.size() - 1 ) ).isEqualTo( new SitemapEntry( "", "base:folder", NOW ) );
        assertThat( validUntil ).isNull();
        verify( contentService, times( 2 ) ).getByIds( any( GetContentByIdsParams.class ) );

        final ArgumentCaptor<ContentQuery> query = ArgumentCaptor.forClass( ContentQuery.class );
        verify( contentService, times( 2 ) ).find( query.capture() );
        final ContentQuery overview = query.getAllValues().get( 0 );
        assertThat( overview.getSize() ).isZero();
        assertThat( overview.getAggregationQueries() ).hasSize( 1 );
        final ContentQuery fetch = query.getAllValues().get( 1 );
        assertThat( fetch.getQueryFilters().getSize() ).isZero();
        assertThat( fetch.getQueryExpr().getOrderList() ).extracting( Object::toString ).containsExactly( "_path ASC" );
        assertThat( fetch.getSize() ).isEqualTo( SitemapQuery.PAGE_SIZE );
    }

    @Test
    void singleFileIsReadByPathPageByPage()
    {
        final List<Content> page = IntStream.range( 0, SitemapQuery.PAGE_SIZE ).mapToObj( i -> content( "c" + i, "/mysite/Item-" + i ) ).collect( Collectors.toList() );
        final Content rest = content( "rest", "/mysite/zz" );
        answerGetByIds( Stream.concat( page.stream(), Stream.of( rest ) ).collect( Collectors.toList() ) );
        when( contentService.find( any( ContentQuery.class ) ) ).thenAnswer( invocation -> {
            final ContentQuery query = invocation.getArgument( 0 );
            if ( query.getSize() == 0 )
            {
                return overview( Sitemap.MAX_URLS_PER_FILE );
            }
            return query.getQueryFilters().isEmpty() ? result( page ) : result( List.of( rest ) );
        } );

        final List<SitemapEntry> entries = new ArrayList<>();
        draft( () -> new SitemapQuery( contentService, params(), NOW ).execute( entries::add, () -> false ) );

        assertThat( entries ).hasSize( SitemapQuery.PAGE_SIZE + 1 );
        final ArgumentCaptor<ContentQuery> query = ArgumentCaptor.forClass( ContentQuery.class );
        verify( contentService, times( 3 ) ).find( query.capture() );
        final ContentQuery second = query.getAllValues().get( 2 );
        assertThat( second.getSize() ).isEqualTo( SitemapQuery.PAGE_SIZE );
        assertThat( second.getQueryExpr().getOrderList() ).extracting( Object::toString ).containsExactly( "_path ASC" );
        final RangeFilter after = (RangeFilter) second.getQueryFilters().iterator().next();
        assertThat( after.getFieldName() ).isEqualTo( "_path" );
        assertThat( after.getFrom().asString() ).isEqualTo( "/content/mysite/item-" + ( SitemapQuery.PAGE_SIZE - 1 ) );
        assertThat( after.isIncludeLower() ).isFalse();
    }

    @Test
    void largeSitemapIsReadNewestFirstInSlices()
    {
        final Instant day = Instant.parse( "2024-05-01T00:00:00Z" );
        final Instant hour = day.minus( Duration.ofDays( 2 ) );
        final Instant second = Instant.parse( "2024-01-01T10:20:30Z" );
        final Instant stored = Instant.parse( "2024-01-03T00:00:00Z" );
        final List<String> requests = new ArrayList<>();
        when( contentService.find( any( ContentQuery.class ) ) ).thenAnswer( invocation -> {
            final ContentQuery query = invocation.getArgument( 0 );
            final String histogram = histogram( query );
            final String filters = filters( query );
            requests.add( ( histogram != null ? histogram : query.getSize() + " " + query.getQueryExpr().getOrderList() ) + " " + filters );
            if ( histogram == null )
            {
                // One content per slice, named after its filters.
                final Content content = content( "c" + requests.size(), "/mysite/c" + requests.size() );
                answerGetByIds( List.of( content ) );
                return result( List.of( content ) );
            }
            // newest day 6000, then 7000, then a day of 25000 split by hours, then a day with 30000 modified in one millisecond and
            // stored on three days, then 3000
            switch ( histogram )
            {
                case "modifiedtime 1d":
                    return overview( 71_000, bucket( day, 6_000 ), bucket( day.minus( Duration.ofDays( 1 ) ), 7_000 ),
                                     bucket( day.minus( Duration.ofDays( 2 ) ), 25_000 ), bucket( second.truncatedTo( ChronoUnit.DAYS ), 30_000 ),
                                     bucket( second.minus( Duration.ofDays( 1 ) ).truncatedTo( ChronoUnit.DAYS ), 3_000 ) );
                case "modifiedtime 1h":
                    return filters.contains( hour.toString() )
                        ? overview( 25_000, bucket( hour, 9_000 ), bucket( hour.plus( Duration.ofHours( 1 ) ), 8_000 ),
                                    bucket( hour.plus( Duration.ofHours( 5 ) ), 8_000 ) )
                        : overview( 30_000, bucket( second.truncatedTo( ChronoUnit.HOURS ), 30_000 ) );
                case "modifiedtime 1m":
                    return overview( 30_000, bucket( second.truncatedTo( ChronoUnit.MINUTES ), 30_000 ) );
                case "modifiedtime 1s":
                case "modifiedtime 1ms":
                    return overview( 30_000, bucket( second, 30_000 ) );
                case "_ts 1d":
                    return overview( 30_000, bucket( stored.minus( Duration.ofDays( 2 ) ), 10_000 ), bucket( stored, 10_000 ),
                                     bucket( stored.minus( Duration.ofDays( 1 ) ), 10_000 ) );
                default:
                    throw new IllegalStateException( histogram );
            }
        } );

        final List<SitemapEntry> entries = new ArrayList<>();
        draft( () -> new SitemapQuery( contentService, params(), NOW ).execute( entries::add, () -> false ) );

        final String desc = SitemapQuery.PAGE_SIZE + " [modifiedtime DESC] ";
        final String ms = "modifiedtime 2024-01-01T10:20:30Z 2024-01-01T10:20:30.001Z";
        // All slices are planned first, then read newest first.
        assertThat( requests ).containsExactly( "modifiedtime 1d -",
                                                "modifiedtime 1h modifiedtime 2024-04-29T00:00:00Z 2024-04-30T00:00:00Z",
                                                "modifiedtime 1h modifiedtime 2024-01-01T00:00:00Z 2024-01-02T00:00:00Z",
                                                "modifiedtime 1m modifiedtime 2024-01-01T10:00:00Z 2024-01-01T11:00:00Z",
                                                "modifiedtime 1s modifiedtime 2024-01-01T10:20:00Z 2024-01-01T10:21:00Z",
                                                "modifiedtime 1ms modifiedtime 2024-01-01T10:20:30Z 2024-01-01T10:20:31Z",
                                                "_ts 1d " + ms,
                                                desc + "modifiedtime 2024-05-01T00:00:00Z 2024-05-02T00:00:00Z",
                                                desc + "modifiedtime 2024-04-30T00:00:00Z 2024-05-01T00:00:00Z",
                                                desc + "modifiedtime 2024-04-29T05:00:00Z 2024-04-29T06:00:00Z",
                                                desc + "modifiedtime 2024-04-29T01:00:00Z 2024-04-29T02:00:00Z",
                                                desc + "modifiedtime 2024-04-29T00:00:00Z 2024-04-29T01:00:00Z",
                                                desc + ms + " & _ts 2024-01-03T00:00:00Z 2024-01-04T00:00:00Z",
                                                desc + ms + " & _ts 2024-01-02T00:00:00Z 2024-01-03T00:00:00Z",
                                                desc + ms + " & _ts 2024-01-01T00:00:00Z 2024-01-02T00:00:00Z",
                                                desc + "modifiedtime 2023-12-31T00:00:00Z 2024-01-01T00:00:00Z" );
        assertThat( entries ).hasSize( 9 );
    }

    @Test
    void sliceThatCannotBeSplitIsScrolled()
    {
        final Instant instant = Instant.parse( "2024-01-01T10:20:30Z" );
        final List<ContentQuery> reads = new ArrayList<>();
        when( contentService.find( any( ContentQuery.class ) ) ).thenAnswer( invocation -> {
            final ContentQuery query = invocation.getArgument( 0 );
            if ( histogram( query ) != null )
            {
                return overview( 60_000, bucket( instant, 60_000 ) );
            }
            reads.add( query );
            return result( List.of() );
        } );

        draft( () -> new SitemapQuery( contentService, params(), NOW ).execute( entry -> {
        }, () -> false ) );

        assertThat( reads ).hasSize( 1 );
        assertThat( reads.get( 0 ).getSize() ).isEqualTo( NodeQuery.ALL_RESULTS_SIZE_FLAG );
        assertThat( filters( reads.get( 0 ) ) ).isEqualTo(
            "modifiedtime 2024-01-01T10:20:30Z 2024-01-01T10:20:30.001Z & _ts 2024-01-01T10:20:30Z 2024-01-01T10:20:30.001Z" );
    }

    @Test
    void stopsBetweenBatchesWhenCancelled()
    {
        final List<Content> contents =
            IntStream.range( 0, SitemapQuery.FETCH_BATCH_SIZE * 3 ).mapToObj( i -> content( "c" + i, "/mysite/item-" + i ) ).collect( Collectors.toList() );
        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn( result( contents ) );
        answerGetByIds( contents );

        final List<SitemapEntry> entries = new ArrayList<>();
        assertThatThrownBy( () -> draft(
            () -> new SitemapQuery( contentService, params(), NOW ).execute( entries::add, () -> !entries.isEmpty() ) ) ).isInstanceOf(
            CancellationException.class );

        assertThat( entries ).hasSize( SitemapQuery.FETCH_BATCH_SIZE );
        verify( contentService, times( 1 ) ).getByIds( any( GetContentByIdsParams.class ) );
    }

    @Test
    void readsNewestMaxItemsWhenThereAreMore()
    {
        final Instant day = Instant.parse( "2024-05-01T00:00:00Z" );
        final List<Content> newest = List.of( content( "a", "/mysite/a" ), content( "b", "/mysite/b" ) );
        final List<Content> older = List.of( content( "c", "/mysite/c" ) );
        answerGetByIds( Stream.concat( newest.stream(), older.stream() ).collect( Collectors.toList() ) );
        when( contentService.find( any( ContentQuery.class ) ) ).thenAnswer( invocation -> {
            final ContentQuery query = invocation.getArgument( 0 );
            if ( query.getSize() == 0 )
            {
                return overview( 3, bucket( day, 2 ), bucket( day.minus( Duration.ofDays( 1 ) ), 1 ) );
            }
            // Both days fit in one range, read newest first.
            return result( Stream.concat( newest.stream(), older.stream() ).collect( Collectors.toList() ) );
        } );

        final List<SitemapEntry> entries = new ArrayList<>();
        draft( () -> new SitemapQuery( contentService, params( 2 ), NOW ).execute( entries::add, () -> false ) );

        assertThat( entries ).extracting( SitemapEntry::path ).containsExactly( "/a", "/b" );
        verify( contentService, times( 2 ) ).find( any( ContentQuery.class ) );
    }

    @Test
    void masterExpiresAtNextScheduledChangeLookedUpIgnoringPublishTimes()
    {
        final Content site = content( "site", "/mysite" );
        final Content pending = content( "pending", "/mysite/pending", ContentPublishInfo.create().from( NOW.plusSeconds( 600 ) ).build() );
        final Content expiring = content( "expiring", "/mysite/expiring", ContentPublishInfo.create().to( NOW.plusSeconds( 300 ) ).build() );
        final List<Object> ignorePublishTimes = new ArrayList<>();

        when( contentService.find( any( ContentQuery.class ) ) ).thenAnswer( invocation -> {
            final ContentQuery query = invocation.getArgument( 0 );
            if ( query.getSize() != 1 )
            {
                return result( List.of( site ) );
            }
            ignorePublishTimes.add( ContextAccessor.current().getAttribute( SitemapQuery.IGNORE_PUBLISH_TIMES ) );
            return result( List.of( query.getQueryExpr().getOrderList().get( 0 ).toString().startsWith( "publish.from" ) ? pending : expiring ) );
        } );
        answerGetByIds( List.of( site ) );
        when( contentService.getById( pending.getId() ) ).thenReturn( pending );
        when( contentService.getById( expiring.getId() ) ).thenReturn( expiring );

        final List<SitemapEntry> entries = new ArrayList<>();
        final Instant validUntil = ContextBuilder.create()
            .repositoryId( "com.enonic.cms.project" )
            .branch( "master" )
            .build()
            .callWith( () -> new SitemapQuery( contentService, params(), NOW ).execute( entries::add, () -> false ) );

        assertThat( entries ).extracting( SitemapEntry::path ).containsExactly( "" );
        assertThat( validUntil ).isEqualTo( NOW.plusSeconds( 300 ) );
        assertThat( ignorePublishTimes ).containsExactly( true, true );
    }

    @Test
    void ignorePath()
    {
        assertThat( SitemapQuery.ignorePath( "/content/s", "/a/" ) ).isEqualTo( "/content/s/a" );
        assertThat( SitemapQuery.ignorePath( "/content/s", "a*" ) ).isEqualTo( "/content/s/a*" );
        assertThat( SitemapQuery.ignorePath( "/content/s", "*a" ) ).isEqualTo( "*a" );
    }

    private static String histogram( final ContentQuery query )
    {
        for ( final AggregationQuery aggregation : query.getAggregationQueries() )
        {
            final DateHistogramAggregationQuery histogram = (DateHistogramAggregationQuery) aggregation;
            return histogram.getFieldName() + " " + histogram.getInterval();
        }
        return null;
    }

    private static String filters( final ContentQuery query )
    {
        final List<String> filters = new ArrayList<>();
        for ( final Filter filter : query.getQueryFilters() )
        {
            final RangeFilter range = (RangeFilter) filter;
            filters.add( range.getFieldName() + " " +
                             ( range.getFrom().isDateType() ? range.getFrom().asInstant() + " " + range.getTo().asInstant() : range.getFrom().asString() ) );
        }
        return filters.isEmpty() ? "-" : String.join( " & ", filters );
    }

    private static DateHistogramBucket bucket( final Instant key, final long count )
    {
        return DateHistogramBucket.create().keyAsInstant( key ).key( key.toString() ).docCount( count ).build();
    }

    private static FindContentIdsByQueryResult overview( final long total, final DateHistogramBucket... buckets )
    {
        final Buckets.Builder timeline = Buckets.create();
        for ( final DateHistogramBucket bucket : buckets )
        {
            timeline.add( bucket );
        }
        return FindContentIdsByQueryResult.create()
            .contents( ContentIds.empty() )
            .totalHits( total )
            .aggregations( Aggregations.from( Aggregation.bucketAggregation( SitemapQuery.TIMELINE ).buckets( timeline.build() ).build() ) )
            .build();
    }

    private static List<PropertySet> sets( final PropertySet parent, final String name )
    {
        final List<PropertySet> sets = new ArrayList<>();
        parent.getSets( name ).forEach( sets::add );
        return sets;
    }

    private static SitemapParams params()
    {
        return params( 50_000 );
    }

    private static SitemapParams params( final int maxItems )
    {
        return new SitemapParams( "/mysite", "https://example.com", List.of( new ContentTypeSetting( "base:folder", "", "" ) ),
                                  List.of(), maxItems );
    }

    private void answerGetByIds( final List<Content> contents )
    {
        when( contentService.getByIds( any( GetContentByIdsParams.class ) ) ).thenAnswer( invocation -> {
            final ContentIds ids = invocation.<GetContentByIdsParams>getArgument( 0 ).getIds();
            final List<Content> found = contents.stream().filter( content -> ids.contains( content.getId() ) ).collect( Collectors.toList() );
            Collections.reverse( found );
            return Contents.from( found );
        } );
    }

    private static <T> T draft( final Callable<T> callable )
    {
        return ContextBuilder.create().repositoryId( "com.enonic.cms.project" ).branch( "draft" ).build().callWith( callable );
    }

    private static FindContentIdsByQueryResult result( final List<Content> contents )
    {
        return FindContentIdsByQueryResult.create()
            .contents( ContentIds.from( contents.stream().map( Content::getId ).collect( Collectors.toList() ) ) )
            .totalHits( contents.size() )
            .build();
    }

    private static Content content( final String id, final String path )
    {
        return content( id, path, null );
    }

    private static Content content( final String id, final String path, final ContentPublishInfo publishInfo )
    {
        final ContentPath contentPath = ContentPath.from( path );
        return Content.create()
            .id( ContentId.from( id ) )
            .parentPath( contentPath.getParentPath() )
            .name( contentPath.getName().toString() )
            .type( ContentTypeName.folder() )
            .modifiedTime( NOW )
            .publishInfo( publishInfo )
            .build();
    }
}

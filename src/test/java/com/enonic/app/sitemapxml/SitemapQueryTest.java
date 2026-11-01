package com.enonic.app.sitemapxml;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
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
        final SitemapParams params = new SitemapParams( "/mysite", "https://example.com", List.of(), List.of( "/skip/", "*/foo", "bar" ) );

        final PropertySet bool = new SitemapQuery( contentService, params, NOW ).dsl().getSet( "boolean" );
        final List<PropertySet> must = sets( bool, "must" );
        final List<PropertySet> mustNot = sets( bool, "mustNot" );

        assertThat( must.get( 0 ).getSet( "in" ).getStrings( "values" ) ).containsExactly( "portal:site" );
        assertThat( must.get( 1 ).getSet( "term" ).getString( "field" ) ).isEqualTo( "_path._path" );
        assertThat( must.get( 1 ).getSet( "term" ).getString( "value" ) ).isEqualTo( "/content/mysite" );
        assertThat( mustNot.get( 0 ).getSet( "term" ).getString( "field" ) ).isEqualTo( "x.com-enonic-app-metafields.meta-data.blockRobots" );
        assertThat( mustNot.get( 0 ).getSet( "term" ).getBoolean( "value" ) ).isTrue();
        assertThat( mustNot.subList( 1, 4 ) ).extracting( set -> set.getSet( "like" ).getString( "value" ) )
            .containsExactly( "/content/mysite/skip", "*/foo", "/content/mysite/bar" );
    }

    @Test
    void streamsContentsLoadedInBatchesNewestFirst()
    {
        final int count = SitemapQuery.FETCH_BATCH_SIZE + 5;
        final List<Content> contents = new ArrayList<>(
            IntStream.range( 0, count ).mapToObj( i -> content( "c" + i, "/mysite/item-" + i ) ).toList() );
        contents.add( content( "site", "/mysite" ) );
        contents.add( content( "other", "/mysiteother/b" ) );
        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn( result( contents ) );
        answerGetByIds( contents );

        final List<SitemapEntry> entries = new ArrayList<>();
        final Instant validUntil = draft( () -> new SitemapQuery( contentService, params(), NOW ).execute( entries::add, () -> false ) );

        assertThat( entries ).hasSize( count + 1 );
        assertThat( entries.getFirst() ).isEqualTo( new SitemapEntry( "/item-0", "base:folder", NOW ) );
        assertThat( entries.get( count - 1 ).path() ).isEqualTo( "/item-" + ( count - 1 ) );
        assertThat( entries.getLast() ).isEqualTo( new SitemapEntry( "", "base:folder", NOW ) );
        assertThat( validUntil ).isNull();
        verify( contentService, times( 2 ) ).getByIds( any( GetContentByIdsParams.class ) );

        final ArgumentCaptor<ContentQuery> query = ArgumentCaptor.forClass( ContentQuery.class );
        verify( contentService, times( 2 ) ).find( query.capture() );
        final ContentQuery overview = query.getAllValues().get( 0 );
        assertThat( overview.getSize() ).isZero();
        assertThat( overview.getAggregationQueries() ).hasSize( 1 );
        final ContentQuery fetch = query.getAllValues().get( 1 );
        assertThat( fetch.getReturnFields() ).isEmpty();
        assertThat( fetch.getQueryFilters().getSize() ).isZero();
        assertThat( fetch.getQueryExpr().getOrderList() ).extracting( Object::toString ).containsExactly( "_path ASC" );
        assertThat( fetch.getSize() ).isEqualTo( SitemapQuery.PAGE_SIZE );
    }

    @Test
    void singleFileIsReadByPathPageByPage()
    {
        final List<Content> page = IntStream.range( 0, SitemapQuery.PAGE_SIZE ).mapToObj( i -> content( "c" + i, "/mysite/Item-" + i ) ).toList();
        final Content rest = content( "rest", "/mysite/zz" );
        answerGetByIds( Stream.concat( page.stream(), Stream.of( rest ) ).toList() );
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
    void largeSitemapIsReadNewestFirstInTimeRanges()
    {
        final Instant day = Instant.parse( "2024-05-01T00:00:00Z" );
        final Instant hour = day.minus( Duration.ofDays( 2 ) );
        final Instant second = Instant.parse( "2024-01-01T10:20:30Z" );
        final List<String> requests = new ArrayList<>();
        when( contentService.find( any( ContentQuery.class ) ) ).thenAnswer( invocation -> {
            final ContentQuery query = invocation.getArgument( 0 );
            final String interval = interval( query );
            final String range = range( query );
            requests.add( ( interval != null ? interval : query.getSize() + " " + query.getQueryExpr().getOrderList() ) + " " + range );
            if ( interval == null && query.getQueryFilters().getSize() > 1 )
            {
                throw new IllegalStateException( "Only one page expected" );
            }
            if ( interval == null )
            {
                // One content per range query, named after its range.
                final Content content = content( "c" + requests.size(), "/mysite/" + range.replace( ':', '-' ).replace( ' ', '_' ) );
                answerGetByIds( List.of( content ) );
                return result( List.of( content ) );
            }
            return switch ( interval )
            {
                // newest day 6000, then 7000, then a day of 25000 split by hours, then a day with 30000 in one millisecond, then 3000
                case "1d" -> overview( 71_000, bucket( day, 6_000 ), bucket( day.minus( Duration.ofDays( 1 ) ), 7_000 ),
                                       bucket( day.minus( Duration.ofDays( 2 ) ), 25_000 ),
                                       bucket( second.truncatedTo( java.time.temporal.ChronoUnit.DAYS ), 30_000 ),
                                       bucket( second.minus( Duration.ofDays( 1 ) ).truncatedTo( java.time.temporal.ChronoUnit.DAYS ), 3_000 ) );
                case "1h" -> range.startsWith( hour.toString() )
                    ? overview( 25_000, bucket( hour, 9_000 ), bucket( hour.plus( Duration.ofHours( 1 ) ), 8_000 ),
                                bucket( hour.plus( Duration.ofHours( 5 ) ), 8_000 ) )
                    : overview( 30_000, bucket( second.truncatedTo( java.time.temporal.ChronoUnit.HOURS ), 30_000 ) );
                case "1m" -> overview( 30_000, bucket( second.truncatedTo( java.time.temporal.ChronoUnit.MINUTES ), 30_000 ) );
                case "1s" -> overview( 30_000, bucket( second, 30_000 ) );
                case "1ms" -> overview( 30_000, bucket( second, 30_000 ) );
                default -> throw new IllegalStateException( interval );
            };
        } );

        final List<SitemapEntry> entries = new ArrayList<>();
        draft( () -> new SitemapQuery( contentService, params(), NOW ).execute( entries::add, () -> false ) );

        final String desc = SitemapQuery.PAGE_SIZE + " [modifiedtime DESC] ";
        // All ranges are planned first, then read newest first.
        assertThat( requests ).containsExactly( "1d -",
                                                "1h 2024-04-29T00:00:00Z 2024-04-30T00:00:00Z",
                                                "1h 2024-01-01T00:00:00Z 2024-01-02T00:00:00Z",
                                                "1m 2024-01-01T10:00:00Z 2024-01-01T11:00:00Z",
                                                "1s 2024-01-01T10:20:00Z 2024-01-01T10:21:00Z",
                                                "1ms 2024-01-01T10:20:30Z 2024-01-01T10:20:31Z",
                                                desc + "2024-05-01T00:00:00Z 2024-05-02T00:00:00Z",
                                                desc + "2024-04-30T00:00:00Z 2024-05-01T00:00:00Z",
                                                desc + "2024-04-29T05:00:00Z 2024-04-29T06:00:00Z",
                                                desc + "2024-04-29T01:00:00Z 2024-04-29T02:00:00Z",
                                                desc + "2024-04-29T00:00:00Z 2024-04-29T01:00:00Z",
                                                SitemapQuery.PAGE_SIZE + " [_path ASC] 2024-01-01T10:20:30Z 2024-01-01T10:20:30.001Z",
                                                desc + "2023-12-31T00:00:00Z 2024-01-01T00:00:00Z" );
        assertThat( entries ).hasSize( 7 );
    }

    @Test
    void stopsBetweenBatchesWhenCancelled()
    {
        final List<Content> contents =
            IntStream.range( 0, SitemapQuery.FETCH_BATCH_SIZE * 3 ).mapToObj( i -> content( "c" + i, "/mysite/item-" + i ) ).toList();
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
            return result( List.of( query.getQueryExpr().getOrderList().getFirst().toString().startsWith( "publish.from" ) ? pending : expiring ) );
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

    private static String interval( final ContentQuery query )
    {
        for ( final AggregationQuery aggregation : query.getAggregationQueries() )
        {
            return ( (DateHistogramAggregationQuery) aggregation ).getInterval();
        }
        return null;
    }

    private static String range( final ContentQuery query )
    {
        for ( final Filter filter : query.getQueryFilters() )
        {
            final RangeFilter range = (RangeFilter) filter;
            return range.getFrom().asInstant() + " " + range.getTo().asInstant();
        }
        return "-";
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
        return new SitemapParams( "/mysite", "https://example.com", List.of( new ContentTypeSetting( "base:folder", "", "" ) ),
                                  List.of() );
    }

    private void answerGetByIds( final List<Content> contents )
    {
        when( contentService.getByIds( any( GetContentByIdsParams.class ) ) ).thenAnswer( invocation -> {
            final ContentIds ids = invocation.<GetContentByIdsParams>getArgument( 0 ).getIds();
            final List<Content> found = new ArrayList<>( contents.stream().filter( content -> ids.contains( content.getId() ) ).toList() );
            return Contents.from( found.reversed() );
        } );
    }

    private static <T> T draft( final Callable<T> callable )
    {
        return ContextBuilder.create().repositoryId( "com.enonic.cms.project" ).branch( "draft" ).build().callWith( callable );
    }

    private static FindContentIdsByQueryResult result( final List<Content> contents )
    {
        return FindContentIdsByQueryResult.create()
            .contents( ContentIds.from( contents.stream().map( Content::getId ).toList() ) )
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

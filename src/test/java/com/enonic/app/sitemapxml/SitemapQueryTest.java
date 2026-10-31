package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.enonic.xp.schema.content.ContentTypeName;

import static org.assertj.core.api.Assertions.assertThat;
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
        final SitemapParams params = new SitemapParams( "/mysite", "https://example.com", List.of(), List.of( "/skip/", "*/foo", "bar" ), 10,
                                                        10 );

        final PropertySet bool = new SitemapQuery( contentService, params, NOW ).dsl().getSet( "boolean" );
        final List<PropertySet> must = sets( bool, "must" );
        final List<PropertySet> mustNot = sets( bool, "mustNot" );

        assertThat( must.get( 0 ).getSet( "in" ).getStrings( "values" ) ).containsExactly( "portal:site" );
        final List<PropertySet> should = sets( must.get( 1 ).getSet( "boolean" ), "should" );
        assertThat( should.get( 0 ).getSet( "like" ).getString( "value" ) ).isEqualTo( "/content/mysite/*" );
        assertThat( should.get( 1 ).getSet( "term" ).getString( "value" ) ).isEqualTo( "/content/mysite" );
        assertThat( mustNot.get( 0 ).getSet( "term" ).getString( "field" ) ).isEqualTo( "x.com-enonic-app-metafields.meta-data.blockRobots" );
        assertThat( mustNot.get( 0 ).getSet( "term" ).getBoolean( "value" ) ).isTrue();
        assertThat( mustNot.subList( 1, 4 ) ).extracting( set -> set.getSet( "like" ).getString( "value" ) )
            .containsExactly( "/content/mysite/skip", "*/foo", "/content/mysite/bar" );
    }

    @Test
    void loadsContentInBatchesKeepingQueryOrder()
    {
        final int count = SitemapQuery.FETCH_BATCH_SIZE + 5;
        final List<Content> contents =
            IntStream.range( 0, count ).mapToObj( i -> content( "c" + i, "/mysite", "item-" + i, null ) ).collect( Collectors.toList() );
        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn( result( contents ) );
        when( contentService.getByIds( any( GetContentByIdsParams.class ) ) ).thenAnswer( invocation -> {
            final ContentIds ids = invocation.<GetContentByIdsParams>getArgument( 0 ).getIds();
            final List<Content> found = contents.stream().filter( content -> ids.contains( content.getId() ) ).collect( Collectors.toList() );
            Collections.reverse( found );
            return Contents.from( found );
        } );

        final SitemapContents result = draft( () -> new SitemapQuery( contentService, params( 50_000 ), NOW ).execute() );

        assertThat( result.entries() ).hasSize( count );
        assertThat( result.entries().get( 0 ) ).isEqualTo( new SitemapEntry( "/item-0", "base:folder", NOW ) );
        assertThat( result.entries().get( count - 1 ).path() ).isEqualTo( "/item-" + ( count - 1 ) );
        assertThat( result.validUntil() ).isNull();
        verify( contentService, times( 2 ) ).getByIds( any( GetContentByIdsParams.class ) );

        final ArgumentCaptor<ContentQuery> query = ArgumentCaptor.forClass( ContentQuery.class );
        verify( contentService ).find( query.capture() );
        assertThat( query.getValue().getSize() ).isEqualTo( NodeQuery.ALL_RESULTS_SIZE_FLAG );
        assertThat( query.getValue().getQueryExpr().getOrderList() ).extracting( Object::toString ).containsExactly( "modifiedtime DESC" );
    }

    @Test
    void limitsToMaxItemsAndSitePaths()
    {
        final List<Content> contents = List.of( content( "site", "/", "mysite", null ), content( "other", "/", "mysiteother", null ),
                                                content( "a", "/mysite", "a", null ) );
        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn( result( contents ) );
        when( contentService.getByIds( any( GetContentByIdsParams.class ) ) ).thenReturn( Contents.from( contents ) );

        final SitemapContents result = draft( () -> new SitemapQuery( contentService, params( 2 ), NOW ).execute() );

        assertThat( result.entries() ).extracting( SitemapEntry::path ).containsExactly( "" );
        final ArgumentCaptor<ContentQuery> query = ArgumentCaptor.forClass( ContentQuery.class );
        verify( contentService ).find( query.capture() );
        assertThat( query.getValue().getSize() ).isEqualTo( 2 );
    }

    @Test
    void masterExpiresAtNextScheduledChangeLookedUpIgnoringPublishTimes()
    {
        final Content site = content( "site", "/", "mysite", null );
        final Content pending = content( "pending", "/mysite", "pending", ContentPublishInfo.create().from( NOW.plusSeconds( 600 ) ).build() );
        final Content expiring =
            content( "expiring", "/mysite", "expiring", ContentPublishInfo.create().to( NOW.plusSeconds( 300 ) ).build() );
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
        when( contentService.getById( pending.getId() ) ).thenReturn( pending );
        when( contentService.getById( expiring.getId() ) ).thenReturn( expiring );
        when( contentService.getByIds( any( GetContentByIdsParams.class ) ) ).thenReturn( Contents.from( site ) );

        final SitemapContents contents = ContextBuilder.create()
            .repositoryId( "com.enonic.cms.project" )
            .branch( "master" )
            .build()
            .callWith( () -> new SitemapQuery( contentService, params( 10 ), NOW ).execute() );

        assertThat( contents.entries() ).extracting( SitemapEntry::path ).containsExactly( "" );
        assertThat( contents.validUntil() ).isEqualTo( NOW.plusSeconds( 300 ) );
        assertThat( ignorePublishTimes ).containsExactly( true, true );
    }

    @Test
    void ignorePath()
    {
        assertThat( SitemapQuery.ignorePath( "/content/s", "/a/" ) ).isEqualTo( "/content/s/a" );
        assertThat( SitemapQuery.ignorePath( "/content/s", "a*" ) ).isEqualTo( "/content/s/a*" );
        assertThat( SitemapQuery.ignorePath( "/content/s", "*a" ) ).isEqualTo( "*a" );
    }

    private static List<PropertySet> sets( final PropertySet parent, final String name )
    {
        final List<PropertySet> sets = new ArrayList<>();
        parent.getSets( name ).forEach( sets::add );
        return sets;
    }

    private static SitemapParams params( final int maxItems )
    {
        return new SitemapParams( "/mysite", "https://example.com", List.of( new ContentTypeSetting( "base:folder", "", "" ) ), List.of(),
                                  maxItems, 10 );
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

    private static Content content( final String id, final String parent, final String name, final ContentPublishInfo publishInfo )
    {
        return Content.create()
            .id( ContentId.from( id ) )
            .parentPath( ContentPath.from( parent ) )
            .name( name )
            .type( ContentTypeName.folder() )
            .modifiedTime( NOW )
            .publishInfo( publishInfo )
            .build();
    }
}

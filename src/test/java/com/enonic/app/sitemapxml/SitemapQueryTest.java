package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
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
        final SitemapParams params = new SitemapParams( "/mysite", "https://example.com", List.of(), List.of( "/skip/", "*/foo", "bar" ), 50_000 );

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
        verify( contentService ).find( query.capture() );
        assertThat( query.getValue().getQueryExpr().getOrderList() ).extracting( Object::toString ).containsExactly( "modifiedtime DESC" );
        assertThat( query.getValue().getSize() ).isEqualTo( NodeQuery.ALL_RESULTS_SIZE_FLAG );
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
    void limitsToMaxItems()
    {
        final List<Content> contents = List.of( content( "a", "/mysite/a" ), content( "b", "/mysite/b" ) );
        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn( result( contents ) );
        answerGetByIds( contents );

        final List<SitemapEntry> entries = new ArrayList<>();
        draft( () -> new SitemapQuery( contentService, params( 1 ), NOW ).execute( entries::add, () -> false ) );

        assertThat( entries ).extracting( SitemapEntry::path ).containsExactly( "/a" );
        final ArgumentCaptor<ContentQuery> query = ArgumentCaptor.forClass( ContentQuery.class );
        verify( contentService ).find( query.capture() );
        assertThat( query.getValue().getSize() ).isEqualTo( 1 );
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

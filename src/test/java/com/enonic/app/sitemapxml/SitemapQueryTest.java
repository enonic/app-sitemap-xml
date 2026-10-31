package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
import com.enonic.xp.index.FieldValues;
import com.enonic.xp.index.IndexPath;
import com.enonic.xp.node.NodeQuery;
import com.enonic.xp.schema.content.ContentTypeName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    void readsPathTypeAndModifiedTimeFromIndex()
    {
        final Map<ContentId, FieldValues> fields = new LinkedHashMap<>();
        fields.put( ContentId.from( "a" ), fields( "/mysite/a", "com.example:MyType", "2024-02-02T09:01:33.169382938Z" ) );
        fields.put( ContentId.from( "site" ), fields( "/mysite", "portal:site", "2024-01-01T00:00:00Z" ) );
        fields.put( ContentId.from( "other" ), fields( "/mysiteother/b", "portal:site", "2024-01-01T00:00:00Z" ) );
        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn( result( fields ) );

        final SitemapContents contents = draft( () -> new SitemapQuery( contentService, params( 10 ), NOW ).execute() );

        assertThat( contents.entries() ).containsExactly(
            new SitemapEntry( "/a", "com.example:MyType", Instant.parse( "2024-02-02T09:01:33.169382938Z" ) ),
            new SitemapEntry( "", "portal:site", Instant.parse( "2024-01-01T00:00:00Z" ) ) );
        assertThat( contents.validUntil() ).isNull();
        verify( contentService, never() ).getByIds( any() );

        final ArgumentCaptor<ContentQuery> query = ArgumentCaptor.forClass( ContentQuery.class );
        verify( contentService ).find( query.capture() );
        assertThat( query.getValue().getReturnFields() ).containsExactlyInAnyOrder( IndexPath.from( "_path" ), IndexPath.from( "type" ),
                                                                                      IndexPath.from( "modifiedTime" ) );
        assertThat( query.getValue().getQueryExpr().getOrderList() ).extracting( Object::toString ).containsExactly( "modifiedtime DESC" );
        assertThat( query.getValue().getSize() ).isEqualTo( 10 );
    }

    @Test
    void loadsContentInBatchesWhenIndexValuesAreMissing()
    {
        final int count = SitemapQuery.FETCH_BATCH_SIZE + 5;
        final List<Content> contents = IntStream.range( 0, count ).mapToObj( i -> content( "c" + i, "/mysite/item-" + i ) ).toList();
        final Map<ContentId, FieldValues> fields = new LinkedHashMap<>();
        contents.forEach( content -> fields.put( content.getId(), FieldValues.empty() ) );
        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn( result( fields ) );
        when( contentService.getByIds( any( GetContentByIdsParams.class ) ) ).thenAnswer( invocation -> {
            final ContentIds ids = invocation.<GetContentByIdsParams>getArgument( 0 ).getIds();
            final List<Content> found = new ArrayList<>( contents.stream().filter( content -> ids.contains( content.getId() ) ).toList() );
            return Contents.from( found.reversed() );
        } );

        final SitemapContents result = draft( () -> new SitemapQuery( contentService, params( 50_000 ), NOW ).execute() );

        assertThat( result.entries() ).hasSize( count );
        assertThat( result.entries().getFirst() ).isEqualTo( new SitemapEntry( "/item-0", "base:folder", NOW ) );
        assertThat( result.entries().getLast().path() ).isEqualTo( "/item-" + ( count - 1 ) );
        verify( contentService, times( 2 ) ).getByIds( any( GetContentByIdsParams.class ) );

        final ArgumentCaptor<ContentQuery> query = ArgumentCaptor.forClass( ContentQuery.class );
        verify( contentService ).find( query.capture() );
        assertThat( query.getValue().getSize() ).isEqualTo( NodeQuery.ALL_RESULTS_SIZE_FLAG );
    }

    @Test
    void limitsToMaxItems()
    {
        final Map<ContentId, FieldValues> fields = new LinkedHashMap<>();
        fields.put( ContentId.from( "a" ), fields( "/mysite/a", "base:folder", "2024-01-01T00:00:00Z" ) );
        fields.put( ContentId.from( "b" ), fields( "/mysite/b", "base:folder", "2024-01-01T00:00:00Z" ) );
        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn( result( fields ) );

        final SitemapContents contents = draft( () -> new SitemapQuery( contentService, params( 1 ), NOW ).execute() );

        assertThat( contents.entries() ).extracting( SitemapEntry::path ).containsExactly( "/a" );
    }

    @Test
    void masterExpiresAtNextScheduledChangeLookedUpIgnoringPublishTimes()
    {
        final Content pending = content( "pending", "/mysite/pending", ContentPublishInfo.create().from( NOW.plusSeconds( 600 ) ).build() );
        final Content expiring = content( "expiring", "/mysite/expiring", ContentPublishInfo.create().to( NOW.plusSeconds( 300 ) ).build() );
        final List<Object> ignorePublishTimes = new ArrayList<>();

        when( contentService.find( any( ContentQuery.class ) ) ).thenAnswer( invocation -> {
            final ContentQuery query = invocation.getArgument( 0 );
            if ( query.getSize() != 1 )
            {
                return result( Map.of( ContentId.from( "site" ), fields( "/mysite", "portal:site", "2024-01-01T00:00:00Z" ) ) );
            }
            ignorePublishTimes.add( ContextAccessor.current().getAttribute( SitemapQuery.IGNORE_PUBLISH_TIMES ) );
            final Content next = query.getQueryExpr().getOrderList().getFirst().toString().startsWith( "publish.from" ) ? pending : expiring;
            return result( Map.of( next.getId(), FieldValues.empty() ) );
        } );
        when( contentService.getById( pending.getId() ) ).thenReturn( pending );
        when( contentService.getById( expiring.getId() ) ).thenReturn( expiring );

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

    @Test
    void instant()
    {
        assertThat( SitemapQuery.instant( "2024-01-01T00:00:00Z" ) ).contains( Instant.parse( "2024-01-01T00:00:00Z" ) );
        assertThat( SitemapQuery.instant( 1704067200000L ) ).contains( Instant.parse( "2024-01-01T00:00:00Z" ) );
        assertThat( SitemapQuery.instant( NOW ) ).contains( NOW );
        assertThat( SitemapQuery.instant( "not a date" ) ).isEmpty();
    }

    private static List<PropertySet> sets( final PropertySet parent, final String name )
    {
        final List<PropertySet> sets = new ArrayList<>();
        parent.getSets( name ).forEach( sets::add );
        return sets;
    }

    private static SitemapParams params( final int maxItems )
    {
        return new SitemapParams( "/mysite", "https://example.com", List.of( new ContentTypeSetting( "base:folder", "", "" ),
                                                                             new ContentTypeSetting( "com.example:MyType", "", "" ) ),
                                  List.of(), maxItems, 10 );
    }

    private static <T> T draft( final Callable<T> callable )
    {
        return ContextBuilder.create().repositoryId( "com.enonic.cms.project" ).branch( "draft" ).build().callWith( callable );
    }

    private static FieldValues fields( final String path, final String type, final String modifiedTime )
    {
        return FieldValues.create()
            .add( "_path", List.of( path ) )
            .add( "type", List.of( type ) )
            .add( "modifiedTime", List.of( modifiedTime ) )
            .build();
    }

    private static FindContentIdsByQueryResult result( final Map<ContentId, FieldValues> fields )
    {
        return FindContentIdsByQueryResult.create()
            .contents( ContentIds.from( fields.keySet() ) )
            .fields( fields )
            .totalHits( fields.size() )
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

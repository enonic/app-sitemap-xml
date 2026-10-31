package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.data.PropertySet;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.node.FindNodesByQueryResult;
import com.enonic.xp.node.Node;
import com.enonic.xp.node.NodeHit;
import com.enonic.xp.node.NodeId;
import com.enonic.xp.node.NodeIds;
import com.enonic.xp.node.NodePath;
import com.enonic.xp.node.NodeQuery;
import com.enonic.xp.node.NodeService;
import com.enonic.xp.node.Nodes;

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
    private NodeService nodeService;

    @Test
    void dsl()
    {
        final SitemapParams params = new SitemapParams( "/mysite", "https://example.com", List.of(), List.of( "/skip/", "*/foo", "bar" ), 10,
                                                        10 );

        final PropertySet bool = new SitemapQuery( nodeService, params, NOW ).dsl().getSet( "boolean" );
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
    void fetchesNodesInBatchesKeepingQueryOrder()
    {
        final int count = SitemapQuery.FETCH_BATCH_SIZE + 5;
        final List<Node> nodes = IntStream.range( 0, count ).mapToObj( i -> node( "n" + i, "/content/mysite/item-" + i, "base:folder" ) ).toList();
        when( nodeService.findByQuery( any( NodeQuery.class ) ) ).thenReturn( hits( nodes ) );
        when( nodeService.getByIds( any( NodeIds.class ) ) ).thenAnswer(
            invocation -> Nodes.from( nodes.stream().filter( node -> invocation.<NodeIds>getArgument( 0 ).contains( node.id() ) ).toList().reversed() ) );

        final SitemapContents contents = draft( () -> new SitemapQuery( nodeService, params( 50_000 ), NOW ).execute() );

        assertThat( contents.entries() ).hasSize( count );
        assertThat( contents.entries().getFirst() ).isEqualTo( new SitemapEntry( "/item-0", "base:folder", NOW ) );
        assertThat( contents.entries().getLast().path() ).isEqualTo( "/item-" + ( count - 1 ) );
        assertThat( contents.validUntil() ).isNull();
        verify( nodeService, times( 2 ) ).getByIds( any( NodeIds.class ) );

        final ArgumentCaptor<NodeQuery> query = ArgumentCaptor.forClass( NodeQuery.class );
        verify( nodeService ).findByQuery( query.capture() );
        assertThat( query.getValue().getSize() ).isEqualTo( NodeQuery.ALL_RESULTS_SIZE_FLAG );
        assertThat( query.getValue().getQueryFilters().isEmpty() ).isTrue();
    }

    @Test
    void limitsToMaxItemsAndSitePaths()
    {
        final List<Node> nodes = List.of( node( "site", "/content/mysite", "portal:site" ), node( "other", "/content/mysiteother", "base:folder" ),
                                          node( "a", "/content/mysite/a", "base:folder" ) );
        when( nodeService.findByQuery( any( NodeQuery.class ) ) ).thenReturn( hits( nodes ) );
        when( nodeService.getByIds( any( NodeIds.class ) ) ).thenReturn( Nodes.from( nodes ) );

        final SitemapContents contents = draft( () -> new SitemapQuery( nodeService, params( 2 ), NOW ).execute() );

        assertThat( contents.entries() ).extracting( SitemapEntry::path ).containsExactly( "" );
        final ArgumentCaptor<NodeQuery> query = ArgumentCaptor.forClass( NodeQuery.class );
        verify( nodeService ).findByQuery( query.capture() );
        assertThat( query.getValue().getSize() ).isEqualTo( 2 );
    }

    @Test
    void masterFiltersPublishTimesAndExpiresAtNextScheduledChange()
    {
        final Node site = node( "site", "/content/mysite", "portal:site" );
        final Node pending = node( "pending", "/content/mysite/pending", "base:folder" );
        pending.data().setInstant( "publish.from", NOW.plusSeconds( 600 ) );
        final Node expiring = node( "expiring", "/content/mysite/expiring", "base:folder" );
        expiring.data().setInstant( "publish.to", NOW.plusSeconds( 300 ) );

        when( nodeService.findByQuery( any( NodeQuery.class ) ) ).thenAnswer( invocation -> {
            final NodeQuery query = invocation.getArgument( 0 );
            if ( query.getSize() != 1 )
            {
                assertThat( query.getQueryFilters().getSize() ).isEqualTo( 2 );
                return hits( List.of( site ) );
            }
            return hits( List.of( query.getOrderBys().getFirst().toString().startsWith( "publish.from" ) ? pending : expiring ) );
        } );
        when( nodeService.getById( pending.id() ) ).thenReturn( pending );
        when( nodeService.getById( expiring.id() ) ).thenReturn( expiring );
        when( nodeService.getByIds( any( NodeIds.class ) ) ).thenReturn( Nodes.from( site ) );

        final SitemapContents contents = ContextBuilder.create()
            .repositoryId( "com.enonic.cms.project" )
            .branch( "master" )
            .build()
            .callWith( () -> new SitemapQuery( nodeService, params( 10 ), NOW ).execute() );

        assertThat( contents.entries() ).extracting( SitemapEntry::path ).containsExactly( "" );
        assertThat( contents.validUntil() ).isEqualTo( NOW.plusSeconds( 300 ) );
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

    private static FindNodesByQueryResult hits( final List<Node> nodes )
    {
        final FindNodesByQueryResult.Builder result = FindNodesByQueryResult.create().totalHits( nodes.size() );
        nodes.forEach( node -> result.addNodeHit( NodeHit.create().nodeId( node.id() ).build() ) );
        return result.build();
    }

    private static Node node( final String id, final String path, final String type )
    {
        final PropertyTree data = new PropertyTree();
        data.setString( "type", type );
        data.setInstant( "modifiedTime", NOW );
        final NodePath nodePath = new NodePath( path );
        return Node.create()
            .id( NodeId.from( id ) )
            .parentPath( nodePath.getParentPath() )
            .name( nodePath.getName().toString() )
            .data( data )
            .build();
    }
}

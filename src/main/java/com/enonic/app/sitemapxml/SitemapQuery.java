package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.data.PropertySet;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.data.ValueFactory;
import com.enonic.xp.index.IndexPath;
import com.enonic.xp.node.Node;
import com.enonic.xp.node.NodeHit;
import com.enonic.xp.node.NodeId;
import com.enonic.xp.node.NodeIds;
import com.enonic.xp.node.NodeIndexPath;
import com.enonic.xp.node.NodeQuery;
import com.enonic.xp.node.NodeService;
import com.enonic.xp.query.expr.DslExpr;
import com.enonic.xp.query.expr.FieldOrderExpr;
import com.enonic.xp.query.expr.OrderExpr;
import com.enonic.xp.query.expr.QueryExpr;
import com.enonic.xp.query.filter.BooleanFilter;
import com.enonic.xp.query.filter.Filter;
import com.enonic.xp.query.filter.RangeFilter;

public final class SitemapQuery
{
    static final int ES_MAX_RESULT_WINDOW = 10_000;

    private static final Logger LOG = LoggerFactory.getLogger( SitemapQuery.class );

    static final int FETCH_BATCH_SIZE = 1_000;

    private static final String CONTENT_ROOT = "/content";

    private static final String BLOCK_ROBOTS_FIELD = "x.com-enonic-app-metafields.meta-data.blockRobots";

    private static final String PUBLISH_FROM = "publish.from";

    private static final String PUBLISH_TO = "publish.to";

    private static final IndexPath MODIFIED_TIME = IndexPath.from( "modifiedTime" );

    private static final IndexPath TYPE = IndexPath.from( "type" );

    private final NodeService nodeService;

    private final SitemapParams params;

    private final Instant now;

    public SitemapQuery( final NodeService nodeService, final SitemapParams params, final Instant now )
    {
        this.nodeService = nodeService;
        this.params = params;
        this.now = now;
    }

    public SitemapContents execute()
    {
        final boolean filterPublishTimes = ContentConstants.BRANCH_MASTER.equals( ContextAccessor.current().getBranch() );

        final NodeQuery.Builder query = NodeQuery.create()
            .query( QueryExpr.from( DslExpr.from( dsl() ), FieldOrderExpr.create( MODIFIED_TIME, OrderExpr.Direction.DESC ),
                                    FieldOrderExpr.create( TYPE, OrderExpr.Direction.ASC ) ) )
            .returnFields( NodeIndexPath.PATH )
            .size( params.maxItems() <= ES_MAX_RESULT_WINDOW ? params.maxItems() : NodeQuery.ALL_RESULTS_SIZE_FLAG );
        if ( filterPublishTimes )
        {
            onlineFilters().forEach( query::addQueryFilter );
        }

        final List<NodeHit> hits = nodeService.findByQuery( query.build() ).getNodeHits().stream().limit( params.maxItems() ).toList();

        final Instant validUntil = filterPublishTimes ? Stream.of( PUBLISH_FROM, PUBLISH_TO )
            .flatMap( field -> nextChange( field ).stream() )
            .min( Instant::compareTo )
            .orElse( null ) : null;

        return new SitemapContents( entries( hits ), validUntil );
    }

    private List<SitemapEntry> entries( final List<NodeHit> hits )
    {
        final List<@Nullable SitemapEntry> fromIndex = hits.stream().map( this::fromIndex ).toList();
        final List<NodeId> missing = new ArrayList<>();
        for ( int i = 0; i < hits.size(); i++ )
        {
            if ( fromIndex.get( i ) == null )
            {
                missing.add( hits.get( i ).getNodeId() );
            }
        }
        final Map<NodeId, SitemapEntry> loaded = missing.isEmpty() ? Map.of() : load( missing );
        LOG.debug( "Sitemap of {}: {} entries from index, {} loaded from nodes", params.sitePath(), hits.size() - missing.size(),
                   missing.size() );

        final List<SitemapEntry> entries = new ArrayList<>( hits.size() );
        for ( int i = 0; i < hits.size(); i++ )
        {
            final SitemapEntry indexed = fromIndex.get( i );
            final SitemapEntry resolved = indexed != null ? indexed : loaded.get( hits.get( i ).getNodeId() );
            if ( resolved != null && isInSite( resolved.path() ) )
            {
                entries.add( new SitemapEntry( resolved.path().substring( siteNodePath().length() ), resolved.contentType(),
                                               resolved.lastModified() ) );
            }
        }
        return entries;
    }

    private @Nullable SitemapEntry fromIndex( final NodeHit hit )
    {
        final Optional<String> path = hit.getFields().getSingleValue( NodeIndexPath.PATH ).map( Object::toString );
        final List<Object> sortValues = hit.getSort() != null ? hit.getSort().getValues() : List.of();
        if ( path.isEmpty() || sortValues.size() != 2 )
        {
            return null;
        }
        final Optional<String> type = contentType( String.valueOf( sortValues.get( 1 ) ) );
        final Optional<Instant> modifiedTime = instant( String.valueOf( sortValues.get( 0 ) ) );
        if ( type.isEmpty() || modifiedTime.isEmpty() )
        {
            return null;
        }
        return new SitemapEntry( path.get(), type.get(), modifiedTime.get() );
    }

    private Optional<String> contentType( final String indexed )
    {
        return params.contentTypeNames().stream().filter( type -> type.equalsIgnoreCase( indexed ) ).findFirst();
    }

    static Optional<Instant> instant( final String indexed )
    {
        try
        {
            return Optional.of( Instant.parse( indexed ) );
        }
        catch ( DateTimeParseException e )
        {
            return Optional.empty();
        }
    }

    private Map<NodeId, SitemapEntry> load( final List<NodeId> ids )
    {
        final Map<NodeId, SitemapEntry> entries = new HashMap<>();
        for ( int start = 0; start < ids.size(); start += FETCH_BATCH_SIZE )
        {
            final List<NodeId> batch = ids.subList( start, Math.min( start + FETCH_BATCH_SIZE, ids.size() ) );
            for ( final Node node : nodeService.getByIds( NodeIds.from( batch ) ) )
            {
                final String type = node.data().getString( "type" );
                if ( type != null )
                {
                    entries.put( node.id(), new SitemapEntry( node.path().toString(), type, node.data().getInstant( "modifiedTime" ) ) );
                }
            }
        }
        return entries;
    }

    private boolean isInSite( final String path )
    {
        final String siteNodePath = siteNodePath();
        return path.equals( siteNodePath ) || path.startsWith( siteNodePath + "/" );
    }

    private String siteNodePath()
    {
        return CONTENT_ROOT + params.sitePath();
    }

    private Optional<Instant> nextChange( final String field )
    {
        final NodeQuery query = NodeQuery.create()
            .query( QueryExpr.from( DslExpr.from( dsl() ), FieldOrderExpr.create( IndexPath.from( field ), OrderExpr.Direction.ASC ) ) )
            .addQueryFilter( RangeFilter.create().fieldName( field ).gt( ValueFactory.newDateTime( now ) ).build() )
            .size( 1 )
            .build();

        return nodeService.findByQuery( query )
            .getNodeIds()
            .stream()
            .findFirst()
            .map( nodeService::getById )
            .map( node -> node.data().getInstant( field ) );
    }

    private List<Filter> onlineFilters()
    {
        final Filter notPending = BooleanFilter.create()
            .mustNot( RangeFilter.create().fieldName( PUBLISH_FROM ).gt( ValueFactory.newDateTime( now ) ).build() )
            .build();
        final Filter notExpired = BooleanFilter.create()
            .mustNot( RangeFilter.create().fieldName( PUBLISH_TO ).lt( ValueFactory.newDateTime( now ) ).build() )
            .build();
        return List.of( notPending, notExpired );
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
}

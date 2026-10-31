package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentId;
import com.enonic.xp.content.ContentIds;
import com.enonic.xp.content.ContentPublishInfo;
import com.enonic.xp.content.ContentQuery;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.content.GetContentByIdsParams;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.data.PropertySet;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.data.ValueFactory;
import com.enonic.xp.index.IndexPath;
import com.enonic.xp.node.NodeQuery;
import com.enonic.xp.query.expr.DslExpr;
import com.enonic.xp.query.expr.FieldOrderExpr;
import com.enonic.xp.query.expr.OrderExpr;
import com.enonic.xp.query.expr.QueryExpr;
import com.enonic.xp.query.filter.RangeFilter;

public final class SitemapQuery
{
    static final int ES_MAX_RESULT_WINDOW = 10_000;

    static final int FETCH_BATCH_SIZE = 1_000;

    static final String IGNORE_PUBLISH_TIMES = "ignorePublishTimes";

    private static final String CONTENT_ROOT = "/content";

    private static final String BLOCK_ROBOTS_FIELD = "x.com-enonic-app-metafields.meta-data.blockRobots";

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

    public SitemapContents execute()
    {
        final ContentQuery query = ContentQuery.create()
            .queryExpr( QueryExpr.from( DslExpr.from( dsl() ), FieldOrderExpr.create( MODIFIED_TIME, OrderExpr.Direction.DESC ) ) )
            .size( params.maxItems() <= ES_MAX_RESULT_WINDOW ? params.maxItems() : NodeQuery.ALL_RESULTS_SIZE_FLAG )
            .build();

        final List<ContentId> ids =
            contentService.find( query ).getContentIds().stream().limit( params.maxItems() ).collect( Collectors.toList() );

        final Instant validUntil = ContentConstants.BRANCH_MASTER.equals( ContextAccessor.current().getBranch() ) ? Stream.of(
            PUBLISH_FROM, PUBLISH_TO ).flatMap( field -> nextChange( field ).stream() ).min( Instant::compareTo ).orElse( null ) : null;

        return new SitemapContents( entries( ids ), validUntil );
    }

    private List<SitemapEntry> entries( final List<ContentId> ids )
    {
        final List<SitemapEntry> entries = new ArrayList<>( ids.size() );
        for ( int start = 0; start < ids.size(); start += FETCH_BATCH_SIZE )
        {
            final List<ContentId> batch = ids.subList( start, Math.min( start + FETCH_BATCH_SIZE, ids.size() ) );
            final Map<ContentId, Content> contents = new HashMap<>();
            for ( final Content content : contentService.getByIds( new GetContentByIdsParams( ContentIds.from( batch ) ) ) )
            {
                contents.put( content.getId(), content );
            }
            for ( final ContentId id : batch )
            {
                final Content content = contents.get( id );
                if ( content != null && isInSite( content.getPath().toString() ) )
                {
                    entries.add( new SitemapEntry( content.getPath().toString().substring( params.sitePath().length() ),
                                                   content.getType().toString(), content.getModifiedTime() ) );
                }
            }
        }
        return entries;
    }

    private boolean isInSite( final String path )
    {
        return params.sitePath().isEmpty() || path.equals( params.sitePath() ) || path.startsWith( params.sitePath() + "/" );
    }

    private Optional<Instant> nextChange( final IndexPath field )
    {
        final Function<ContentPublishInfo, Instant> value =
            PUBLISH_FROM.equals( field ) ? ContentPublishInfo::getFrom : ContentPublishInfo::getTo;

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
}

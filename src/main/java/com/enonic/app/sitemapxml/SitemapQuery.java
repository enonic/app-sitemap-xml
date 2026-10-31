package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.enonic.xp.index.FieldValues;
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

    private static final Logger LOG = LoggerFactory.getLogger( SitemapQuery.class );

    private static final String CONTENT_ROOT = "/content";

    private static final String BLOCK_ROBOTS_FIELD = "x.com-enonic-app-metafields.meta-data.blockRobots";

    private static final IndexPath PATH = IndexPath.from( "_path" );

    private static final IndexPath TYPE = IndexPath.from( "type" );

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
            .returnFields( PATH, TYPE, MODIFIED_TIME )
            .size( params.maxItems() <= ES_MAX_RESULT_WINDOW ? params.maxItems() : NodeQuery.ALL_RESULTS_SIZE_FLAG )
            .build();

        final FindContentIdsByQueryResult result = contentService.find( query );
        final List<ContentId> ids = result.getContentIds().stream().limit( params.maxItems() ).toList();

        final Instant validUntil = ContentConstants.BRANCH_MASTER.equals( ContextAccessor.current().getBranch() ) ? Stream.of(
            PUBLISH_FROM, PUBLISH_TO ).flatMap( field -> nextChange( field ).stream() ).min( Instant::compareTo ).orElse( null ) : null;

        return new SitemapContents( entries( ids, result.getFields() ), validUntil );
    }

    private List<SitemapEntry> entries( final List<ContentId> ids, final Map<ContentId, FieldValues> fields )
    {
        final Map<ContentId, SitemapEntry> fromIndex = new HashMap<>();
        final List<ContentId> missing = new ArrayList<>();
        for ( final ContentId id : ids )
        {
            final SitemapEntry entry = fromIndex( fields.getOrDefault( id, FieldValues.empty() ) );
            if ( entry != null )
            {
                fromIndex.put( id, entry );
            }
            else
            {
                missing.add( id );
            }
        }
        final Map<ContentId, SitemapEntry> loaded = missing.isEmpty() ? Map.of() : load( missing );
        LOG.debug( "Sitemap of {}: {} entries from index, {} loaded", params.sitePath(), fromIndex.size(), missing.size() );

        final List<SitemapEntry> entries = new ArrayList<>( ids.size() );
        for ( final ContentId id : ids )
        {
            final SitemapEntry entry = fromIndex.containsKey( id ) ? fromIndex.get( id ) : loaded.get( id );
            if ( entry != null && isInSite( entry.path() ) )
            {
                entries.add( new SitemapEntry( entry.path().substring( params.sitePath().length() ), entry.contentType(),
                                               entry.lastModified() ) );
            }
        }
        return entries;
    }

    private @Nullable SitemapEntry fromIndex( final FieldValues fields )
    {
        final Optional<String> path = fields.getSingleValue( PATH ).map( Object::toString );
        final Optional<String> type = fields.getSingleValue( TYPE ).map( Object::toString );
        final Optional<Instant> modifiedTime = fields.getSingleValue( MODIFIED_TIME ).flatMap( SitemapQuery::instant );
        if ( path.isEmpty() || type.isEmpty() || modifiedTime.isEmpty() )
        {
            return null;
        }
        return new SitemapEntry( path.get(), type.get(), modifiedTime.get() );
    }

    static Optional<Instant> instant( final Object value )
    {
        if ( value instanceof Instant instant )
        {
            return Optional.of( instant );
        }
        if ( value instanceof Number millis )
        {
            return Optional.of( Instant.ofEpochMilli( millis.longValue() ) );
        }
        try
        {
            return Optional.of( Instant.parse( value.toString() ) );
        }
        catch ( DateTimeParseException e )
        {
            return Optional.empty();
        }
    }

    private Map<ContentId, SitemapEntry> load( final List<ContentId> ids )
    {
        final Map<ContentId, SitemapEntry> entries = new HashMap<>();
        for ( int start = 0; start < ids.size(); start += FETCH_BATCH_SIZE )
        {
            final List<ContentId> batch = ids.subList( start, Math.min( start + FETCH_BATCH_SIZE, ids.size() ) );
            for ( final Content content : contentService.getByIds( new GetContentByIdsParams( ContentIds.from( batch ) ) ) )
            {
                entries.put( content.getId(), new SitemapEntry( content.getPath().toString(), content.getType().toString(),
                                                                content.getModifiedTime() ) );
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
}

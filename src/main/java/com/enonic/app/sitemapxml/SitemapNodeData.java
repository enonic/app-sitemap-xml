package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.enonic.xp.data.PropertySet;

/**
 * The key and the description of a stored sitemap as node properties.
 */
final class SitemapNodeData
{
    static final String KEY = "key";

    static final String SITEMAP = "sitemap";

    private SitemapNodeData()
    {
    }

    static void writeKey( final PropertySet data, final SitemapCache.Key key )
    {
        final JsonNode stored = key.store();
        final PropertySet set = data.addSet( KEY );
        set.addString( "repository", stored.required( "repository" ).asText() );
        set.addString( "branch", stored.required( "branch" ).asText() );
        stored.required( "principals" ).forEach( principal -> set.addString( "principals", principal.asText() ) );
        set.addString( "sitePath", stored.required( "sitePath" ).asText() );
        set.addString( "siteUrl", stored.required( "siteUrl" ).asText() );
        set.addString( "format", stored.required( "format" ).asText() );
        for ( final JsonNode contentType : stored.required( "contentTypes" ) )
        {
            final PropertySet setting = set.addSet( "contentTypes" );
            setting.addString( "contentType", contentType.required( "contentType" ).asText() );
            setting.addString( "changefreq", contentType.required( "changefreq" ).asText() );
            setting.addString( "priority", contentType.required( "priority" ).asText() );
        }
        stored.required( "ignorePatterns" ).forEach( pattern -> set.addString( "ignorePatterns", pattern.asText() ) );
    }

    static SitemapCache.Key readKey( final PropertySet data )
    {
        final PropertySet set = data.getSet( KEY );
        final ObjectNode key = JsonNodeFactory.instance.objectNode();
        key.put( "repository", set.getString( "repository" ) );
        key.put( "branch", set.getString( "branch" ) );
        final ArrayNode principals = key.putArray( "principals" );
        set.getStrings( "principals" ).forEach( principals::add );
        key.put( "sitePath", set.getString( "sitePath" ) );
        key.put( "siteUrl", set.getString( "siteUrl" ) );
        key.put( "format", set.getString( "format" ) );
        final ArrayNode contentTypes = key.putArray( "contentTypes" );
        for ( final PropertySet setting : set.getSets( "contentTypes" ) )
        {
            contentTypes.addObject()
                .put( "contentType", setting.getString( "contentType" ) )
                .put( "changefreq", setting.getString( "changefreq" ) )
                .put( "priority", setting.getString( "priority" ) );
        }
        final ArrayNode ignorePatterns = key.putArray( "ignorePatterns" );
        set.getStrings( "ignorePatterns" ).forEach( ignorePatterns::add );
        return SitemapCache.Key.restore( key );
    }

    static void writeSitemap( final PropertySet data, final Sitemap sitemap )
    {
        final JsonNode stored = sitemap.store();
        final PropertySet set = data.addSet( SITEMAP );
        set.addLong( "size", stored.required( "size" ).asLong() );
        for ( final JsonNode file : stored.required( "files" ) )
        {
            final PropertySet described = set.addSet( "files" );
            described.addLong( "number", file.required( "number" ).asLong() );
            final JsonNode lastmod = file.get( "lastmod" );
            if ( lastmod != null )
            {
                described.addInstant( "lastmod", Instant.parse( lastmod.asText() ) );
            }
            described.addString( "digest", file.required( "digest" ).asText() );
            final JsonNode range = file.get( "range" );
            if ( range != null )
            {
                described.addInstant( "range", Instant.parse( range.asText() ) );
            }
            final JsonNode count = file.get( "count" );
            if ( count != null )
            {
                described.addLong( "count", count.asLong() );
            }
            described.addLong( "urls", file.required( "urls" ).asLong() );
        }
        final JsonNode cutoff = stored.get( "cutoff" );
        if ( cutoff != null )
        {
            set.addInstant( "cutoff", Instant.parse( cutoff.asText() ) );
        }
        final JsonNode modified = stored.get( "modified" );
        if ( modified != null )
        {
            set.addInstant( "modified", Instant.parse( modified.asText() ) );
        }
        for ( final Map.Entry<String, JsonNode> file : stored.required( "bytes" ).properties() )
        {
            final PropertySet size = set.addSet( "bytes" );
            size.addString( "name", file.getKey() );
            size.addLong( "size", file.getValue().asLong() );
        }
        final JsonNode named = stored.get( "namedDigests" );
        if ( named != null )
        {
            for ( final Map.Entry<String, JsonNode> file : named.properties() )
            {
                final PropertySet digest = set.addSet( "namedDigests" );
                digest.addString( "name", file.getKey() );
                digest.addString( "digest", file.getValue().asText() );
            }
        }
    }

    static @Nullable Sitemap readSitemap( final PropertySet data, final Sitemap.FileSource files )
    {
        final PropertySet set = data.getSet( SITEMAP );
        if ( set == null )
        {
            return null;
        }
        final ObjectNode description = JsonNodeFactory.instance.objectNode();
        description.put( "size", set.getLong( "size" ) );
        final ArrayNode described = description.putArray( "files" );
        for ( final PropertySet file : set.getSets( "files" ) )
        {
            final ObjectNode entry = described.addObject();
            entry.put( "number", file.getLong( "number" ) );
            final Instant lastmod = file.getInstant( "lastmod" );
            if ( lastmod != null )
            {
                entry.put( "lastmod", lastmod.toString() );
            }
            entry.put( "digest", file.getString( "digest" ) );
            final Instant range = file.getInstant( "range" );
            if ( range != null )
            {
                entry.put( "range", range.toString() );
            }
            final Long count = file.getLong( "count" );
            if ( count != null )
            {
                entry.put( "count", count );
            }
            entry.put( "urls", file.getLong( "urls" ) );
        }
        final Instant cutoff = set.getInstant( "cutoff" );
        if ( cutoff != null )
        {
            description.put( "cutoff", cutoff.toString() );
        }
        final Instant modified = set.getInstant( "modified" );
        if ( modified != null )
        {
            description.put( "modified", modified.toString() );
        }
        final ObjectNode bytes = description.putObject( "bytes" );
        set.getSets( "bytes" ).forEach( file -> bytes.put( file.getString( "name" ), file.getLong( "size" ) ) );
        final ObjectNode named = description.putObject( "namedDigests" );
        set.getSets( "namedDigests" ).forEach( file -> named.put( file.getString( "name" ), file.getString( "digest" ) ) );
        return Sitemap.restore( files, description );
    }
}

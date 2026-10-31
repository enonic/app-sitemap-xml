package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.io.ByteSource;

import com.enonic.xp.content.ContentService;
import com.enonic.xp.script.ScriptValue;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.script.bean.ScriptBean;

public final class SitemapBean
    implements ScriptBean
{
    private SitemapService service;

    @Override
    public void initialize( final BeanContext context )
    {
        this.service = new SitemapService( context.getService( ContentService.class ).get(), context.getService( SitemapCache.class ).get(),
                                           Clock.systemUTC() );
    }

    public SitemapResponse root( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.root() ) );
    }

    public SitemapResponse file( final ScriptValue params, final int number )
    {
        return respond( params, sitemap -> sitemap.file( number ) );
    }

    public SitemapResponse json( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.json() ) );
    }

    public SitemapResponse rootGzip( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.rootGzip() ) );
    }

    public SitemapResponse rootEncoded( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.rootEncoded() ) );
    }

    public SitemapResponse fileGzip( final ScriptValue params, final int number )
    {
        return respond( params, sitemap -> sitemap.fileGzip( number ) );
    }

    public String guillotineUrlset( final ScriptValue params, final int count )
    {
        return Objects.requireNonNull( service, "Bean not initialized" ).sitemap( toParams( params.getMap() ) ).urlsetJson( count );
    }

    private SitemapResponse respond( final ScriptValue params, final Function<Sitemap, Optional<ByteSource>> body )
    {
        try
        {
            final Sitemap sitemap = Objects.requireNonNull( service, "Bean not initialized" ).sitemap( toParams( params.getMap() ) );
            return body.apply( sitemap ).map( SitemapResponse::ok ).orElseGet( SitemapResponse::notFound );
        }
        catch ( SitemapBusyException e )
        {
            return SitemapResponse.tooManyRequests();
        }
    }

    static SitemapParams toParams( final Map<String, Object> map )
    {
        final List<ContentTypeSetting> contentTypes = list( map.get( "contentTypes" ) ).stream()
            .filter( Map.class::isInstance )
            .map( setting -> (Map<?, ?>) setting )
            .map( setting -> new ContentTypeSetting( string( setting.get( "contentType" ) ), string( setting.get( "changefreq" ) ),
                                                     string( setting.get( "priority" ) ) ) )
            .collect( Collectors.toList() );
        final List<String> ignorePatterns = list( map.get( "ignorePatterns" ) ).stream().map( SitemapBean::string ).collect( Collectors.toList() );

        return new SitemapParams( string( map.get( "sitePath" ) ), string( map.get( "siteUrl" ) ), contentTypes, ignorePatterns,
                                  integer( map.get( "maxItems" ) ), integer( map.get( "urlsPerFile" ) ) );
    }

    private static List<?> list( final Object value )
    {
        return value instanceof List ? (List<?>) value : List.of();
    }

    private static String string( final Object value )
    {
        return value == null ? "" : value.toString();
    }

    private static int integer( final Object value )
    {
        if ( value instanceof Number )
        {
            return ( (Number) value ).intValue();
        }
        if ( value instanceof String )
        {
            return Integer.parseInt( ( (String) value ).trim() );
        }
        throw new IllegalArgumentException( "Not a number: " + value );
    }
}

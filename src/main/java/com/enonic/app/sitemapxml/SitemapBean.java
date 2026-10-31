package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.google.common.io.ByteSource;

import com.enonic.xp.content.ContentService;
import com.enonic.xp.portal.url.PortalUrlService;
import com.enonic.xp.script.ScriptValue;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.script.bean.ScriptBean;

public final class SitemapBean
    implements ScriptBean
{
    private @Nullable SitemapService service;

    @Override
    public void initialize( final BeanContext context )
    {
        this.service = new SitemapService( context.getService( ContentService.class ).get(), context.getService( PortalUrlService.class ).get(),
                                           context.getService( SitemapCache.class ).get(), Clock.systemUTC() );
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

    private SitemapResponse respond( final ScriptValue params, final Function<Sitemap, Optional<ByteSource>> body )
    {
        final Map<String, Object> map = params.getMap();
        try
        {
            final Sitemap sitemap = Objects.requireNonNull( service, "Bean not initialized" )
                .sitemap( toParams( map ), Boolean.TRUE.equals( map.get( "preferBaseUrl" ) ) );
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
            .map( Map.class::cast )
            .map( setting -> new ContentTypeSetting( string( setting.get( "contentType" ) ), string( setting.get( "changefreq" ) ),
                                                     string( setting.get( "priority" ) ) ) )
            .toList();
        final List<String> ignorePatterns = list( map.get( "ignorePatterns" ) ).stream().map( SitemapBean::string ).toList();

        return new SitemapParams( string( map.get( "sitePath" ) ), string( map.get( "siteUrl" ) ), contentTypes, ignorePatterns,
                                  integer( map.get( "maxItems" ) ), integer( map.get( "urlsPerFile" ) ) );
    }

    private static List<?> list( final @Nullable Object value )
    {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string( final @Nullable Object value )
    {
        return value == null ? "" : value.toString();
    }

    private static int integer( final @Nullable Object value )
    {
        return switch ( value )
        {
            case Number number -> number.intValue();
            case String string -> Integer.parseInt( string.trim() );
            case null -> throw new IllegalArgumentException( "Missing number" );
            default -> throw new IllegalArgumentException( "Not a number: " + value );
        };
    }
}

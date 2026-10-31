package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.enonic.xp.node.NodeService;
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
        this.service = new SitemapService( context.getService( NodeService.class ).get(), context.getService( SitemapCache.class ).get(),
                                           Clock.systemUTC() );
    }

    public String root( final ScriptValue params )
    {
        return sitemap( params ).root();
    }

    public String file( final ScriptValue params, final int number )
    {
        return sitemap( params ).file( number ).orElse( null );
    }

    public String json( final ScriptValue params )
    {
        return sitemap( params ).json();
    }

    private Sitemap sitemap( final ScriptValue params )
    {
        return Objects.requireNonNull( service, "Bean not initialized" ).sitemap( toParams( params.getMap() ) );
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

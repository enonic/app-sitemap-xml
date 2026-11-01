package com.enonic.app.sitemapxml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record SitemapParams(String sitePath, String siteUrl, List<ContentTypeSetting> contentTypes, List<String> ignorePatterns)
{
    public static final String SITE_CONTENT_TYPE = "portal:site";

    public static final String DEFAULT_CHANGEFREQ = "monthly";

    public static final String DEFAULT_PRIORITY = "0.5";

    public SitemapParams
    {
        contentTypes = normalize( contentTypes );
        ignorePatterns = ignorePatterns.stream().filter( pattern -> !pattern.isBlank() ).toList();
    }

    public SitemapParams withSiteUrl( final String url )
    {
        return new SitemapParams( sitePath, url, contentTypes, ignorePatterns );
    }

    public ContentTypeSetting setting( final String contentType )
    {
        return contentTypes.stream()
            .filter( setting -> setting.contentType().equals( contentType ) )
            .findFirst()
            .orElseThrow( () -> new IllegalArgumentException( "Content type not in sitemap: " + contentType ) );
    }

    /**
     * The same params with the host of the site URL in lower case. Host names are case-insensitive, and a site URL taken from the
     * request has the host as the client sent it.
     */
    public SitemapParams withLowerCaseHost()
    {
        final int start = hostStart( siteUrl );
        if ( start < 0 )
        {
            return this;
        }
        final int end = authorityEnd( siteUrl, start );
        final String host = siteUrl.substring( start, end );
        final String lowerCase = host.toLowerCase( Locale.ROOT );
        return lowerCase.equals( host ) ? this : new SitemapParams( sitePath, siteUrl.substring( 0, start ) + lowerCase + siteUrl.substring( end ), contentTypes, ignorePatterns );
    }

    /**
     * Whether the site URL names a port. A site URL taken from the request has whatever port the client sent.
     */
    public boolean hasExplicitPort()
    {
        final int start = hostStart( siteUrl );
        if ( start < 0 )
        {
            return false;
        }
        final String host = siteUrl.substring( start, authorityEnd( siteUrl, start ) );
        return host.startsWith( "[" ) ? host.contains( "]:" ) : host.contains( ":" );
    }

    private static int hostStart( final String url )
    {
        final int scheme = url.indexOf( "://" );
        if ( scheme < 0 )
        {
            return -1;
        }
        final int start = scheme + 3;
        final int userInfo = url.lastIndexOf( '@', authorityEnd( url, start ) - 1 );
        return userInfo >= start ? userInfo + 1 : start;
    }

    private static int authorityEnd( final String url, final int start )
    {
        for ( int i = start; i < url.length(); i++ )
        {
            final char c = url.charAt( i );
            if ( c == '/' || c == '?' || c == '#' )
            {
                return i;
            }
        }
        return url.length();
    }

    public List<String> contentTypeNames()
    {
        return contentTypes.stream().map( ContentTypeSetting::contentType ).toList();
    }

    private static List<ContentTypeSetting> normalize( final List<ContentTypeSetting> contentTypes )
    {
        final Map<String, ContentTypeSetting> byName = new LinkedHashMap<>();
        for ( final ContentTypeSetting setting : contentTypes )
        {
            if ( !setting.contentType().isBlank() )
            {
                byName.put( setting.contentType(), new ContentTypeSetting( setting.contentType(),
                                                                           orDefault( setting.changefreq(), DEFAULT_CHANGEFREQ ),
                                                                           orDefault( setting.priority(), DEFAULT_PRIORITY ) ) );
            }
        }
        byName.putIfAbsent( SITE_CONTENT_TYPE, new ContentTypeSetting( SITE_CONTENT_TYPE, "hourly", "1.0" ) );
        return List.copyOf( byName.values() );
    }

    private static String orDefault( final String value, final String defaultValue )
    {
        return value.isBlank() ? defaultValue : value;
    }
}

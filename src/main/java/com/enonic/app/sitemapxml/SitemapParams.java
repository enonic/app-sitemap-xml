package com.enonic.app.sitemapxml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SitemapParams
{
    public static final String SITE_CONTENT_TYPE = "portal:site";

    public static final String DEFAULT_CHANGEFREQ = "monthly";

    public static final String DEFAULT_PRIORITY = "0.5";

    private final String sitePath;

    private final String siteUrl;

    private final List<ContentTypeSetting> contentTypes;

    private final List<String> ignorePatterns;

    private final int maxItems;

    public SitemapParams( final String sitePath, final String siteUrl, final List<ContentTypeSetting> contentTypes,
                          final List<String> ignorePatterns, final int maxItems )
    {
        if ( maxItems < 1 )
        {
            throw new IllegalArgumentException( "maxItems must be positive: " + maxItems );
        }
        this.sitePath = Objects.requireNonNull( sitePath );
        this.siteUrl = Objects.requireNonNull( siteUrl );
        this.contentTypes = normalize( contentTypes );
        this.ignorePatterns = ignorePatterns.stream().filter( pattern -> !pattern.isBlank() ).collect( Collectors.toUnmodifiableList() );
        this.maxItems = maxItems;
    }

    public String sitePath()
    {
        return sitePath;
    }

    public String siteUrl()
    {
        return siteUrl;
    }

    public List<ContentTypeSetting> contentTypes()
    {
        return contentTypes;
    }

    public List<String> ignorePatterns()
    {
        return ignorePatterns;
    }

    public int maxItems()
    {
        return maxItems;
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
        return lowerCase.equals( host ) ? this : new SitemapParams( sitePath, siteUrl.substring( 0, start ) + lowerCase + siteUrl.substring( end ), contentTypes, ignorePatterns, maxItems );
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
        return contentTypes.stream().map( ContentTypeSetting::contentType ).collect( Collectors.toUnmodifiableList() );
    }

    @Override
    public boolean equals( final Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( !( o instanceof SitemapParams ) )
        {
            return false;
        }
        final SitemapParams that = (SitemapParams) o;
        return maxItems == that.maxItems && sitePath.equals( that.sitePath ) &&
            siteUrl.equals( that.siteUrl ) && contentTypes.equals( that.contentTypes ) && ignorePatterns.equals( that.ignorePatterns );
    }

    @Override
    public int hashCode()
    {
        return Objects.hash( sitePath, siteUrl, contentTypes, ignorePatterns, maxItems );
    }

    @Override
    public String toString()
    {
        return "SitemapParams[" + sitePath + ", " + siteUrl + ", " + contentTypes + ", " + ignorePatterns + ", " + maxItems + "]";
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

package com.enonic.app.sitemapxml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SitemapParams
{
    public static final int MAX_URLS_PER_FILE = 50_000;

    public static final String SITE_CONTENT_TYPE = "portal:site";

    public static final String DEFAULT_CHANGEFREQ = "monthly";

    public static final String DEFAULT_PRIORITY = "0.5";

    private final String sitePath;

    private final String siteUrl;

    private final List<ContentTypeSetting> contentTypes;

    private final List<String> ignorePatterns;

    private final int maxItems;

    private final int urlsPerFile;

    public SitemapParams( final String sitePath, final String siteUrl, final List<ContentTypeSetting> contentTypes,
                          final List<String> ignorePatterns, final int maxItems, final int urlsPerFile )
    {
        if ( maxItems < 1 )
        {
            throw new IllegalArgumentException( "maxItems must be positive: " + maxItems );
        }
        if ( urlsPerFile < 1 || urlsPerFile > MAX_URLS_PER_FILE )
        {
            throw new IllegalArgumentException( "urlsPerFile must be between 1 and " + MAX_URLS_PER_FILE + ": " + urlsPerFile );
        }
        this.sitePath = Objects.requireNonNull( sitePath );
        this.siteUrl = Objects.requireNonNull( siteUrl );
        this.contentTypes = normalize( contentTypes );
        this.ignorePatterns = ignorePatterns.stream().filter( pattern -> !pattern.isBlank() ).collect( Collectors.toUnmodifiableList() );
        this.maxItems = maxItems;
        this.urlsPerFile = urlsPerFile;
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

    public int urlsPerFile()
    {
        return urlsPerFile;
    }

    public ContentTypeSetting setting( final String contentType )
    {
        return contentTypes.stream()
            .filter( setting -> setting.contentType().equals( contentType ) )
            .findFirst()
            .orElseThrow( () -> new IllegalArgumentException( "Content type not in sitemap: " + contentType ) );
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
        return maxItems == that.maxItems && urlsPerFile == that.urlsPerFile && sitePath.equals( that.sitePath ) &&
            siteUrl.equals( that.siteUrl ) && contentTypes.equals( that.contentTypes ) && ignorePatterns.equals( that.ignorePatterns );
    }

    @Override
    public int hashCode()
    {
        return Objects.hash( sitePath, siteUrl, contentTypes, ignorePatterns, maxItems, urlsPerFile );
    }

    @Override
    public String toString()
    {
        return "SitemapParams[" + sitePath + ", " + siteUrl + ", " + contentTypes + ", " + ignorePatterns + ", " + maxItems + ", " +
            urlsPerFile + "]";
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

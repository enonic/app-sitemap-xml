package com.enonic.app.sitemapxml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SitemapParams(String sitePath, String siteUrl, List<ContentTypeSetting> contentTypes, List<String> ignorePatterns,
                            int maxItems, int urlsPerFile)
{
    public static final int MAX_URLS_PER_FILE = 50_000;

    public static final String SITE_CONTENT_TYPE = "portal:site";

    public static final String DEFAULT_CHANGEFREQ = "monthly";

    public static final String DEFAULT_PRIORITY = "0.5";

    public SitemapParams
    {
        if ( maxItems < 1 )
        {
            throw new IllegalArgumentException( "maxItems must be positive: " + maxItems );
        }
        if ( urlsPerFile < 1 || urlsPerFile > MAX_URLS_PER_FILE )
        {
            throw new IllegalArgumentException( "urlsPerFile must be between 1 and " + MAX_URLS_PER_FILE + ": " + urlsPerFile );
        }
        contentTypes = normalize( contentTypes );
        ignorePatterns = ignorePatterns.stream().filter( pattern -> !pattern.isBlank() ).toList();
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

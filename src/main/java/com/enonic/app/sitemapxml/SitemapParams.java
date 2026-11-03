package com.enonic.app.sitemapxml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * What a sitemap lists and how.
 *
 * @param format whether the sitemap is served as {@code sitemap.xml} or headless (through Guillotine): each has its own cached
 *               sitemap, with the files that one needs only
 */
public record SitemapParams(String sitePath, String siteUrl, List<ContentTypeSetting> contentTypes, List<String> ignorePatterns,
                            Format format)
{
    public enum Format
    {
        /**
         * {@code sitemap.xml} and its files, with full URLs.
         */
        XML,
        /**
         * Headless, for the Guillotine {@code sitemap} field: URLs as paths, kept as JSON lines.
         */
        HEADLESS
    }

    public static final String SITE_CONTENT_TYPE = "portal:site";

    public static final String DEFAULT_CHANGEFREQ = "monthly";

    public static final String DEFAULT_PRIORITY = "0.5";

    public SitemapParams( final String sitePath, final String siteUrl, final List<ContentTypeSetting> contentTypes,
                          final List<String> ignorePatterns )
    {
        this( sitePath, siteUrl, contentTypes, ignorePatterns, Format.XML );
    }

    public SitemapParams
    {
        contentTypes = normalize( contentTypes );
        ignorePatterns = ignorePatterns.stream().filter( pattern -> !pattern.isBlank() ).toList();
    }

    public SitemapParams withSiteUrl( final String url )
    {
        return new SitemapParams( sitePath, url, contentTypes, ignorePatterns, format );
    }

    public SitemapParams withFormat( final Format format )
    {
        return new SitemapParams( sitePath, siteUrl, contentTypes, ignorePatterns, format );
    }

    public ContentTypeSetting setting( final String contentType )
    {
        return contentTypes.stream()
            .filter( setting -> setting.contentType().equals( contentType ) )
            .findFirst()
            .orElseThrow( () -> new IllegalArgumentException( "Content type not in sitemap: " + contentType ) );
    }

    /**
     * The same params for a site URL taken from the request: its scheme must be {@code http} or {@code https} and it must have no user
     * info. The host is lower-cased, since host names are case-insensitive and the request has the host as the client sent it; query
     * and fragment are left out.
     *
     * @throws InvalidSiteUrlException when the site URL cannot be a sitemap's
     */
    public SitemapParams forRequestSiteUrl()
    {
        final int scheme = siteUrl.indexOf( "://" );
        final String protocol = scheme < 0 ? "" : siteUrl.substring( 0, scheme );
        if ( !"http".equals( protocol ) && !"https".equals( protocol ) )
        {
            throw new InvalidSiteUrlException( siteUrl );
        }
        final int start = scheme + 3;
        final int end = authorityEnd( siteUrl, start );
        final String authority = siteUrl.substring( start, end );
        if ( authority.isEmpty() || authority.contains( "@" ) )
        {
            throw new InvalidSiteUrlException( siteUrl );
        }
        int pathEnd = end;
        while ( pathEnd < siteUrl.length() && siteUrl.charAt( pathEnd ) != '?' && siteUrl.charAt( pathEnd ) != '#' )
        {
            pathEnd++;
        }
        final String url = protocol + "://" + authority.toLowerCase( Locale.ROOT ) + siteUrl.substring( end, pathEnd );
        return url.equals( siteUrl ) ? this : new SitemapParams( sitePath, url, contentTypes, ignorePatterns, format );
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

    /**
     * The host of the site URL when it names no port, as in {@code https://www.example.com/path}; null when it names a port or has no
     * host.
     */
    public @Nullable String hostWithoutPort()
    {
        final int start = hostStart( siteUrl );
        if ( start < 0 || hasExplicitPort() )
        {
            return null;
        }
        final String host = siteUrl.substring( start, authorityEnd( siteUrl, start ) );
        return host.isEmpty() ? null : host;
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

package com.enonic.app.sitemapxml;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class Sitemap
{
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    private static final String NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone( ZoneOffset.UTC );

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final SitemapParams params;

    private final List<SitemapEntry> entries;

    private final ConcurrentMap<Integer, String> rendered = new ConcurrentHashMap<>();

    public Sitemap( final SitemapParams params, final List<SitemapEntry> entries )
    {
        this.params = params;
        this.entries = List.copyOf( entries );
    }

    public int size()
    {
        return entries.size();
    }

    public int fileCount()
    {
        return Math.max( 1, ( entries.size() + params.urlsPerFile() - 1 ) / params.urlsPerFile() );
    }

    public boolean isIndex()
    {
        return fileCount() > 1;
    }

    public String root()
    {
        return rendered.computeIfAbsent( 0, n -> isIndex() ? index() : urlset( entries ) );
    }

    public Optional<String> file( final int number )
    {
        if ( !isIndex() || number < 1 || number > fileCount() )
        {
            return Optional.empty();
        }
        return Optional.of( rendered.computeIfAbsent( number, n -> urlset( fileEntries( n ) ) ) );
    }

    public String fileUrl( final int number )
    {
        return baseUrl() + "/sitemap-" + number + ".xml";
    }

    public String index()
    {
        final StringBuilder xml = new StringBuilder( XML_DECLARATION ).append( "<sitemapindex xmlns=\"" ).append( NAMESPACE ).append( "\">" );
        for ( int number = 1; number <= fileCount(); number++ )
        {
            xml.append( "<sitemap><loc>" );
            escapeXml( xml, fileUrl( number ) );
            xml.append( "</loc>" );
            fileEntries( number ).stream()
                .map( SitemapEntry::lastModified )
                .filter( Objects::nonNull )
                .max( Instant::compareTo )
                .ifPresent( lastModified -> xml.append( "<lastmod>" ).append( DATE.format( lastModified ) ).append( "</lastmod>" ) );
            xml.append( "</sitemap>" );
        }
        return xml.append( "</sitemapindex>" ).toString();
    }

    public String json()
    {
        final StringBuilder json = new StringBuilder( "[" );
        for ( final SitemapEntry entry : entries )
        {
            final ContentTypeSetting setting = params.setting( entry.contentType() );
            if ( json.length() > 1 )
            {
                json.append( ',' );
            }
            json.append( "{\"changefreq\":" );
            jsonString( json, setting.changefreq() );
            if ( entry.lastModified() != null )
            {
                json.append( ",\"lastmod\":" );
                jsonString( json, entry.lastModified().toString() );
            }
            json.append( ",\"priority\":" );
            jsonString( json, setting.priority() );
            json.append( ",\"loc\":" );
            jsonString( json, loc( entry ) );
            json.append( '}' );
        }
        return json.append( ']' ).toString();
    }

    String loc( final SitemapEntry entry )
    {
        if ( entry.path().isEmpty() )
        {
            return params.siteUrl();
        }
        final StringBuilder url = new StringBuilder( baseUrl() );
        for ( final String segment : entry.path().split( "/" ) )
        {
            if ( !segment.isEmpty() )
            {
                url.append( '/' );
                encodePathSegment( url, segment );
            }
        }
        return url.toString();
    }

    private List<SitemapEntry> fileEntries( final int number )
    {
        final int from = ( number - 1 ) * params.urlsPerFile();
        return entries.subList( from, Math.min( from + params.urlsPerFile(), entries.size() ) );
    }

    private String urlset( final List<SitemapEntry> fileEntries )
    {
        final StringBuilder xml = new StringBuilder( XML_DECLARATION ).append( "<urlset xmlns=\"" ).append( NAMESPACE ).append( "\">" );
        for ( final SitemapEntry entry : fileEntries )
        {
            final ContentTypeSetting setting = params.setting( entry.contentType() );
            xml.append( "<url><loc>" );
            escapeXml( xml, loc( entry ) );
            xml.append( "</loc>" );
            if ( entry.lastModified() != null )
            {
                xml.append( "<lastmod>" ).append( DATE.format( entry.lastModified() ) ).append( "</lastmod>" );
            }
            xml.append( "<changefreq>" );
            escapeXml( xml, setting.changefreq() );
            xml.append( "</changefreq><priority>" );
            escapeXml( xml, setting.priority() );
            xml.append( "</priority></url>" );
        }
        return xml.append( "</urlset>" ).toString();
    }

    private String baseUrl()
    {
        final String siteUrl = params.siteUrl();
        return siteUrl.endsWith( "/" ) ? siteUrl.substring( 0, siteUrl.length() - 1 ) : siteUrl;
    }

    static void encodePathSegment( final StringBuilder out, final String segment )
    {
        for ( final byte b : segment.getBytes( StandardCharsets.UTF_8 ) )
        {
            final char c = (char) ( b & 0xFF );
            if ( ( c >= 'a' && c <= 'z' ) || ( c >= 'A' && c <= 'Z' ) || ( c >= '0' && c <= '9' ) || "-._~!$&'()*+,;=:@".indexOf( c ) >= 0 )
            {
                out.append( c );
            }
            else
            {
                out.append( '%' ).append( HEX[( b >> 4 ) & 0xF] ).append( HEX[b & 0xF] );
            }
        }
    }

    private static void escapeXml( final StringBuilder out, final String value )
    {
        for ( int i = 0; i < value.length(); i++ )
        {
            final char c = value.charAt( i );
            switch ( c )
            {
                case '&':
                    out.append( "&amp;" );
                    break;
                case '<':
                    out.append( "&lt;" );
                    break;
                case '>':
                    out.append( "&gt;" );
                    break;
                case '"':
                    out.append( "&quot;" );
                    break;
                case '\'':
                    out.append( "&apos;" );
                    break;
                default:
                    out.append( c );
            }
        }
    }

    private static void jsonString( final StringBuilder out, final String value )
    {
        out.append( '"' );
        for ( int i = 0; i < value.length(); i++ )
        {
            final char c = value.charAt( i );
            switch ( c )
            {
                case '"':
                    out.append( "\\\"" );
                    break;
                case '\\':
                    out.append( "\\\\" );
                    break;
                case '\n':
                    out.append( "\\n" );
                    break;
                case '\r':
                    out.append( "\\r" );
                    break;
                case '\t':
                    out.append( "\\t" );
                    break;
                default:
                    if ( c < 0x20 )
                    {
                        out.append( String.format( "\\u%04x", (int) c ) );
                    }
                    else
                    {
                        out.append( c );
                    }
            }
        }
        out.append( '"' );
    }
}

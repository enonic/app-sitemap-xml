package com.enonic.app.sitemapxml;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class SitemapRenderer
{
    static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    static final String NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";

    static final String URLSET_START = XML_DECLARATION + "<urlset xmlns=\"" + NAMESPACE + "\">";

    static final String URLSET_END = "</urlset>";

    static final String INDEX_START = XML_DECLARATION + "<sitemapindex xmlns=\"" + NAMESPACE + "\">";

    static final String INDEX_END = "</sitemapindex>";

    static final int LASTMOD_BYTES = "<lastmod>0000-00-00</lastmod>".length();

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone( ZoneOffset.UTC );

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final SitemapParams params;

    SitemapRenderer( final SitemapParams params )
    {
        this.params = params;
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

    String url( final SitemapEntry entry, final String loc )
    {
        final ContentTypeSetting setting = params.setting( entry.contentType() );
        final StringBuilder xml = new StringBuilder( "<url><loc>" );
        escapeXml( xml, loc );
        xml.append( "</loc>" );
        if ( entry.lastModified() != null )
        {
            xml.append( "<lastmod>" ).append( DATE.format( entry.lastModified() ) ).append( "</lastmod>" );
        }
        xml.append( "<changefreq>" );
        escapeXml( xml, setting.changefreq() );
        xml.append( "</changefreq><priority>" );
        escapeXml( xml, setting.priority() );
        return xml.append( "</priority></url>" ).toString();
    }

    String json( final SitemapEntry entry, final String loc )
    {
        final ContentTypeSetting setting = params.setting( entry.contentType() );
        final StringBuilder json = new StringBuilder( "{\"changefreq\":" );
        jsonString( json, setting.changefreq() );
        if ( entry.lastModified() != null )
        {
            json.append( ",\"lastmod\":" );
            jsonString( json, entry.lastModified().toString() );
        }
        json.append( ",\"priority\":" );
        jsonString( json, setting.priority() );
        json.append( ",\"loc\":" );
        jsonString( json, loc );
        return json.append( '}' ).toString();
    }

    String indexEntry( final String fileUrl, final Instant lastModified )
    {
        final StringBuilder xml = new StringBuilder( "<sitemap><loc>" );
        escapeXml( xml, fileUrl );
        xml.append( "</loc>" );
        if ( lastModified != null )
        {
            xml.append( "<lastmod>" ).append( DATE.format( lastModified ) ).append( "</lastmod>" );
        }
        return xml.append( "</sitemap>" ).toString();
    }

    String fileUrl( final String fileName )
    {
        return baseUrl() + "/" + fileName;
    }

    private String baseUrl()
    {
        final String siteUrl = params.siteUrl();
        return siteUrl.endsWith( "/" ) ? siteUrl.substring( 0, siteUrl.length() - 1 ) : siteUrl;
    }

    static long utf8Length( final CharSequence value )
    {
        long bytes = 0;
        for ( int i = 0; i < value.length(); i++ )
        {
            final char c = value.charAt( i );
            if ( c < 0x80 )
            {
                bytes += 1;
            }
            else if ( c < 0x800 )
            {
                bytes += 2;
            }
            else if ( Character.isHighSurrogate( c ) )
            {
                bytes += 4;
                i++;
            }
            else
            {
                bytes += 3;
            }
        }
        return bytes;
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

    static void escapeXml( final StringBuilder out, final String value )
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

    static void jsonString( final StringBuilder out, final String value )
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

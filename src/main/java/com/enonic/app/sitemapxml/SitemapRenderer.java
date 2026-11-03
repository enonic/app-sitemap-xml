package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.fasterxml.jackson.core.JsonGenerator;

final class SitemapRenderer
{
    static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    static final String NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";

    static final String URLSET_START = XML_DECLARATION + "<urlset xmlns=\"" + NAMESPACE + "\">";

    static final String URLSET_END = "</urlset>";

    static final String INDEX_START = XML_DECLARATION + "<sitemapindex xmlns=\"" + NAMESPACE + "\">";

    static final String INDEX_END = "</sitemapindex>";

    static final int INDEX_LASTMOD_BYTES = "<lastmod>0000-00-00T00:00:00Z</lastmod>".length();

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone( ZoneOffset.UTC );

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static final XMLOutputFactory XML_OUTPUT = XMLOutputFactory.newDefaultFactory();

    private final SitemapParams params;

    private final StringWriter buffer = new StringWriter();

    private final XMLStreamWriter xml;

    SitemapRenderer( final SitemapParams params )
    {
        this.params = params;
        try
        {
            this.xml = XML_OUTPUT.createXMLStreamWriter( buffer );
        }
        catch ( XMLStreamException e )
        {
            throw new IllegalStateException( e );
        }
    }

    String loc( final SitemapEntry entry )
    {
        if ( entry.path().isEmpty() )
        {
            return params.siteUrl();
        }
        return baseUrl() + path( entry );
    }

    /**
     * The path of the entry relative to the site, percent-encoded; for the headless sitemap also safe in XML as it is.
     */
    String path( final SitemapEntry entry )
    {
        final StringBuilder path = new StringBuilder();
        for ( final String segment : entry.path().split( "/" ) )
        {
            if ( !segment.isEmpty() )
            {
                path.append( '/' );
                encodePathSegment( path, segment, params.format() == SitemapParams.Format.HEADLESS );
            }
        }
        return path.isEmpty() ? "/" : path.toString();
    }

    String url( final SitemapEntry entry, final String loc )
    {
        final ContentTypeSetting setting = params.setting( entry.contentType() );
        return url( loc, entry.lastModified(), setting.changefreq(), setting.priority() );
    }

    String url( final String loc, final Instant lastModified, final String changefreq, final String priority )
    {
        return element( () -> {
            xml.writeStartElement( "url" );
            textElement( "loc", loc );
            if ( lastModified != null )
            {
                textElement( "lastmod", DATE.format( lastModified ) );
            }
            textElement( "changefreq", changefreq );
            textElement( "priority", priority );
            xml.writeEndElement();
        } );
    }

    /**
     * Writes the path-based entry of the headless sitemap, served through Guillotine. Change frequency and priority are left out
     * when they have default values.
     */
    void urlsetEntry( final JsonGenerator json, final SitemapEntry entry )
        throws IOException
    {
        final ContentTypeSetting setting = params.setting( entry.contentType() );
        json.writeStartObject();
        // percent-encoded, also & and ': clients put it in <loc> as it is
        json.writeStringField( "path", path( entry ) );
        if ( entry.lastModified() != null )
        {
            json.writeStringField( "lastmod", entry.lastModified().toString() );
        }
        if ( !SitemapParams.DEFAULT_CHANGEFREQ.equals( setting.changefreq() ) )
        {
            json.writeStringField( "changefreq", setting.changefreq() );
        }
        if ( !SitemapParams.DEFAULT_PRIORITY.equals( setting.priority() ) )
        {
            json.writeStringField( "priority", setting.priority() );
        }
        json.writeEndObject();
    }

    String indexEntry( final String fileUrl, final Instant lastModified )
    {
        return element( () -> {
            xml.writeStartElement( "sitemap" );
            textElement( "loc", fileUrl );
            if ( lastModified != null )
            {
                textElement( "lastmod", DateTimeFormatter.ISO_INSTANT.format( lastModified.truncatedTo( ChronoUnit.SECONDS ) ) );
            }
            xml.writeEndElement();
        } );
    }

    /**
     * Writes one element with the XML stream writer and returns it, so its size is known before it is added to a file.
     */
    private String element( final XmlContent content )
    {
        buffer.getBuffer().setLength( 0 );
        try
        {
            content.write();
            xml.flush();
        }
        catch ( XMLStreamException e )
        {
            throw new IllegalStateException( e );
        }
        return buffer.toString();
    }

    private void textElement( final String name, final String text )
        throws XMLStreamException
    {
        xml.writeStartElement( name );
        xml.writeCharacters( text );
        xml.writeEndElement();
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

    /**
     * The bytes the given text takes in the XML, escaped and encoded in UTF-8.
     */
    static long xmlTextLength( final String text )
    {
        final StringWriter escaped = new StringWriter();
        try
        {
            final XMLStreamWriter writer = XML_OUTPUT.createXMLStreamWriter( escaped );
            writer.writeCharacters( text );
            writer.flush();
        }
        catch ( XMLStreamException e )
        {
            throw new IllegalStateException( e );
        }
        return utf8Length( escaped.getBuffer() );
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

    /**
     * Percent-encodes a path segment like portal URLs do.
     *
     * @param xmlSafe whether {@code &} and {@code '} are encoded too, so that the result needs no escaping in XML
     */
    static void encodePathSegment( final StringBuilder out, final String segment, final boolean xmlSafe )
    {
        final String allowed = xmlSafe ? "-._~!$()*+,;=:@" : "-._~!$&'()*+,;=:@";
        for ( final byte b : segment.getBytes( StandardCharsets.UTF_8 ) )
        {
            final char c = (char) ( b & 0xFF );
            if ( ( c >= 'a' && c <= 'z' ) || ( c >= 'A' && c <= 'Z' ) || ( c >= '0' && c <= '9' ) || allowed.indexOf( c ) >= 0 )
            {
                out.append( c );
            }
            else
            {
                out.append( '%' ).append( HEX[( b >> 4 ) & 0xF] ).append( HEX[b & 0xF] );
            }
        }
    }

    @FunctionalInterface
    private interface XmlContent
    {
        void write()
            throws XMLStreamException;
    }
}

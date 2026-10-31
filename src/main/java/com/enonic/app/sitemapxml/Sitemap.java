package com.enonic.app.sitemapxml;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Sitemap
{
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    private static final String NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone( ZoneOffset.UTC );

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    static final int MAX_FILES = 50_000;

    static final int MAX_LOC_LENGTH = 2_048;

    private static final Logger LOG = LoggerFactory.getLogger( Sitemap.class );

    private static final String URLSET_START = XML_DECLARATION + "<urlset xmlns=\"" + NAMESPACE + "\">";

    private static final String URLSET_END = "</urlset>";

    private static final String INDEX_START = XML_DECLARATION + "<sitemapindex xmlns=\"" + NAMESPACE + "\">";

    private static final String INDEX_END = "</sitemapindex>";

    private final SitemapParams params;

    private final List<SitemapEntry> entries;

    private final List<List<SitemapEntry>> files;

    private final ConcurrentMap<Integer, String> rendered = new ConcurrentHashMap<>();

    public Sitemap( final SitemapParams params, final List<SitemapEntry> entries )
    {
        this( params, entries, MAX_FILE_BYTES );
    }

    Sitemap( final SitemapParams params, final List<SitemapEntry> entries, final long maxFileBytes )
    {
        this.params = params;
        final List<SitemapEntry> valid =
            entries.stream().filter( entry -> loc( entry ).length() <= MAX_LOC_LENGTH ).collect( Collectors.toList() );
        if ( valid.size() < entries.size() )
        {
            LOG.warn( "Sitemap of {}: {} URLs longer than {} characters left out", params.sitePath(), entries.size() - valid.size(),
                      MAX_LOC_LENGTH );
        }
        this.files = split( valid, maxFileBytes );
        this.entries = files.stream().flatMap( List::stream ).collect( Collectors.toList() );
        if ( this.entries.size() < valid.size() )
        {
            LOG.warn( "Sitemap of {}: {} URLs left out, a sitemap index lists at most {} files", params.sitePath(),
                      valid.size() - this.entries.size(), MAX_FILES );
        }
    }

    public int size()
    {
        return entries.size();
    }

    public int fileCount()
    {
        return files.size();
    }

    public boolean isIndex()
    {
        return fileCount() > 1;
    }

    public String root()
    {
        return rendered.computeIfAbsent( 0, n -> isIndex() ? index() : urlset( files.get( 0 ) ) );
    }

    public Optional<String> file( final int number )
    {
        if ( !isIndex() || number < 1 || number > fileCount() )
        {
            return Optional.empty();
        }
        return Optional.of( rendered.computeIfAbsent( number, n -> urlset( files.get( n - 1 ) ) ) );
    }

    public String fileUrl( final int number )
    {
        return baseUrl() + "/sitemap-" + number + ".xml";
    }

    public String index()
    {
        final StringBuilder xml = new StringBuilder( INDEX_START );
        for ( int number = 1; number <= fileCount(); number++ )
        {
            indexEntry( xml, number );
        }
        return xml.append( INDEX_END ).toString();
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

    private List<List<SitemapEntry>> split( final List<SitemapEntry> valid, final long maxFileBytes )
    {
        final long available = maxFileBytes - utf8Length( URLSET_START ) - utf8Length( URLSET_END );
        final List<List<SitemapEntry>> result = new ArrayList<>();
        List<SitemapEntry> current = new ArrayList<>();
        long currentBytes = 0;
        for ( final SitemapEntry entry : valid )
        {
            final StringBuilder url = new StringBuilder();
            url( url, entry );
            final long bytes = utf8Length( url );
            if ( !current.isEmpty() && ( current.size() >= params.urlsPerFile() || currentBytes + bytes > available ) )
            {
                result.add( current );
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add( entry );
            currentBytes += bytes;
        }
        result.add( current );
        return limitIndex( result );
    }

    private List<List<SitemapEntry>> limitIndex( final List<List<SitemapEntry>> split )
    {
        if ( split.size() == 1 )
        {
            return List.of( List.copyOf( split.get( 0 ) ) );
        }
        final long available = MAX_FILE_BYTES - utf8Length( INDEX_START ) - utf8Length( INDEX_END );
        final List<List<SitemapEntry>> result = new ArrayList<>();
        long bytes = 0;
        for ( final List<SitemapEntry> file : split )
        {
            if ( result.size() == MAX_FILES )
            {
                break;
            }
            final StringBuilder entry = new StringBuilder();
            indexEntry( entry, result.size() + 1, file );
            bytes += utf8Length( entry );
            if ( bytes > available )
            {
                break;
            }
            result.add( List.copyOf( file ) );
        }
        return List.copyOf( result );
    }

    private void indexEntry( final StringBuilder xml, final int number )
    {
        indexEntry( xml, number, files.get( number - 1 ) );
    }

    private void indexEntry( final StringBuilder xml, final int number, final List<SitemapEntry> fileEntries )
    {
        xml.append( "<sitemap><loc>" );
        escapeXml( xml, fileUrl( number ) );
        xml.append( "</loc>" );
        fileEntries.stream()
            .map( SitemapEntry::lastModified )
            .filter( Objects::nonNull )
            .max( Instant::compareTo )
            .ifPresent( lastModified -> xml.append( "<lastmod>" ).append( DATE.format( lastModified ) ).append( "</lastmod>" ) );
        xml.append( "</sitemap>" );
    }

    private void url( final StringBuilder xml, final SitemapEntry entry )
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

    private String urlset( final List<SitemapEntry> fileEntries )
    {
        final StringBuilder xml = new StringBuilder( URLSET_START );
        for ( final SitemapEntry entry : fileEntries )
        {
            url( xml, entry );
        }
        return xml.append( URLSET_END ).toString();
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

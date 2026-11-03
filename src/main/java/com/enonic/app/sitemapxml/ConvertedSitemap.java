package com.enonic.app.sitemapxml;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterInputStream;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.common.io.ByteSource;

import static com.enonic.app.sitemapxml.SitemapRenderer.INDEX_END;
import static com.enonic.app.sitemapxml.SitemapRenderer.INDEX_START;
import static com.enonic.app.sitemapxml.SitemapRenderer.URLSET_END;
import static com.enonic.app.sitemapxml.SitemapRenderer.URLSET_START;

/**
 * {@code sitemap.xml} of a public site whose URLs are taken from the request, converted on the fly from the site's headless sitemap:
 * each path is put after the base URL of the request. The headless sitemap is the same for every host and generated again without a
 * request, so a request never waits for a generation of its own host's sitemap. The files are written as they are read, and compressed
 * as they are written when gzip is asked for; their size is not known in advance. Its files are split so that the XML stays within the
 * limits with a base URL of up to {@link Sitemap#MAX_BASE_URL_LENGTH} characters and bytes in the XML.
 */
final class ConvertedSitemap
    implements SitemapFiles
{
    private static final int ETAG_LENGTH = 32;

    private final Sitemap source;

    private final SitemapParams params;

    private final String baseUrl;

    /**
     * @param source the headless sitemap of the site
     * @param params the params of {@code sitemap.xml}, with the site URL of the request
     * @throws InvalidSiteUrlException when the site URL is longer than {@link Sitemap#MAX_BASE_URL_LENGTH} characters, or takes more
     *                                 bytes than that in the XML (escaped, in UTF-8): the files of the source leave room for that much
     */
    ConvertedSitemap( final Sitemap source, final SitemapParams params )
    {
        final String siteUrl = params.siteUrl();
        this.baseUrl = siteUrl.endsWith( "/" ) ? siteUrl.substring( 0, siteUrl.length() - 1 ) : siteUrl;
        if ( baseUrl.length() > Sitemap.MAX_BASE_URL_LENGTH || SitemapRenderer.xmlTextLength( baseUrl ) > Sitemap.MAX_BASE_URL_LENGTH )
        {
            throw new InvalidSiteUrlException( siteUrl );
        }
        this.source = source;
        this.params = params;
    }

    @Override
    public ByteSource root()
    {
        return isIndex() ? index( ".xml" ) : urlset( 1 );
    }

    @Override
    public ByteSource rootEncoded()
    {
        return gzip( root() );
    }

    @Override
    public ByteSource rootGzip()
    {
        return gzip( isIndex() ? index( ".xml" + Sitemap.GZIP_EXTENSION ) : urlset( 1 ) );
    }

    @Override
    public Optional<ByteSource> file( final int number )
    {
        return validFile( number ) ? Optional.of( urlset( number ) ) : Optional.empty();
    }

    @Override
    public Optional<ByteSource> fileGzip( final int number )
    {
        return file( number ).map( ConvertedSitemap::gzip );
    }

    @Override
    public @Nullable String rootETag( final boolean encoded )
    {
        return isIndex() ? etag( "index", indexDigest(), encoded ) : etag( "file", source.fileDigest( 1 ), encoded );
    }

    @Override
    public @Nullable String rootGzipETag()
    {
        return isIndex() ? etag( "index.gz", indexDigest(), true ) : rootETag( true );
    }

    @Override
    public Optional<String> fileETag( final int number, final boolean gzip )
    {
        return validFile( number ) ? Optional.of( etag( "file", source.fileDigest( number ), gzip ) ) : Optional.empty();
    }

    @Override
    public boolean isIndex()
    {
        return source.fileCount() > 1;
    }

    @Override
    public List<@Nullable Instant> lastModified()
    {
        return source.lastModified();
    }

    @Override
    public @Nullable Instant rootLastModified()
    {
        // the index follows the files of the source and their times, a single file its content: the root changes with the source's
        return source.rootLastModified();
    }

    private boolean validFile( final int number )
    {
        return isIndex() && number >= 1 && number <= source.fileCount();
    }

    private String indexDigest()
    {
        final StringBuilder digests = new StringBuilder();
        for ( int number = 1; number <= source.fileCount(); number++ )
        {
            digests.append( source.fileDigest( number ) ).append( ' ' ).append( source.lastModified().get( number - 1 ) ).append( '\n' );
        }
        return digests.toString();
    }

    /**
     * The entity tag of a converted file: a digest of what it is converted from and the URLs put in it.
     */
    private String etag( final String variant, final String digest, final boolean gzip )
    {
        try
        {
            final MessageDigest sha = MessageDigest.getInstance( "SHA-256" );
            sha.update( ( variant + '\n' + digest + '\n' + params.siteUrl() ).getBytes( StandardCharsets.UTF_8 ) );
            return "\"" + HexFormat.of().formatHex( sha.digest() ).substring( 0, ETAG_LENGTH ) + ( gzip ? "-gzip" : "" ) + "\"";
        }
        catch ( NoSuchAlgorithmException e )
        {
            throw new IllegalStateException( e );
        }
    }

    /**
     * The sitemap index, listing the files with the given extension.
     */
    private ByteSource index( final String extension )
    {
        final List<@Nullable Instant> lastModified = source.lastModified();
        return new ByteSource()
        {
            @Override
            public InputStream openStream()
            {
                final SitemapRenderer renderer = new SitemapRenderer( params );
                return new ChunkInputStream( null )
                {
                    private int next;

                    @Override
                    @Nullable String nextChunk()
                    {
                        final int number = next++;
                        if ( number == 0 )
                        {
                            return INDEX_START;
                        }
                        if ( number <= lastModified.size() )
                        {
                            return renderer.indexEntry( renderer.fileUrl( "sitemap-" + number + extension ),
                                                        lastModified.get( number - 1 ) );
                        }
                        return number == lastModified.size() + 1 ? INDEX_END : null;
                    }
                };
            }
        };
    }

    /**
     * The XML of one file, converted line by line from its headless entries.
     */
    private ByteSource urlset( final int number )
    {
        return new ByteSource()
        {
            @Override
            public InputStream openStream()
                throws IOException
            {
                final SitemapRenderer renderer = new SitemapRenderer( params );
                final BufferedReader lines =
                    new BufferedReader( new InputStreamReader( source.urlset( number ).openStream(), StandardCharsets.UTF_8 ) );
                return new ChunkInputStream( lines )
                {
                    private boolean started;

                    private boolean ended;

                    @Override
                    @Nullable String nextChunk()
                        throws IOException
                    {
                        if ( !started )
                        {
                            started = true;
                            return URLSET_START;
                        }
                        if ( ended )
                        {
                            return null;
                        }
                        String line;
                        do
                        {
                            line = lines.readLine();
                        }
                        while ( line != null && line.isEmpty() );
                        if ( line == null )
                        {
                            ended = true;
                            return URLSET_END;
                        }
                        return url( renderer, line );
                    }
                };
            }
        };
    }

    /**
     * The {@code <url>} of a JSON line of the headless sitemap, read with the streaming parser.
     */
    private String url( final SitemapRenderer renderer, final String line )
        throws IOException
    {
        String path = null;
        String lastmod = null;
        String changefreq = SitemapParams.DEFAULT_CHANGEFREQ;
        String priority = SitemapParams.DEFAULT_PRIORITY;
        try (JsonParser json = Sitemap.JSON.createParser( line ))
        {
            json.nextToken();
            while ( json.nextToken() == JsonToken.FIELD_NAME )
            {
                final String name = json.currentName();
                json.nextToken();
                final String value = json.getText();
                switch ( name )
                {
                    case "path" -> path = value;
                    case "lastmod" -> lastmod = value;
                    case "changefreq" -> changefreq = value;
                    case "priority" -> priority = value;
                    default -> json.skipChildren();
                }
            }
        }
        if ( path == null )
        {
            throw new IOException( "Sitemap entry without path: " + line );
        }
        return renderer.url( "/".equals( path ) ? params.siteUrl() : baseUrl + path, lastmod == null ? null : Instant.parse( lastmod ),
                             changefreq, priority );
    }

    /**
     * Compresses the content as it is read: a gzip header, the deflated content, and a trailer with its CRC and size once it is read.
     */
    static ByteSource gzip( final ByteSource plain )
    {
        return new ByteSource()
        {
            @Override
            public InputStream openStream()
                throws IOException
            {
                final CRC32 crc = new CRC32();
                final CountingInputStream counted = new CountingInputStream( plain.openStream() );
                final CheckedInputStream checked = new CheckedInputStream( counted, crc );
                // for each response: the fastest level, a little larger output for much less work
                final Deflater deflater = new Deflater( Deflater.BEST_SPEED, true );
                final InputStream deflated = new DeflaterInputStream( checked, deflater )
                {
                    @Override
                    public void close()
                        throws IOException
                    {
                        super.close();
                        deflater.end();
                    }
                };
                final byte[] header = {0x1f, (byte) 0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, (byte) 0xff};
                final List<InputStream> parts = List.of( new ByteArrayInputStream( header ), deflated );
                return new SequenceInputStream( new Enumeration<>()
                {
                    private int next;

                    @Override
                    public boolean hasMoreElements()
                    {
                        return next <= parts.size();
                    }

                    @Override
                    public InputStream nextElement()
                    {
                        if ( !hasMoreElements() )
                        {
                            throw new NoSuchElementException();
                        }
                        final int index = next++;
                        if ( index < parts.size() )
                        {
                            return parts.get( index );
                        }
                        // the trailer, once the content is read
                        final long value = crc.getValue();
                        final long size = counted.count();
                        return new ByteArrayInputStream(
                            new byte[]{(byte) value, (byte) ( value >> 8 ), (byte) ( value >> 16 ), (byte) ( value >> 24 ), (byte) size,
                                (byte) ( size >> 8 ), (byte) ( size >> 16 ), (byte) ( size >> 24 )} );
                    }
                } );
            }
        };
    }

    /**
     * An input stream of UTF-8 text produced chunk by chunk.
     */
    private abstract static class ChunkInputStream
        extends InputStream
    {
        private final @Nullable AutoCloseable resource;

        private byte[] chunk = new byte[0];

        private int position;

        private boolean done;

        ChunkInputStream( final @Nullable AutoCloseable resource )
        {
            this.resource = resource;
        }

        /**
         * The next chunk of text, or null at the end.
         */
        abstract @Nullable String nextChunk()
            throws IOException;

        private boolean fill()
            throws IOException
        {
            while ( !done && position >= chunk.length )
            {
                final String next = nextChunk();
                if ( next == null )
                {
                    done = true;
                }
                else
                {
                    chunk = next.getBytes( StandardCharsets.UTF_8 );
                    position = 0;
                }
            }
            return position < chunk.length;
        }

        @Override
        public int read()
            throws IOException
        {
            return fill() ? chunk[position++] & 0xFF : -1;
        }

        @Override
        public int read( final byte[] buffer, final int offset, final int length )
            throws IOException
        {
            if ( length == 0 )
            {
                return 0;
            }
            if ( !fill() )
            {
                return -1;
            }
            final int count = Math.min( length, chunk.length - position );
            System.arraycopy( chunk, position, buffer, offset, count );
            position += count;
            return count;
        }

        @Override
        public void close()
            throws IOException
        {
            if ( resource != null )
            {
                try
                {
                    resource.close();
                }
                catch ( IOException | RuntimeException e )
                {
                    throw e;
                }
                catch ( Exception e )
                {
                    throw new UncheckedIOException( new IOException( e ) );
                }
            }
        }
    }

    private static final class CountingInputStream
        extends java.io.FilterInputStream
    {
        private long count;

        CountingInputStream( final InputStream in )
        {
            super( in );
        }

        @Override
        public int read()
            throws IOException
        {
            final int read = super.read();
            if ( read >= 0 )
            {
                count++;
            }
            return read;
        }

        @Override
        public int read( final byte[] buffer, final int offset, final int length )
            throws IOException
        {
            final int read = super.read( buffer, offset, length );
            if ( read > 0 )
            {
                count += read;
            }
            return read;
        }

        long count()
        {
            return count;
        }
    }
}

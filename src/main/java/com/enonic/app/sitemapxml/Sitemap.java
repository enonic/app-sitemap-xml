package com.enonic.app.sitemapxml;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.google.common.io.ByteSource;
import com.google.common.io.MoreFiles;

import static com.enonic.app.sitemapxml.SitemapRenderer.INDEX_END;
import static com.enonic.app.sitemapxml.SitemapRenderer.INDEX_START;
import static com.enonic.app.sitemapxml.SitemapRenderer.LASTMOD_BYTES;
import static com.enonic.app.sitemapxml.SitemapRenderer.URLSET_END;
import static com.enonic.app.sitemapxml.SitemapRenderer.URLSET_START;
import static com.enonic.app.sitemapxml.SitemapRenderer.utf8Length;

/**
 * A generated sitemap. Its files are kept on disk, gzip-compressed only, and decompressed on the fly when served plain.
 */
public final class Sitemap
{
    static final int MAX_URLS_PER_FILE = 50_000;

    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    static final int MAX_FILES = 50_000;

    static final int MAX_LOC_LENGTH = 2_048;

    static final String GZIP_EXTENSION = ".gz";

    static final String ROOT_FILE = "sitemap.xml";

    static final String PLAIN_INDEX_FILE = "sitemap-index.xml";

    static final JsonFactory JSON = new JsonFactory();

    private static final Logger LOG = LoggerFactory.getLogger( Sitemap.class );

    private final Path directory;

    private final int size;

    private final Map<String, Long> plainSizes;

    private final List<@Nullable Instant> lastModified;

    private Sitemap( final Path directory, final int size, final Map<String, Long> plainSizes, final List<@Nullable Instant> lastModified )
    {
        this.directory = directory;
        this.size = size;
        this.plainSizes = Map.copyOf( plainSizes );
        this.lastModified = Collections.unmodifiableList( new ArrayList<>( lastModified ) );
    }

    /**
     * Starts writing a sitemap to the given directory. Entries are written to the files as they are added.
     */
    public static Builder builder( final Path directory, final SitemapParams params )
    {
        return builder( directory, params, MAX_URLS_PER_FILE, MAX_FILE_BYTES, MAX_FILES );
    }

    static Builder builder( final Path directory, final SitemapParams params, final int maxUrlsPerFile, final long maxFileBytes,
                          final int maxFiles )
    {
        try
        {
            Files.createDirectories( directory );
            return new Builder( directory, params, maxUrlsPerFile, maxFileBytes, maxFiles );
        }
        catch ( IOException e )
        {
            delete( directory );
            throw new UncheckedIOException( e );
        }
    }

    public int size()
    {
        return size;
    }

    public int fileCount()
    {
        return lastModified.size();
    }

    public boolean isIndex()
    {
        return lastModified.size() > 1;
    }

    public ByteSource root()
    {
        return plain( plainRootFile() );
    }

    /**
     * The root sitemap as {@link #root()} returns it, but gzip-compressed, to be sent with {@code Content-Encoding: gzip}.
     */
    public ByteSource rootEncoded()
    {
        return gzip( plainRootFile() );
    }

    public ByteSource rootGzip()
    {
        return gzip( ROOT_FILE );
    }

    public Optional<ByteSource> file( final int number )
    {
        return validFile( number ).map( this::plain );
    }

    public Optional<ByteSource> fileGzip( final int number )
    {
        return validFile( number ).map( this::gzip );
    }

    public List<@Nullable Instant> lastModified()
    {
        return lastModified;
    }

    /**
     * The first {@code count} URLs of the sitemap as a JSON array of path-based entries.
     */
    public String urlsetJson( final int count )
    {
        return jsonArray( json -> {
            int remaining = count;
            for ( int number = 1; number <= lastModified.size() && remaining > 0; number++ )
            {
                remaining -= copyUrlset( json, number, remaining );
            }
        } );
    }

    /**
     * The URLs of one sitemap file as a JSON array of path-based entries, or empty if there is no such file.
     */
    public Optional<String> fileUrlsetJson( final int number )
    {
        if ( number < 1 || number > lastModified.size() )
        {
            return Optional.empty();
        }
        return Optional.of( jsonArray( json -> copyUrlset( json, number, Integer.MAX_VALUE ) ) );
    }

    /**
     * The files of the sitemap as a JSON array of {@code {"number": n, "lastmod": "..."}}.
     */
    public String filesJson()
    {
        return jsonArray( json -> {
            for ( int i = 0; i < lastModified.size(); i++ )
            {
                json.writeStartObject();
                json.writeNumberField( "number", i + 1 );
                final Instant lastmod = lastModified.get( i );
                if ( lastmod != null )
                {
                    json.writeStringField( "lastmod", lastmod.toString() );
                }
                json.writeEndObject();
            }
        } );
    }

    private int copyUrlset( final JsonGenerator json, final int number, final int max )
        throws IOException
    {
        int copied = 0;
        try (BufferedReader lines = new BufferedReader(
            new InputStreamReader( plain( urlsetFileName( number ) ).openStream(), StandardCharsets.UTF_8 ) ))
        {
            String line;
            while ( copied < max && ( line = lines.readLine() ) != null )
            {
                if ( !line.isEmpty() )
                {
                    json.writeRawValue( line );
                    copied++;
                }
            }
        }
        return copied;
    }

    private static String jsonArray( final JsonWriter content )
    {
        final StringWriter out = new StringWriter();
        try (JsonGenerator json = JSON.createGenerator( out ))
        {
            json.writeStartArray();
            content.write( json );
            json.writeEndArray();
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
        return out.toString();
    }

    public void delete()
    {
        delete( directory );
    }

    /**
     * Describes the sitemap in the given properties, for {@link #restore} to read the sitemap back from its directory.
     */
    void store( final Properties properties )
    {
        properties.setProperty( "sitemap.directory", directory.getFileName().toString() );
        properties.setProperty( "sitemap.size", Integer.toString( size ) );
        properties.setProperty( "sitemap.files", Integer.toString( lastModified.size() ) );
        for ( int i = 0; i < lastModified.size(); i++ )
        {
            final Instant lastmod = lastModified.get( i );
            if ( lastmod != null )
            {
                properties.setProperty( "sitemap.lastmod." + ( i + 1 ), lastmod.toString() );
            }
        }
        plainSizes.forEach( ( name, bytes ) -> properties.setProperty( "sitemap.bytes." + name, Long.toString( bytes ) ) );
    }

    /**
     * Reads back a sitemap that {@link #store} described, from its directory in the given parent directory.
     *
     * @throws IllegalArgumentException when the description is incomplete or the files are missing
     */
    static Sitemap restore( final Path parent, final Properties properties )
    {
        final String name = required( properties, "sitemap.directory" );
        final Path directory = parent.resolve( name );
        if ( !directory.getParent().equals( parent ) || !Files.isRegularFile( directory.resolve( ROOT_FILE + GZIP_EXTENSION ) ) )
        {
            throw new IllegalArgumentException( "No sitemap in " + directory );
        }
        final int files = Integer.parseInt( required( properties, "sitemap.files" ) );
        final List<@Nullable Instant> lastModified = new ArrayList<>();
        for ( int number = 1; number <= files; number++ )
        {
            final String lastmod = properties.getProperty( "sitemap.lastmod." + number );
            lastModified.add( lastmod == null ? null : Instant.parse( lastmod ) );
        }
        final Map<String, Long> plainSizes = new HashMap<>();
        for ( final String property : properties.stringPropertyNames() )
        {
            if ( property.startsWith( "sitemap.bytes." ) )
            {
                plainSizes.put( property.substring( "sitemap.bytes.".length() ), Long.parseLong( properties.getProperty( property ) ) );
            }
        }
        return new Sitemap( directory, Integer.parseInt( required( properties, "sitemap.size" ) ), plainSizes, lastModified );
    }

    private static String required( final Properties properties, final String name )
    {
        final String value = properties.getProperty( name );
        if ( value == null )
        {
            throw new IllegalArgumentException( "Missing " + name );
        }
        return value;
    }

    Path directory()
    {
        return directory;
    }

    static String fileName( final int number )
    {
        return "sitemap-" + number + ".xml";
    }

    static String urlsetFileName( final int number )
    {
        return "urlset-" + number + ".jsonl";
    }

    private String plainRootFile()
    {
        return isIndex() ? PLAIN_INDEX_FILE : ROOT_FILE;
    }

    private Optional<String> validFile( final int number )
    {
        if ( !isIndex() || number < 1 || number > lastModified.size() )
        {
            return Optional.empty();
        }
        return Optional.of( fileName( number ) );
    }

    private ByteSource gzip( final String fileName )
    {
        return MoreFiles.asByteSource( directory.resolve( fileName + GZIP_EXTENSION ) );
    }

    private ByteSource plain( final String fileName )
    {
        return new GunzippedSource( gzip( fileName ), plainSizes.get( fileName ) );
    }

    static void delete( final Path directory )
    {
        if ( !Files.exists( directory ) )
        {
            return;
        }
        try (Stream<Path> paths = Files.walk( directory ))
        {
            for ( final Path path : paths.sorted( Comparator.reverseOrder() ).toList() )
            {
                Files.deleteIfExists( path );
            }
        }
        catch ( IOException e )
        {
            LOG.warn( "Could not delete sitemap files in {}", directory, e );
        }
    }

    @FunctionalInterface
    private interface JsonWriter
    {
        void write( JsonGenerator json )
            throws IOException;
    }

    private static final class GunzippedSource
        extends ByteSource
    {
        private final ByteSource gzipped;

        private final @Nullable Long size;

        GunzippedSource( final ByteSource gzipped, final @Nullable Long size )
        {
            this.gzipped = gzipped;
            this.size = size;
        }

        @Override
        public InputStream openStream()
            throws IOException
        {
            return new GZIPInputStream( gzipped.openStream() );
        }

        @Override
        public com.google.common.base.Optional<Long> sizeIfKnown()
        {
            return com.google.common.base.Optional.fromNullable( size );
        }
    }

    /**
     * Counts the UTF-8 bytes written, which is the size of the file when served plain.
     */
    private static final class CountingWriter
        extends Writer
    {
        private final Writer delegate;

        private long bytes;

        CountingWriter( final Writer delegate )
        {
            this.delegate = delegate;
        }

        @Override
        public void write( final String value )
            throws IOException
        {
            bytes += utf8Length( value );
            delegate.write( value );
        }

        @Override
        public void write( final char[] chars, final int offset, final int length )
            throws IOException
        {
            bytes += utf8Length( CharBuffer.wrap( chars, offset, length ) );
            delegate.write( chars, offset, length );
        }

        @Override
        public void flush()
            throws IOException
        {
            delegate.flush();
        }

        @Override
        public void close()
            throws IOException
        {
            delegate.close();
        }

        long bytes()
        {
            return bytes;
        }
    }

    /**
     * Writes the files of a sitemap as entries are added, so a sitemap is never held in memory. Files are split by the limits of the
     * sitemap protocol: at most 50 000 URLs and 50MB (uncompressed) per file, at most 50 000 files and 50MB in the index.
     * Closing a builder that is not built deletes what was written.
     */
    public static final class Builder
        implements Closeable
    {
        private final Path directory;

        private final SitemapParams params;

        private final SitemapRenderer renderer;

        private final int maxUrlsPerFile;

        private final long maxFileBytes;

        private final int maxFiles;

        private final List<@Nullable Instant> lastModified = new ArrayList<>();

        private final Map<String, Long> plainSizes = new HashMap<>();

        private @Nullable CountingWriter file;

        private @Nullable CountingWriter urlsetFile;

        private @Nullable JsonGenerator urlset;

        private int size;

        private int fileUrls;

        private long fileBytes;

        private long indexBytes;

        private int tooLong;

        private int leftOut;

        private boolean built;

        private Builder( final Path directory, final SitemapParams params, final int maxUrlsPerFile, final long maxFileBytes,
                        final int maxFiles )
        {
            this.directory = directory;
            this.params = params;
            this.renderer = new SitemapRenderer( params );
            this.maxUrlsPerFile = maxUrlsPerFile;
            this.maxFileBytes = maxFileBytes;
            this.maxFiles = maxFiles;
            this.indexBytes = utf8Length( INDEX_START ) + utf8Length( INDEX_END );
        }

        public void add( final SitemapEntry entry )
        {
            try
            {
                final String loc = renderer.loc( entry );
                if ( loc.length() > MAX_LOC_LENGTH )
                {
                    tooLong++;
                    return;
                }
                final String url = renderer.url( entry, loc );
                if ( leftOut > 0 || !reserve( utf8Length( url ) ) )
                {
                    leftOut++;
                    return;
                }
                Objects.requireNonNull( file ).write( url );
                renderer.urlsetEntry( Objects.requireNonNull( urlset ), entry );
                fileUrls++;
                lastModified.set( lastModified.size() - 1, latest( lastModified.getLast(), entry.lastModified() ) );
                size++;
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }
        }

        public Sitemap build()
        {
            try
            {
                closeFile();
                if ( tooLong > 0 )
                {
                    LOG.warn( "Sitemap of {}: {} URLs longer than {} characters left out", params.sitePath(), tooLong, MAX_LOC_LENGTH );
                }
                if ( leftOut > 0 )
                {
                    LOG.warn( "Sitemap of {}: {} URLs left out, a sitemap index lists at most {} files and {} bytes", params.sitePath(),
                              leftOut, maxFiles, MAX_FILE_BYTES );
                }

                if ( lastModified.isEmpty() )
                {
                    writeEmpty();
                }
                else if ( lastModified.size() == 1 )
                {
                    Files.move( gzPath( fileName( 1 ) ), gzPath( ROOT_FILE ) );
                    plainSizes.put( ROOT_FILE, plainSizes.remove( fileName( 1 ) ) );
                }
                else
                {
                    writeIndex( ROOT_FILE, ".xml" + GZIP_EXTENSION );
                    writeIndex( PLAIN_INDEX_FILE, ".xml" );
                }
                built = true;
                return new Sitemap( directory, size, plainSizes, lastModified );
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }
        }

        private void writeEmpty()
            throws IOException
        {
            final CountingWriter root = open( ROOT_FILE );
            root.write( URLSET_START + URLSET_END );
            close( root, ROOT_FILE );
            close( open( urlsetFileName( 1 ) ), urlsetFileName( 1 ) );
            lastModified.add( null );
        }

        private void writeIndex( final String name, final String extension )
            throws IOException
        {
            final CountingWriter index = open( name );
            index.write( INDEX_START );
            for ( int number = 1; number <= lastModified.size(); number++ )
            {
                index.write( renderer.indexEntry( renderer.fileUrl( "sitemap-" + number + extension ), lastModified.get( number - 1 ) ) );
            }
            index.write( INDEX_END );
            close( index, name );
        }

        private boolean reserve( final long urlBytes )
            throws IOException
        {
            final long available = maxFileBytes - utf8Length( URLSET_START ) - utf8Length( URLSET_END );
            if ( file != null && fileUrls < maxUrlsPerFile && fileBytes + urlBytes <= available )
            {
                fileBytes += urlBytes;
                return true;
            }

            final int number = lastModified.size() + 1;
            final long indexEntryBytes =
                utf8Length( renderer.indexEntry( renderer.fileUrl( fileName( number ) + GZIP_EXTENSION ), null ) ) + LASTMOD_BYTES;
            if ( number > maxFiles || indexBytes + indexEntryBytes > MAX_FILE_BYTES )
            {
                return false;
            }
            closeFile();
            indexBytes += indexEntryBytes;
            file = open( fileName( number ) );
            file.write( URLSET_START );
            urlsetFile = open( urlsetFileName( number ) );
            urlset = JSON.createGenerator( urlsetFile );
            urlset.setRootValueSeparator( new SerializedString( "\n" ) );
            lastModified.add( null );
            fileUrls = 0;
            fileBytes = urlBytes;
            return true;
        }

        private void closeFile()
            throws IOException
        {
            if ( file != null )
            {
                file.write( URLSET_END );
                close( file, fileName( lastModified.size() ) );
                file = null;
                Objects.requireNonNull( urlset ).close();
                urlset = null;
                plainSizes.put( urlsetFileName( lastModified.size() ), Objects.requireNonNull( urlsetFile ).bytes() );
                urlsetFile = null;
            }
        }

        private CountingWriter open( final String name )
            throws IOException
        {
            return new CountingWriter( new BufferedWriter(
                new OutputStreamWriter( new GZIPOutputStream( Files.newOutputStream( gzPath( name ) ) ), StandardCharsets.UTF_8 ) ) );
        }

        private void close( final CountingWriter writer, final String name )
            throws IOException
        {
            writer.close();
            plainSizes.put( name, writer.bytes() );
        }

        private Path gzPath( final String name )
        {
            return directory.resolve( name + GZIP_EXTENSION );
        }

        @Override
        public void close()
        {
            if ( built )
            {
                return;
            }
            for ( final Closeable closeable : new Closeable[]{file, urlset} )
            {
                try
                {
                    if ( closeable != null )
                    {
                        closeable.close();
                    }
                }
                catch ( IOException e )
                {
                    LOG.debug( "Could not close sitemap file in {}", directory, e );
                }
            }
            delete( directory );
        }

        private static @Nullable Instant latest( final @Nullable Instant a, final @Nullable Instant b )
        {
            if ( a == null )
            {
                return b;
            }
            return b == null || a.isAfter( b ) ? a : b;
        }
    }
}

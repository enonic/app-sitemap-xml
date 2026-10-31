package com.enonic.app.sitemapxml;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
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
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteSource;
import com.google.common.io.MoreFiles;

import static com.enonic.app.sitemapxml.SitemapRenderer.INDEX_END;
import static com.enonic.app.sitemapxml.SitemapRenderer.INDEX_START;
import static com.enonic.app.sitemapxml.SitemapRenderer.LASTMOD_BYTES;
import static com.enonic.app.sitemapxml.SitemapRenderer.URLSET_END;
import static com.enonic.app.sitemapxml.SitemapRenderer.URLSET_START;
import static com.enonic.app.sitemapxml.SitemapRenderer.utf8Length;

public final class Sitemap
{
    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    static final int MAX_FILES = 50_000;

    static final int MAX_LOC_LENGTH = 2_048;

    static final String GZIP_EXTENSION = ".gz";

    static final String ROOT_FILE = "sitemap.xml";

    static final String JSON_FILE = "sitemap.json";

    static final String PLAIN_INDEX_FILE = "sitemap-index.xml";

    private static final Logger LOG = LoggerFactory.getLogger( Sitemap.class );

    private final Path directory;

    private final int fileCount;

    private final int size;

    private final Map<String, Long> plainSizes;

    private final List<@Nullable Instant> lastModified;

    private Sitemap( final Path directory, final int fileCount, final int size, final Map<String, Long> plainSizes,
                     final List<@Nullable Instant> lastModified )
    {
        this.directory = directory;
        this.fileCount = fileCount;
        this.size = size;
        this.plainSizes = Map.copyOf( plainSizes );
        this.lastModified = Collections.unmodifiableList( new ArrayList<>( lastModified ) );
    }

    public static Sitemap write( final Path directory, final SitemapParams params, final List<SitemapEntry> entries )
    {
        return write( directory, params, entries, MAX_FILE_BYTES, MAX_FILES );
    }

    static Sitemap write( final Path directory, final SitemapParams params, final List<SitemapEntry> entries, final long maxFileBytes,
                          final int maxFiles )
    {
        try
        {
            Files.createDirectories( directory );
            try (FileWriter writer = new FileWriter( directory, params, maxFileBytes, maxFiles ))
            {
                return writer.write( entries );
            }
        }
        catch ( IOException e )
        {
            delete( directory );
            throw new UncheckedIOException( e );
        }
        catch ( RuntimeException e )
        {
            delete( directory );
            throw e;
        }
    }

    public int size()
    {
        return size;
    }

    public int fileCount()
    {
        return fileCount;
    }

    public boolean isIndex()
    {
        return fileCount > 1;
    }

    public ByteSource root()
    {
        return plain( isIndex() ? PLAIN_INDEX_FILE : ROOT_FILE );
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

    public ByteSource json()
    {
        return plain( JSON_FILE );
    }

    public List<@Nullable Instant> lastModified()
    {
        return lastModified;
    }

    public String urlsetJson( final int count )
    {
        final StringBuilder json = new StringBuilder( "[" );
        int remaining = count;
        for ( int number = 1; number <= lastModified.size() && remaining > 0; number++ )
        {
            remaining -= appendUrlset( json, number, remaining );
        }
        return json.append( ']' ).toString();
    }

    public Optional<String> fileUrlsetJson( final int number )
    {
        if ( number < 1 || number > lastModified.size() )
        {
            return Optional.empty();
        }
        final StringBuilder json = new StringBuilder( "[" );
        appendUrlset( json, number, Integer.MAX_VALUE );
        return Optional.of( json.append( ']' ).toString() );
    }

    private int appendUrlset( final StringBuilder json, final int number, final int max )
    {
        int appended = 0;
        try (BufferedReader lines = new BufferedReader(
            new InputStreamReader( plain( urlsetFileName( number ) ).openStream(), StandardCharsets.UTF_8 ) ))
        {
            String line;
            while ( appended < max && ( line = lines.readLine() ) != null )
            {
                if ( json.length() > 1 )
                {
                    json.append( ',' );
                }
                json.append( line );
                appended++;
            }
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
        return appended;
    }

    public void delete()
    {
        delete( directory );
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

    private Optional<String> validFile( final int number )
    {
        if ( !isIndex() || number < 1 || number > fileCount )
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
            write( new String( chars, offset, length ) );
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

    private static final class FileWriter
        implements Closeable
    {
        private final Path directory;

        private final SitemapParams params;

        private final SitemapRenderer renderer;

        private final long maxFileBytes;

        private final int maxFiles;

        private final List<@Nullable Instant> lastModified = new ArrayList<>();

        private final Map<String, Long> plainSizes = new HashMap<>();

        private @Nullable CountingWriter file;

        private @Nullable CountingWriter urlset;

        private int fileUrls;

        private long fileBytes;

        private long indexBytes;

        FileWriter( final Path directory, final SitemapParams params, final long maxFileBytes, final int maxFiles )
        {
            this.directory = directory;
            this.params = params;
            this.renderer = new SitemapRenderer( params );
            this.maxFileBytes = maxFileBytes;
            this.maxFiles = maxFiles;
            this.indexBytes = utf8Length( INDEX_START ) + utf8Length( INDEX_END );
        }

        Sitemap write( final List<SitemapEntry> entries )
            throws IOException
        {
            int size = 0;
            int tooLong = 0;
            try (CountingWriter json = open( JSON_FILE ))
            {
                json.write( "[" );
                for ( final SitemapEntry entry : entries )
                {
                    final String loc = renderer.loc( entry );
                    if ( loc.length() > MAX_LOC_LENGTH )
                    {
                        tooLong++;
                        continue;
                    }
                    final String url = renderer.url( entry, loc );
                    if ( !reserve( utf8Length( url ) ) )
                    {
                        LOG.warn( "Sitemap of {}: {} URLs left out, a sitemap index lists at most {} files and {} bytes",
                                  params.sitePath(), entries.size() - size - tooLong, maxFiles, MAX_FILE_BYTES );
                        break;
                    }
                    Objects.requireNonNull( file ).write( url );
                    Objects.requireNonNull( urlset ).write( renderer.urlsetEntry( entry ) + "\n" );
                    fileUrls++;
                    lastModified.set( lastModified.size() - 1, latest( lastModified.getLast(), entry.lastModified() ) );

                    if ( size > 0 )
                    {
                        json.write( "," );
                    }
                    json.write( renderer.json( entry, loc ) );
                    size++;
                }
                json.write( "]" );
                close( json, JSON_FILE );
            }
            closeFile();
            if ( tooLong > 0 )
            {
                LOG.warn( "Sitemap of {}: {} URLs longer than {} characters left out", params.sitePath(), tooLong, MAX_LOC_LENGTH );
            }

            final int fileCount = lastModified.size();
            if ( fileCount == 0 )
            {
                try (CountingWriter root = open( ROOT_FILE ))
                {
                    root.write( URLSET_START + URLSET_END );
                    close( root, ROOT_FILE );
                }
                try (CountingWriter empty = open( urlsetFileName( 1 ) ))
                {
                    close( empty, urlsetFileName( 1 ) );
                }
                lastModified.add( null );
                return new Sitemap( directory, 1, size, plainSizes, lastModified );
            }
            if ( fileCount == 1 )
            {
                Files.move( gzPath( fileName( 1 ) ), gzPath( ROOT_FILE ) );
                plainSizes.put( ROOT_FILE, plainSizes.remove( fileName( 1 ) ) );
                return new Sitemap( directory, 1, size, plainSizes, lastModified );
            }

            writeIndex( ROOT_FILE, ".xml" + GZIP_EXTENSION );
            writeIndex( PLAIN_INDEX_FILE, ".xml" );
            return new Sitemap( directory, fileCount, size, plainSizes, lastModified );
        }

        private void writeIndex( final String name, final String extension )
            throws IOException
        {
            try (CountingWriter index = open( name ))
            {
                index.write( INDEX_START );
                for ( int number = 1; number <= lastModified.size(); number++ )
                {
                    index.write(
                        renderer.indexEntry( renderer.fileUrl( "sitemap-" + number + extension ), lastModified.get( number - 1 ) ) );
                }
                index.write( INDEX_END );
                close( index, name );
            }
        }

        private boolean reserve( final long urlBytes )
            throws IOException
        {
            final long available = maxFileBytes - utf8Length( URLSET_START ) - utf8Length( URLSET_END );
            if ( file != null && fileUrls < params.urlsPerFile() && fileBytes + urlBytes <= available )
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
            urlset = open( urlsetFileName( number ) );
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
                close( Objects.requireNonNull( urlset ), urlsetFileName( lastModified.size() ) );
                urlset = null;
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
            throws IOException
        {
            if ( file != null )
            {
                file.close();
                file = null;
            }
            if ( urlset != null )
            {
                urlset.close();
                urlset = null;
            }
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

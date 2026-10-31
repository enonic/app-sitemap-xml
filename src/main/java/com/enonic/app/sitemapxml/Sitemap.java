package com.enonic.app.sitemapxml;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    static final String ROOT_FILE = "sitemap.xml";

    static final String JSON_FILE = "sitemap.json";

    private static final Logger LOG = LoggerFactory.getLogger( Sitemap.class );

    private final Path directory;

    private final int fileCount;

    private final int size;

    private Sitemap( final Path directory, final int fileCount, final int size )
    {
        this.directory = directory;
        this.fileCount = fileCount;
        this.size = size;
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
            try (Writer writer = new Writer( directory, params, maxFileBytes, maxFiles ))
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
        return source( ROOT_FILE );
    }

    public Optional<ByteSource> file( final int number )
    {
        if ( !isIndex() || number < 1 || number > fileCount )
        {
            return Optional.empty();
        }
        return Optional.of( source( fileName( number ) ) );
    }

    public ByteSource json()
    {
        return source( JSON_FILE );
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

    private ByteSource source( final String fileName )
    {
        return MoreFiles.asByteSource( directory.resolve( fileName ) );
    }

    static void delete( final Path directory )
    {
        if ( !Files.exists( directory ) )
        {
            return;
        }
        try (Stream<Path> paths = Files.walk( directory ))
        {
            for ( final Path path : paths.sorted( Comparator.reverseOrder() ).collect( Collectors.toList() ) )
            {
                Files.deleteIfExists( path );
            }
        }
        catch ( IOException e )
        {
            LOG.warn( "Could not delete sitemap files in {}", directory, e );
        }
    }

    private static final class Writer
        implements Closeable
    {
        private final Path directory;

        private final SitemapParams params;

        private final SitemapRenderer renderer;

        private final long maxFileBytes;

        private final int maxFiles;

        private final List<Instant> lastModified = new ArrayList<>();

        private BufferedWriter file;

        private int fileUrls;

        private long fileBytes;

        private long indexBytes;

        Writer( final Path directory, final SitemapParams params, final long maxFileBytes, final int maxFiles )
        {
            this.maxFiles = maxFiles;
            this.directory = directory;
            this.params = params;
            this.renderer = new SitemapRenderer( params );
            this.maxFileBytes = maxFileBytes;
            this.indexBytes = utf8Length( INDEX_START ) + utf8Length( INDEX_END );
        }

        Sitemap write( final List<SitemapEntry> entries )
            throws IOException
        {
            int size = 0;
            int tooLong = 0;
            try (BufferedWriter json = Files.newBufferedWriter( directory.resolve( JSON_FILE ), StandardCharsets.UTF_8 ))
            {
                json.write( '[' );
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
                    fileUrls++;
                    lastModified.set( lastModified.size() - 1, latest( lastModified.get( lastModified.size() - 1 ), entry.lastModified() ) );

                    if ( size > 0 )
                    {
                        json.write( ',' );
                    }
                    json.write( renderer.json( entry, loc ) );
                    size++;
                }
                json.write( ']' );
            }
            closeFile();
            if ( tooLong > 0 )
            {
                LOG.warn( "Sitemap of {}: {} URLs longer than {} characters left out", params.sitePath(), tooLong, MAX_LOC_LENGTH );
            }

            final int fileCount = lastModified.size();
            if ( fileCount <= 1 )
            {
                final Path single = directory.resolve( fileName( 1 ) );
                if ( fileCount == 1 )
                {
                    Files.move( single, directory.resolve( ROOT_FILE ) );
                }
                else
                {
                    Files.writeString( directory.resolve( ROOT_FILE ), URLSET_START + URLSET_END, StandardCharsets.UTF_8 );
                }
                return new Sitemap( directory, 1, size );
            }

            try (BufferedWriter index = Files.newBufferedWriter( directory.resolve( ROOT_FILE ), StandardCharsets.UTF_8 ))
            {
                index.write( INDEX_START );
                for ( int number = 1; number <= fileCount; number++ )
                {
                    index.write( renderer.indexEntry( renderer.fileUrl( fileName( number ) ), lastModified.get( number - 1 ) ) );
                }
                index.write( INDEX_END );
            }
            return new Sitemap( directory, fileCount, size );
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
                utf8Length( renderer.indexEntry( renderer.fileUrl( fileName( number ) ), null ) ) + LASTMOD_BYTES;
            if ( number > maxFiles || indexBytes + indexEntryBytes > MAX_FILE_BYTES )
            {
                return false;
            }
            closeFile();
            indexBytes += indexEntryBytes;
            file = Files.newBufferedWriter( directory.resolve( fileName( number ) ), StandardCharsets.UTF_8 );
            file.write( URLSET_START );
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
                file.close();
                file = null;
            }
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
        }

        private static Instant latest( final Instant a, final Instant b )
        {
            if ( a == null )
            {
                return b;
            }
            return b == null || a.isAfter( b ) ? a : b;
        }
    }
}

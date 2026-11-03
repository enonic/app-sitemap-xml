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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.io.ByteSource;
import com.google.common.io.MoreFiles;

import static com.enonic.app.sitemapxml.SitemapRenderer.INDEX_END;
import static com.enonic.app.sitemapxml.SitemapRenderer.INDEX_START;
import static com.enonic.app.sitemapxml.SitemapRenderer.INDEX_LASTMOD_BYTES;
import static com.enonic.app.sitemapxml.SitemapRenderer.URLSET_END;
import static com.enonic.app.sitemapxml.SitemapRenderer.URLSET_START;
import static com.enonic.app.sitemapxml.SitemapRenderer.utf8Length;

/**
 * A generated sitemap. Its files are kept gzip-compressed only, and decompressed on the fly when served plain. They are written to a
 * local directory, and then stored as attachments of a node, see {@link RepositorySitemapStore}.
 */
public final class Sitemap
    implements SitemapFiles
{
    static final int MAX_URLS_PER_FILE = 50_000;

    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private static final int ETAG_LENGTH = 32;

    static final int MAX_FILES = 50_000;

    static final int MAX_LOC_LENGTH = 2_048;

    /**
     * The longest base URL a headless client is assumed to put in front of the paths. Paths longer than the rest of
     * {@link #MAX_LOC_LENGTH} are left out of a headless sitemap.
     */
    static final int MAX_BASE_URL_LENGTH = 360;

    /**
     * The bytes a headless client is assumed to write for a URL besides its path, and for each entry of its sitemap index: a
     * headless sitemap is split as if each URL took its percent-encoded path plus this many bytes. A round number: what Next.js
     * writes for a sitemap entry besides the URL ({@code <url>}, {@code <loc>}, {@code <lastmod>}, {@code <changefreq>},
     * {@code <priority>}, each on its own line, 133 bytes with a full time) leaves room for a base URL of {@link #MAX_BASE_URL_LENGTH}
     * characters.
     */
    static final int RESERVED_BYTES_PER_URL = 500;

    static final String GZIP_EXTENSION = ".gz";

    static final String ROOT_FILE = "sitemap.xml";

    static final String PLAIN_INDEX_FILE = "sitemap-index.xml";

    static final JsonFactory JSON = new JsonFactory();

    private static final Logger LOG = LoggerFactory.getLogger( Sitemap.class );

    private final FileSource files;

    private final Path directory;

    private final int size;

    private final Map<String, Long> plainSizes;

    private final List<Instant> lastModified;

    private final List<String> digests;

    private final Map<String, String> namedDigests;

    private final long count;

    private final Instant cutoff;

    private final Instant modified;

    private Sitemap( final FileSource files, final Path directory, final int size, final Map<String, Long> plainSizes,
                     final List<Instant> lastModified, final List<String> digests, final Map<String, String> namedDigests )
    {
        this( files, directory, size, plainSizes, lastModified, digests, namedDigests, -1, null, null );
    }

    private Sitemap( final FileSource files, final Path directory, final int size, final Map<String, Long> plainSizes,
                     final List<Instant> lastModified, final List<String> digests, final Map<String, String> namedDigests,
                     final long count, final Instant cutoff, final Instant modified )
    {
        this.count = count;
        this.cutoff = cutoff;
        this.modified = modified;
        this.digests = List.copyOf( digests );
        this.namedDigests = Map.copyOf( namedDigests );
        this.files = files;
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

    /**
     * The entity tag of {@link #root()}, or of {@link #rootEncoded()} when encoded: the first 16 bytes of the digest of the uncompressed
     * content, with a {@code -gzip} suffix for gzip-compressed variants. It is the same on every cluster node holding the same sitemap,
     * since the files are written in a deterministic order.
     */
    public String rootETag( final boolean encoded )
    {
        return etag( plainRootFile(), encoded );
    }

    public String rootGzipETag()
    {
        return etag( ROOT_FILE, true );
    }

    public Optional<String> fileETag( final int number, final boolean gzip )
    {
        return validFile( number ).map( name -> etag( name, gzip ) );
    }

    public List<Instant> lastModified()
    {
        return lastModified;
    }

    /**
     * When the root last changed: {@code sitemap.xml}, a sitemap index or the only file. It is kept while the root stays the same, so
     * it never goes back, also when a file leaves the index.
     */
    @Override
    public Instant rootLastModified()
    {
        return modified != null ? modified : SitemapFiles.super.rootLastModified();
    }

    /**
     * The contents the sitemap was generated from, as read, or -1 when not counted.
     */
    long count()
    {
        return count;
    }

    /**
     * When the contents were counted: contents changed since were not, or may not have been, read.
     */
    Instant cutoff()
    {
        return cutoff;
    }

    /**
     * The same sitemap, generated from the given number of contents, which had not changed since the given time.
     */
    Sitemap withCount( final long count, final Instant cutoff )
    {
        return new Sitemap( files, directory, size, plainSizes, lastModified, digests, namedDigests, count, cutoff, modified );
    }

    /**
     * The same sitemap with the time its root last changed: that of the previous sitemap while the root is the same, the given time
     * otherwise.
     */
    private Sitemap withModified( final Sitemap previous, final Instant now )
    {
        final Instant time = previous != null && previous.modified != null && previous.rootContent().equals( rootContent() )
            ? previous.modified
            : now;
        return new Sitemap( files, directory, size, plainSizes, lastModified, digests, namedDigests, count, cutoff, time );
    }

    /**
     * What the root is made of: the files an index lists, with the time each last changed, or the content of the only file.
     */
    private String rootContent()
    {
        return isIndex() ? "index " + lastModified : "file " + ( digests.isEmpty() ? "" : digests.get( 0 ) );
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

    /**
     * Deletes the local directory the sitemap was written to, if any.
     */
    public void delete()
    {
        if ( directory != null )
        {
            delete( directory );
        }
    }

    Path directory()
    {
        return directory;
    }

    /**
     * Describes the sitemap, for {@link #restore} to read the sitemap back from its directory.
     */
    ObjectNode store()
    {
        final ObjectNode description = JsonNodeFactory.instance.objectNode();
        description.put( "size", size );
        if ( count >= 0 )
        {
            description.put( "count", count );
        }
        if ( cutoff != null )
        {
            description.put( "cutoff", cutoff.toString() );
        }
        if ( modified != null )
        {
            description.put( "modified", modified.toString() );
        }
        final ArrayNode files = description.putArray( "files" );
        for ( int i = 0; i < lastModified.size(); i++ )
        {
            final ObjectNode file = files.addObject();
            final Instant lastmod = lastModified.get( i );
            if ( lastmod != null )
            {
                file.put( "lastmod", lastmod.toString() );
            }
            file.put( "digest", digests.get( i ) );
        }
        final ObjectNode bytes = description.putObject( "bytes" );
        new TreeMap<>( plainSizes ).forEach( bytes::put );
        final ObjectNode index = description.putObject( "namedDigests" );
        new TreeMap<>( namedDigests ).forEach( index::put );
        return description;
    }

    /**
     * Reads back a sitemap that {@link #store} described, with its files in the given directory.
     *
     * @throws IllegalArgumentException when the description is incomplete
     */
    static Sitemap restore( final Path directory, final JsonNode description )
    {
        final List<Instant> lastModified = new ArrayList<>();
        final List<String> digests = new ArrayList<>();
        for ( final JsonNode file : description.required( "files" ) )
        {
            final JsonNode lastmod = file.get( "lastmod" );
            lastModified.add( lastmod == null ? null : Instant.parse( lastmod.asText() ) );
            digests.add( file.required( "digest" ).asText() );
        }
        final Map<String, Long> plainSizes = new HashMap<>();
        for ( final Map.Entry<String, JsonNode> file : description.required( "bytes" ).properties() )
        {
            plainSizes.put( file.getKey(), file.getValue().asLong() );
        }
        final Map<String, String> namedDigests = new HashMap<>();
        final JsonNode index = description.get( "namedDigests" );
        if ( index != null )
        {
            for ( final Map.Entry<String, JsonNode> file : index.properties() )
            {
                namedDigests.put( file.getKey(), file.getValue().asText() );
            }
        }
        final JsonNode count = description.get( "count" );
        final JsonNode cutoff = description.get( "cutoff" );
        final JsonNode modified = description.get( "modified" );
        return new Sitemap( FileSource.directory( directory ), directory, description.required( "size" ).asInt(), plainSizes, lastModified, digests, namedDigests,
                            count == null ? -1 : count.asLong(), cutoff == null ? null : Instant.parse( cutoff.asText() ),
                            modified == null ? null : Instant.parse( modified.asText() ) );
    }

    static String fileName( final int number )
    {
        return "sitemap-" + number + ".xml";
    }

    static String urlsetFileName( final int number )
    {
        return "urlset-" + number + ".jsonl";
    }

    private String etag( final String name, final boolean gzip )
    {
        final String digest = digest( name );
        if ( digest == null )
        {
            return null;
        }
        return "\"" + digest.substring( 0, ETAG_LENGTH ) + ( gzip ? "-gzip" : "" ) + "\"";
    }

    private String digest( final String name )
    {
        if ( namedDigests.containsKey( name ) )
        {
            return namedDigests.get( name );
        }
        if ( name.equals( ROOT_FILE ) && !isIndex() )
        {
            return digests.isEmpty() ? null : digests.get( 0 );
        }
        for ( int number = 1; number <= digests.size(); number++ )
        {
            if ( name.equals( fileName( number ) ) )
            {
                return digests.get( number - 1 );
            }
        }
        return null;
    }

    /**
     * The JSON lines of the headless entries of a file, uncompressed.
     */
    ByteSource urlset( final int number )
    {
        return plain( urlsetFileName( number ) );
    }

    /**
     * The digest of the content of a file: its XML, or the JSON lines of a headless sitemap.
     */
    String fileDigest( final int number )
    {
        return digests.get( number - 1 );
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
        return files.gzipped( fileName );
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

    /**
     * Gives the gzip-compressed content of a sitemap file by name, without the {@code .gz} extension.
     */
    @FunctionalInterface
    interface FileSource
    {
        ByteSource gzipped( String name );

        static FileSource directory( final Path directory )
        {
            return name -> MoreFiles.asByteSource( directory.resolve( name + GZIP_EXTENSION ) );
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

        private final Long size;

        GunzippedSource( final ByteSource gzipped, final Long size )
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
     * Counts the UTF-8 bytes written, which is the size of the file when served plain, and digests them.
     */
    private static final class CountingWriter
        extends Writer
    {
        private final Writer delegate;

        private final MessageDigest digest = sha256();

        private long bytes;

        CountingWriter( final Writer delegate )
        {
            this.delegate = delegate;
        }

        @Override
        public void write( final String value )
            throws IOException
        {
            final byte[] utf8 = value.getBytes( StandardCharsets.UTF_8 );
            bytes += utf8.length;
            digest.update( utf8 );
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

        String digest()
        {
            return HexFormat.of().formatHex( digest.digest() );
        }

        private static MessageDigest sha256()
        {
            try
            {
                return MessageDigest.getInstance( "SHA-256" );
            }
            catch ( NoSuchAlgorithmException e )
            {
                throw new IllegalStateException( e );
            }
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

        private final List<Instant> lastModified = new ArrayList<>();

        private final List<String> digests = new ArrayList<>();

        private final Map<String, String> namedDigests = new HashMap<>();

        private final Map<String, Long> plainSizes = new HashMap<>();

        private CountingWriter file;

        private CountingWriter urlsetFile;

        private JsonGenerator urlset;

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
                // for the headless sitemap, the path the client puts after its base URL
                final String loc = xml() ? renderer.loc( entry ) : renderer.path( entry );
                if ( loc.length() > ( xml() ? MAX_LOC_LENGTH : MAX_LOC_LENGTH - MAX_BASE_URL_LENGTH ) )
                {
                    tooLong++;
                    return;
                }
                final String url = xml() ? renderer.url( entry, loc ) : null;
                final long urlBytes = xml() ? utf8Length( Objects.requireNonNull( url ) ) : utf8Length( loc ) + RESERVED_BYTES_PER_URL;
                if ( leftOut > 0 || !reserve( urlBytes ) )
                {
                    leftOut++;
                    return;
                }
                if ( xml() )
                {
                    Objects.requireNonNull( file ).write( Objects.requireNonNull( url ) );
                }
                else
                {
                    renderer.urlsetEntry( Objects.requireNonNull( urlset ), entry );
                }
                fileUrls++;
                size++;
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }
        }

        private boolean xml()
        {
            return params.format() == SitemapParams.Format.XML;
        }

        /**
         * Finishes the files. A file keeps the modification time of the same file of the previous sitemap when its content is the same,
         * and gets the given time otherwise: crawlers and headless clients fetch a file again when its modification time changes. The
         * XML files hold dates, the headless entries full modification times.
         *
         * @param previous the sitemap this one replaces, if any
         * @param now      the modification time of the files that changed
         */
        public Sitemap build( final Sitemap previous, final Instant now )
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

                final boolean empty = lastModified.isEmpty();
                if ( empty )
                {
                    writeEmpty();
                }
                for ( int i = 0; i < lastModified.size(); i++ )
                {
                    lastModified.set( i, unchanged( previous, i ) ? previous.lastModified.get( i ) : now );
                }
                if ( empty || !xml() )
                {
                    built = true;
                    return local().withModified( previous, now );
                }
                if ( lastModified.size() == 1 )
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
                return local().withModified( previous, now );
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }
        }

        private Sitemap local()
        {
            return new Sitemap( FileSource.directory( directory ), directory, size, plainSizes, lastModified, digests, namedDigests );
        }

        private boolean unchanged( final Sitemap previous, final int index )
        {
            return previous != null && index < previous.digests.size() && previous.digests.get( index ).equals( digests.get( index ) ) &&
                previous.lastModified.get( index ) != null;
        }

        private void writeEmpty()
            throws IOException
        {
            final String name = xml() ? ROOT_FILE : urlsetFileName( 1 );
            final CountingWriter root = open( name );
            if ( xml() )
            {
                root.write( URLSET_START + URLSET_END );
            }
            close( root, name );
            digests.add( root.digest() );
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
            namedDigests.put( name, index.digest() );
        }

        private boolean reserve( final long urlBytes )
            throws IOException
        {
            final long available = maxFileBytes - utf8Length( URLSET_START ) - utf8Length( URLSET_END );
            if ( !lastModified.isEmpty() && fileUrls < maxUrlsPerFile && fileBytes + urlBytes <= available )
            {
                fileBytes += urlBytes;
                return true;
            }

            final int number = lastModified.size() + 1;
            final long indexEntryBytes = xml()
                ? utf8Length( renderer.indexEntry( renderer.fileUrl( fileName( number ) + GZIP_EXTENSION ), null ) ) + INDEX_LASTMOD_BYTES
                : RESERVED_BYTES_PER_URL;
            if ( number > maxFiles || indexBytes + indexEntryBytes > MAX_FILE_BYTES )
            {
                return false;
            }
            closeFile();
            indexBytes += indexEntryBytes;
            if ( xml() )
            {
                file = open( fileName( number ) );
                file.write( URLSET_START );
            }
            else
            {
                // split where the XML files would be split, so that a client rendering one per file stays within the limits
                urlsetFile = open( urlsetFileName( number ) );
                urlset = JSON.createGenerator( urlsetFile );
                urlset.setRootValueSeparator( new SerializedString( "\n" ) );
            }
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
                digests.add( file.digest() );
                file = null;
            }
            if ( urlset != null )
            {
                urlset.close();
                urlset = null;
                plainSizes.put( urlsetFileName( lastModified.size() ), Objects.requireNonNull( urlsetFile ).bytes() );
                digests.add( urlsetFile.digest() );
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
    }
}

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.jspecify.annotations.Nullable;
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
     * The longest base URL a Guillotine client is assumed to put in front of the paths. Paths longer than the rest of
     * {@link #MAX_LOC_LENGTH} are left out of a Guillotine sitemap.
     */
    static final int MAX_BASE_URL_LENGTH = 360;

    /**
     * The bytes a Guillotine client is assumed to write for a URL besides its path, and for each entry of its sitemap index: a
     * Guillotine sitemap is split as if each URL took its percent-encoded path plus this many bytes. A round number: what Next.js
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

    private final @Nullable Path directory;

    private final int size;

    private final Map<String, Long> plainSizes;

    private final List<SitemapFile> fileList;

    private final Map<String, String> namedDigests;

    /**
     * The files written to the local directory: the others are kept from the sitemap this one replaces.
     */
    private final Set<String> local;

    private final @Nullable Instant cutoff;

    private Sitemap( final FileSource files, final @Nullable Path directory, final int size, final Map<String, Long> plainSizes,
                     final List<SitemapFile> fileList, final Map<String, String> namedDigests, final Set<String> local,
                     final @Nullable Instant cutoff )
    {
        this.fileList = List.copyOf( fileList );
        this.namedDigests = Map.copyOf( namedDigests );
        this.files = files;
        this.directory = directory;
        this.size = size;
        this.plainSizes = Map.copyOf( plainSizes );
        this.local = Set.copyOf( local );
        this.cutoff = cutoff;
    }

    /**
     * A file of the sitemap.
     *
     * @param number       its number, which stays the same while its range is not generated again; numbers of files that became empty
     *                     are left out
     * @param lastModified when its content last changed
     * @param digest       of its content: its XML, or the JSON lines of a Guillotine sitemap
     * @param range        when the creation time range of the contents of its group of files starts, null for the first group, or for
     *                     a sitemap read by path. A group holds the contents created from its start until the start of the next group,
     *                     in one or more files
     * @param count        the contents of its group that had not changed at the {@link #cutoff()}, or -1 when not counted
     * @param urls         the URLs in the file
     */
    public record SitemapFile(int number, @Nullable Instant lastModified, String digest, @Nullable Instant range, long count, int urls)
    {
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
        return fileList.size();
    }

    @Override
    public boolean isIndex()
    {
        return fileList.size() > 1;
    }

    List<SitemapFile> files()
    {
        return fileList;
    }

    /**
     * When the counts of the groups of files were taken: contents changed after it are not counted.
     */
    @Nullable Instant cutoff()
    {
        return cutoff;
    }

    /**
     * Whether the groups of files are counted, so that a generation can tell which groups changed: only those need to be generated again.
     */
    boolean hasRanges()
    {
        return cutoff != null && fileList.stream().allMatch( file -> file.count() >= 0 );
    }

    /**
     * The same sitemap with the counts of its groups of files, in order, taken at the given time.
     */
    Sitemap withCounts( final Instant cutoff, final List<Long> counts )
    {
        final List<SitemapFile> counted = new ArrayList<>();
        int group = -1;
        for ( int i = 0; i < fileList.size(); i++ )
        {
            final SitemapFile file = fileList.get( i );
            if ( i == 0 || !Objects.equals( file.range(), fileList.get( i - 1 ).range() ) )
            {
                group++;
            }
            counted.add(
                new SitemapFile( file.number(), file.lastModified(), file.digest(), file.range(), counts.get( group ), file.urls() ) );
        }
        return new Sitemap( files, directory, size, plainSizes, counted, namedDigests, local, cutoff );
    }

    /**
     * The counts of the groups of files, in order.
     */
    List<Long> groupCounts()
    {
        final List<Long> counts = new ArrayList<>();
        for ( int i = 0; i < fileList.size(); i++ )
        {
            if ( i == 0 || !Objects.equals( fileList.get( i ).range(), fileList.get( i - 1 ).range() ) )
            {
                counts.add( fileList.get( i ).count() );
            }
        }
        return counts;
    }

    /**
     * The files of each group, in order.
     */
    List<List<SitemapFile>> groups()
    {
        final List<List<SitemapFile>> groups = new ArrayList<>();
        for ( int i = 0; i < fileList.size(); i++ )
        {
            if ( i == 0 || !Objects.equals( fileList.get( i ).range(), fileList.get( i - 1 ).range() ) )
            {
                groups.add( new ArrayList<>() );
            }
            groups.get( groups.size() - 1 ).add( fileList.get( i ) );
        }
        return groups;
    }

    /**
     * The starts of the creation time ranges of the groups of files, in order: null for the first.
     */
    List<@Nullable Instant> groupStarts()
    {
        final List<@Nullable Instant> starts = new ArrayList<>();
        for ( int i = 0; i < fileList.size(); i++ )
        {
            if ( i == 0 || !Objects.equals( fileList.get( i ).range(), fileList.get( i - 1 ).range() ) )
            {
                starts.add( fileList.get( i ).range() );
            }
        }
        return starts;
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
    public @Nullable String rootETag( final boolean encoded )
    {
        return etag( plainRootFile(), encoded );
    }

    public @Nullable String rootGzipETag()
    {
        return etag( ROOT_FILE, true );
    }

    public Optional<String> fileETag( final int number, final boolean gzip )
    {
        return validFile( number ).map( name -> etag( name, gzip ) );
    }

    @Override
    public List<@Nullable Instant> lastModified()
    {
        return fileList.stream().map( SitemapFile::lastModified ).toList();
    }

    @Override
    public List<Integer> fileNumbers()
    {
        return fileList.stream().map( SitemapFile::number ).toList();
    }

    private boolean hasFile( final int number )
    {
        return fileList.stream().anyMatch( file -> file.number() == number );
    }

    /**
     * The first {@code count} URLs of the sitemap as a JSON array of path-based entries.
     */
    public String urlsetJson( final int count )
    {
        return jsonArray( json -> {
            int remaining = count;
            for ( int i = 0; i < fileList.size() && remaining > 0; i++ )
            {
                remaining -= copyUrlset( json, fileList.get( i ).number(), remaining );
            }
        } );
    }

    /**
     * The URLs of one sitemap file as a JSON array of path-based entries, or empty if there is no such file.
     */
    public Optional<String> fileUrlsetJson( final int number )
    {
        if ( !hasFile( number ) )
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
            for ( final SitemapFile file : fileList )
            {
                json.writeStartObject();
                json.writeNumberField( "number", file.number() );
                final Instant lastmod = file.lastModified();
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

    @Nullable Path directory()
    {
        return directory;
    }

    /**
     * The same sitemap, read from the given files.
     */
    Sitemap withFiles( final FileSource files )
    {
        return new Sitemap( files, null, size, plainSizes, fileList, namedDigests, Set.of(), cutoff );
    }

    /**
     * The gzip-compressed files of the sitemap written to its local directory, by name, without the {@code .gz} extension: the others are
     * those of the sitemap it replaces, already stored.
     */
    Map<String, ByteSource> storedFiles()
    {
        final Map<String, ByteSource> stored = new TreeMap<>();
        local.forEach( name -> stored.put( name, gzip( name ) ) );
        return stored;
    }

    /**
     * The names of all files of the sitemap, without the {@code .gz} extension.
     */
    Set<String> fileNames()
    {
        return new TreeSet<>( plainSizes.keySet() );
    }

    /**
     * Describes the sitemap, for {@link #restore} to read the sitemap back from its directory.
     */
    ObjectNode store()
    {
        final ObjectNode description = JsonNodeFactory.instance.objectNode();
        description.put( "size", size );
        final ArrayNode files = description.putArray( "files" );
        for ( final SitemapFile sitemapFile : fileList )
        {
            final ObjectNode file = files.addObject();
            file.put( "number", sitemapFile.number() );
            if ( sitemapFile.lastModified() != null )
            {
                file.put( "lastmod", sitemapFile.lastModified().toString() );
            }
            file.put( "digest", sitemapFile.digest() );
            if ( sitemapFile.range() != null )
            {
                file.put( "range", sitemapFile.range().toString() );
            }
            if ( sitemapFile.count() >= 0 )
            {
                file.put( "count", sitemapFile.count() );
            }
            file.put( "urls", sitemapFile.urls() );
        }
        if ( cutoff != null )
        {
            description.put( "cutoff", cutoff.toString() );
        }
        final ObjectNode bytes = description.putObject( "bytes" );
        new TreeMap<>( plainSizes ).forEach( bytes::put );
        final ObjectNode index = description.putObject( "namedDigests" );
        new TreeMap<>( namedDigests ).forEach( index::put );
        return description;
    }

    /**
     * Reads back a sitemap that {@link #store} described, with its files from the given source.
     *
     * @throws IllegalArgumentException when the description is incomplete
     */
    static Sitemap restore( final FileSource files, final JsonNode description )
    {
        final List<SitemapFile> fileList = new ArrayList<>();
        for ( final JsonNode file : description.required( "files" ) )
        {
            final JsonNode lastmod = file.get( "lastmod" );
            final JsonNode range = file.get( "range" );
            final JsonNode count = file.get( "count" );
            fileList.add( new SitemapFile( file.required( "number" ).asInt(), lastmod == null ? null : Instant.parse( lastmod.asText() ),
                                           file.required( "digest" ).asText(), range == null ? null : Instant.parse( range.asText() ),
                                           count == null ? -1 : count.asLong(), file.required( "urls" ).asInt() ) );
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
        final JsonNode cutoff = description.get( "cutoff" );
        return new Sitemap( files, null, description.required( "size" ).asInt(), plainSizes, fileList, namedDigests, Set.of(),
                            cutoff == null ? null : Instant.parse( cutoff.asText() ) );
    }

    static String fileName( final int number )
    {
        return "sitemap-" + number + ".xml";
    }

    static String urlsetFileName( final int number )
    {
        return "urlset-" + number + ".jsonl";
    }

    private @Nullable String etag( final String name, final boolean gzip )
    {
        final String digest = digest( name );
        if ( digest == null )
        {
            return null;
        }
        return "\"" + digest.substring( 0, ETAG_LENGTH ) + ( gzip ? "-gzip" : "" ) + "\"";
    }

    private @Nullable String digest( final String name )
    {
        if ( namedDigests.containsKey( name ) )
        {
            return namedDigests.get( name );
        }
        if ( name.equals( ROOT_FILE ) && !isIndex() )
        {
            return fileList.isEmpty() ? null : fileList.get( 0 ).digest();
        }
        for ( final SitemapFile file : fileList )
        {
            if ( name.equals( fileName( file.number() ) ) )
            {
                return file.digest();
            }
        }
        return null;
    }

    /**
     * The JSON lines of the Guillotine entries of a file, uncompressed.
     */
    ByteSource urlset( final int number )
    {
        return plain( urlsetFileName( number ) );
    }

    /**
     * The digest of the content of a file: its XML, or the JSON lines of a Guillotine sitemap.
     */
    String fileDigest( final int number )
    {
        return fileList.stream()
            .filter( file -> file.number() == number )
            .findFirst()
            .orElseThrow( () -> new IllegalArgumentException( "No sitemap file " + number ) )
            .digest();
    }

    long plainSize( final String name )
    {
        return Objects.requireNonNull( plainSizes.get( name ), name );
    }

    private String plainRootFile()
    {
        return isIndex() ? PLAIN_INDEX_FILE : ROOT_FILE;
    }

    private Optional<String> validFile( final int number )
    {
        if ( !isIndex() || !hasFile( number ) )
        {
            return Optional.empty();
        }
        return Optional.of( fileName( number ) );
    }

    private ByteSource gzip( final String fileName )
    {
        return files.gzipped( fileName );
    }

    /**
     * A file of the sitemap, gzip-compressed, by name without the {@code .gz} extension.
     */
    ByteSource gzipped( final String fileName )
    {
        return gzip( fileName );
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
     * <p>
     * Entries read in the order the contents were created are split in groups by creation time: a file holds the contents created from
     * the creation time of its first entry, unless that is the creation time of the last entry of the file before, which then belongs to
     * the same group. A sitemap generated again can keep the files of the groups whose contents did not change ({@link #reuse}) and write
     * the others again ({@link #beginGroup}), each under the number it had; files they need beyond those get new numbers.
     * <p>
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

        private final List<SitemapFile> files = new ArrayList<>();

        private final Map<String, String> namedDigests = new HashMap<>();

        private final Map<String, Long> plainSizes = new HashMap<>();

        private final Set<String> local = new HashSet<>();

        private @Nullable CountingWriter file;

        private @Nullable CountingWriter urlsetFile;

        private @Nullable JsonGenerator urlset;

        private int number;

        private @Nullable Instant range;

        private @Nullable Instant lastCreated;

        private int nextNumber = 1;

        private @Nullable Integer groupNumber;

        private @Nullable Instant groupRange;

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

        /**
         * Gives files beyond those of the groups written again numbers from the given one on.
         */
        public Builder numbersFrom( final int number )
        {
            this.nextNumber = number;
            return this;
        }

        /**
         * Keeps a file of the sitemap this one replaces as it is, with its group.
         */
        public void reuse( final SitemapFile previousFile, final Sitemap previous )
        {
            try
            {
                closeFile();
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }
            endGroup();
            final String name = xml() ? fileName( previousFile.number() ) : urlsetFileName( previousFile.number() );
            plainSizes.put( name, previous.plainSize( name ) );
            files.add( previousFile );
            size += previousFile.urls();
            indexBytes += indexEntryBytes( previousFile.number() );
        }

        /**
         * Starts writing a group of files again: its first file gets the given number and the given range, entries added next go in it.
         * When none are added, the group is gone.
         */
        public void beginGroup( final @Nullable Instant range, final int number )
        {
            try
            {
                closeFile();
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }
            endGroup();
            this.groupNumber = number;
            this.groupRange = range;
        }

        private void endGroup()
        {
            groupNumber = null;
            groupRange = null;
            lastCreated = null;
        }

        public void add( final SitemapEntry entry )
        {
            try
            {
                // for Guillotine, the path the client puts after its base URL
                final String loc = xml() ? renderer.loc( entry ) : renderer.path( entry );
                if ( loc.length() > ( xml() ? MAX_LOC_LENGTH : MAX_LOC_LENGTH - MAX_BASE_URL_LENGTH ) )
                {
                    tooLong++;
                    return;
                }
                final String url = xml() ? renderer.url( entry, loc ) : null;
                final long urlBytes = xml() ? utf8Length( Objects.requireNonNull( url ) ) : utf8Length( loc ) + RESERVED_BYTES_PER_URL;
                if ( leftOut > 0 || !reserve( urlBytes, entry.createdTime() ) )
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
                lastCreated = entry.createdTime();
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
         * and gets the given time otherwise: crawlers and Guillotine clients fetch a file again when its modification time changes. The
         * XML files hold dates, the Guillotine entries full modification times.
         *
         * @param previous the sitemap this one replaces, if any
         * @param now      the modification time of the files that changed
         */
        public Sitemap build( final @Nullable Sitemap previous, final Instant now )
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

                final boolean empty = files.isEmpty();
                if ( empty )
                {
                    writeEmpty();
                }
                for ( int i = 0; i < files.size(); i++ )
                {
                    final SitemapFile written = files.get( i );
                    if ( written.lastModified() == null )
                    {
                        files.set( i, new SitemapFile( written.number(), unchanged( previous, written ) ? previousLastModified( previous,
                                                                                                                             written ) : now,
                                                       written.digest(), written.range(), written.count(), written.urls() ) );
                    }
                }
                if ( !empty && xml() && files.size() == 1 )
                {
                    final String name = fileName( files.get( 0 ).number() );
                    Files.move( gzPath( name ), gzPath( ROOT_FILE ) );
                    plainSizes.put( ROOT_FILE, plainSizes.remove( name ) );
                    local.remove( name );
                    local.add( ROOT_FILE );
                }
                else if ( !empty && xml() )
                {
                    writeIndex( ROOT_FILE, ".xml" + GZIP_EXTENSION );
                    writeIndex( PLAIN_INDEX_FILE, ".xml" );
                }
                built = true;
                return new Sitemap( FileSource.directory( directory ), directory, size, plainSizes, files, namedDigests, local, null );
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }
        }

        private static boolean unchanged( final @Nullable Sitemap previous, final SitemapFile written )
        {
            return previousLastModified( previous, written ) != null;
        }

        private static @Nullable Instant previousLastModified( final @Nullable Sitemap previous, final SitemapFile written )
        {
            if ( previous == null )
            {
                return null;
            }
            return previous.files()
                .stream()
                .filter( file -> file.number() == written.number() && file.digest().equals( written.digest() ) )
                .map( SitemapFile::lastModified )
                .filter( Objects::nonNull )
                .findFirst()
                .orElse( null );
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
            files.add( new SitemapFile( 1, null, root.digest(), null, -1, 0 ) );
        }

        private void writeIndex( final String name, final String extension )
            throws IOException
        {
            final CountingWriter index = open( name );
            index.write( INDEX_START );
            for ( final SitemapFile sitemapFile : files )
            {
                index.write( renderer.indexEntry( renderer.fileUrl( "sitemap-" + sitemapFile.number() + extension ),
                                                  sitemapFile.lastModified() ) );
            }
            index.write( INDEX_END );
            close( index, name );
            namedDigests.put( name, index.digest() );
        }

        private long indexEntryBytes( final int number )
        {
            return xml()
                ? utf8Length( renderer.indexEntry( renderer.fileUrl( fileName( number ) + GZIP_EXTENSION ), null ) ) + INDEX_LASTMOD_BYTES
                : RESERVED_BYTES_PER_URL;
        }

        private boolean reserve( final long urlBytes, final @Nullable Instant created )
            throws IOException
        {
            final long available = maxFileBytes - utf8Length( URLSET_START ) - utf8Length( URLSET_END );
            if ( ( file != null || urlset != null ) && fileUrls < maxUrlsPerFile && fileBytes + urlBytes <= available )
            {
                fileBytes += urlBytes;
                return true;
            }

            final int newNumber = groupNumber != null ? groupNumber : nextNumber;
            final long entryBytes = indexEntryBytes( newNumber );
            final boolean open = file != null || urlset != null;
            if ( files.size() + ( open ? 1 : 0 ) + 1 > maxFiles || indexBytes + entryBytes > MAX_FILE_BYTES )
            {
                return false;
            }
            final @Nullable Instant previousRange = range;
            closeFile();
            if ( groupNumber != null )
            {
                range = groupRange;
                groupNumber = null;
                groupRange = null;
            }
            else
            {
                nextNumber++;
                // a new group starts with this file, unless its first content was created in the same millisecond as the last one before
                range = !open && files.isEmpty() || created == null ? null
                    : created.equals( lastCreated ) ? previousRange : created;
            }
            number = newNumber;
            indexBytes += entryBytes;
            if ( xml() )
            {
                file = open( fileName( number ) );
                file.write( URLSET_START );
            }
            else
            {
                urlsetFile = open( urlsetFileName( number ) );
                urlset = JSON.createGenerator( urlsetFile );
                urlset.setRootValueSeparator( new SerializedString( "\n" ) );
            }
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
                close( file, fileName( number ) );
                files.add( new SitemapFile( number, null, file.digest(), range, -1, fileUrls ) );
                file = null;
            }
            if ( urlset != null )
            {
                urlset.close();
                urlset = null;
                final String name = urlsetFileName( number );
                plainSizes.put( name, Objects.requireNonNull( urlsetFile ).bytes() );
                local.add( name );
                files.add( new SitemapFile( number, null, urlsetFile.digest(), range, -1, fileUrls ) );
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
            local.add( name );
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

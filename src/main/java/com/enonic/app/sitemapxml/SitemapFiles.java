package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.common.io.ByteSource;

/**
 * The files of {@code sitemap.xml}: the root ({@code sitemap.xml}, a urlset or a sitemap index), the numbered files of an index, and
 * their entity tags.
 */
public interface SitemapFiles
{
    ByteSource root();

    /**
     * The root as {@link #root()} returns it, but gzip-compressed, to be sent with {@code Content-Encoding: gzip}.
     */
    ByteSource rootEncoded();

    /**
     * {@code sitemap.xml.gz}: a sitemap index in it lists the {@code .xml.gz} files.
     */
    ByteSource rootGzip();

    Optional<ByteSource> file( int number );

    Optional<ByteSource> fileGzip( int number );

    String rootETag( boolean encoded );

    String rootGzipETag();

    Optional<String> fileETag( int number, boolean gzip );

    boolean isIndex();

    /**
     * The time each file last changed, in order.
     */
    List<Instant> lastModified();

    /**
     * When the root last changed. By default that of its only file, or the latest of the files a sitemap index lists, which goes back
     * when the latest file leaves the index: a sitemap that keeps the time of its root overrides it.
     */
    default Instant rootLastModified()
    {
        return lastModified().stream().filter( Objects::nonNull ).max( Comparator.naturalOrder() ).orElse( null );
    }

    default Optional<Instant> fileLastModified( final int number )
    {
        return isIndex() && number >= 1 && number <= lastModified().size()
            ? Optional.ofNullable( lastModified().get( number - 1 ) )
            : Optional.empty();
    }
}

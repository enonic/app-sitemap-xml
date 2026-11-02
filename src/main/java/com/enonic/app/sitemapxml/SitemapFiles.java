package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

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

    @Nullable String rootETag( boolean encoded );

    @Nullable String rootGzipETag();

    Optional<String> fileETag( int number, boolean gzip );

    boolean isIndex();

    /**
     * The time each file last changed, in order.
     */
    List<@Nullable Instant> lastModified();

    /**
     * When the root last changed: that of its only file, or the latest of the files a sitemap index lists.
     */
    default @Nullable Instant rootLastModified()
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

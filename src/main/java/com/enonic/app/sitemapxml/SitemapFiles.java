package com.enonic.app.sitemapxml;

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
}

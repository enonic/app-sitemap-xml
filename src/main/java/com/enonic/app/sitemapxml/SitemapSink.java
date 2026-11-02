package com.enonic.app.sitemapxml;

import java.util.function.Consumer;

/**
 * Receives the entries of a sitemap being generated, and how far the generation is.
 */
@FunctionalInterface
public interface SitemapSink
    extends Consumer<SitemapEntry>
{
    /**
     * @param read  contents read so far
     * @param total contents to read
     */
    default void progress( final long read, final long total )
    {
    }
}

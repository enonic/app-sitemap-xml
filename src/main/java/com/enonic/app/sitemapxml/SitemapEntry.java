package com.enonic.app.sitemapxml;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A URL of the sitemap.
 *
 * @param createdTime when the content was created, for a sitemap read in the order contents were created: files are grouped by it
 */
public record SitemapEntry(String path, String contentType, @Nullable Instant lastModified, @Nullable Instant createdTime)
{
    public SitemapEntry( final String path, final String contentType, final @Nullable Instant lastModified )
    {
        this( path, contentType, lastModified, null );
    }
}

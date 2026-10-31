package com.enonic.app.sitemapxml;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

public record SitemapEntry(String path, String contentType, @Nullable Instant lastModified)
{
}

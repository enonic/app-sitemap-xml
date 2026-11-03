package com.enonic.app.sitemapxml;

import java.time.Instant;

public record SitemapEntry(String path, String contentType, Instant lastModified)
{
}

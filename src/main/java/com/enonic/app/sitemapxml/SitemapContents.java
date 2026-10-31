package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

public record SitemapContents(List<SitemapEntry> entries, @Nullable Instant validUntil)
{
    public SitemapContents
    {
        entries = List.copyOf( entries );
    }
}

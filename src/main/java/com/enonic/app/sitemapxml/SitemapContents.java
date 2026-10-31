package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.List;

public final class SitemapContents
{
    private final List<SitemapEntry> entries;

    private final Instant validUntil;

    public SitemapContents( final List<SitemapEntry> entries, final Instant validUntil )
    {
        this.entries = List.copyOf( entries );
        this.validUntil = validUntil;
    }

    public List<SitemapEntry> entries()
    {
        return entries;
    }

    public Instant validUntil()
    {
        return validUntil;
    }
}

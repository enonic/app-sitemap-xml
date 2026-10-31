package com.enonic.app.sitemapxml;

public final class SitemapBusyException
    extends RuntimeException
{
    public SitemapBusyException()
    {
        super( "Too many sitemap requests in progress" );
    }
}

package com.enonic.app.sitemapxml;

public final class SitemapBusyException
    extends RuntimeException
{
    public SitemapBusyException()
    {
        super( "Too many requests are waiting for sitemaps to be generated" );
    }
}

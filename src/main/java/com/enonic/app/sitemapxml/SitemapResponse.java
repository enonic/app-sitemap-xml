package com.enonic.app.sitemapxml;

import com.google.common.io.ByteSource;

public final class SitemapResponse
{
    static final int RETRY_AFTER_SECONDS = 10;

    private final int status;

    private final ByteSource body;

    private final String etag;

    private SitemapResponse( final int status, final ByteSource body, final String etag )
    {
        this.status = status;
        this.body = body;
        this.etag = etag;
    }

    static SitemapResponse ok( final ByteSource body, final String etag )
    {
        return new SitemapResponse( 200, body, etag );
    }

    static SitemapResponse notModified( final String etag )
    {
        return new SitemapResponse( 304, null, etag );
    }

    static SitemapResponse notFound()
    {
        return new SitemapResponse( 404, null, null );
    }

    static SitemapResponse badRequest()
    {
        return new SitemapResponse( 400, null, null );
    }

    static SitemapResponse tooManyRequests()
    {
        return new SitemapResponse( 429, null, null );
    }

    public int getStatus()
    {
        return status;
    }

    public ByteSource getBody()
    {
        return body;
    }

    public String getETag()
    {
        return etag;
    }

    public int getRetryAfter()
    {
        return status == 429 ? RETRY_AFTER_SECONDS : 0;
    }
}

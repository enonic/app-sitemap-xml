package com.enonic.app.sitemapxml;


public final class SitemapResponse
{
    static final int RETRY_AFTER_SECONDS = 10;

    private final int status;

    private final String body;

    private SitemapResponse( final int status, final String body )
    {
        this.status = status;
        this.body = body;
    }

    static SitemapResponse ok( final String body )
    {
        return new SitemapResponse( 200, body );
    }

    static SitemapResponse notFound()
    {
        return new SitemapResponse( 404, null );
    }

    static SitemapResponse busy()
    {
        return new SitemapResponse( 503, null );
    }

    public int getStatus()
    {
        return status;
    }

    public String getBody()
    {
        return body;
    }

    public int getRetryAfter()
    {
        return status == 503 ? RETRY_AFTER_SECONDS : 0;
    }
}

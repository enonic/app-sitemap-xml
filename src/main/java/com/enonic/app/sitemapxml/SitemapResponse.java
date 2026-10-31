package com.enonic.app.sitemapxml;

import org.jspecify.annotations.Nullable;

import com.google.common.io.ByteSource;

public final class SitemapResponse
{
    static final int RETRY_AFTER_SECONDS = 10;

    private final int status;

    private final @Nullable ByteSource body;

    private SitemapResponse( final int status, final @Nullable ByteSource body )
    {
        this.status = status;
        this.body = body;
    }

    static SitemapResponse ok( final ByteSource body )
    {
        return new SitemapResponse( 200, body );
    }

    static SitemapResponse notFound()
    {
        return new SitemapResponse( 404, null );
    }

    static SitemapResponse tooManyRequests()
    {
        return new SitemapResponse( 429, null );
    }

    public int getStatus()
    {
        return status;
    }

    public @Nullable ByteSource getBody()
    {
        return body;
    }

    public int getRetryAfter()
    {
        return status == 429 ? RETRY_AFTER_SECONDS : 0;
    }
}

package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.jspecify.annotations.Nullable;

import com.google.common.io.ByteSource;

public final class SitemapResponse
{
    static final int RETRY_AFTER_SECONDS = 10;

    private final int status;

    private final @Nullable ByteSource body;

    private final @Nullable String etag;

    private final @Nullable Instant lastModified;

    private SitemapResponse( final int status, final @Nullable ByteSource body, final @Nullable String etag,
                             final @Nullable Instant lastModified )
    {
        this.status = status;
        this.body = body;
        this.etag = etag;
        this.lastModified = lastModified;
    }

    static SitemapResponse ok( final ByteSource body, final @Nullable String etag, final @Nullable Instant lastModified )
    {
        return new SitemapResponse( 200, body, etag, lastModified );
    }

    static SitemapResponse notModified( final @Nullable String etag, final @Nullable Instant lastModified )
    {
        return new SitemapResponse( 304, null, etag, lastModified );
    }

    static SitemapResponse notFound()
    {
        return new SitemapResponse( 404, null, null, null );
    }

    static SitemapResponse badRequest()
    {
        return new SitemapResponse( 400, null, null, null );
    }

    static SitemapResponse tooManyRequests()
    {
        return new SitemapResponse( 429, null, null, null );
    }

    public int getStatus()
    {
        return status;
    }

    public @Nullable ByteSource getBody()
    {
        return body;
    }

    public @Nullable String getETag()
    {
        return etag;
    }

    /**
     * The {@code Last-Modified} header value: when the file last changed, as an HTTP date.
     */
    public @Nullable String getLastModified()
    {
        return lastModified == null ? null : DateTimeFormatter.RFC_1123_DATE_TIME.format( lastModified.atOffset( ZoneOffset.UTC ) );
    }

    public int getRetryAfter()
    {
        return status == 429 ? RETRY_AFTER_SECONDS : 0;
    }
}

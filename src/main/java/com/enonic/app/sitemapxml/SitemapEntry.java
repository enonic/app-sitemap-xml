package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.Objects;

public final class SitemapEntry
{
    private final String path;

    private final String contentType;

    private final Instant lastModified;

    public SitemapEntry( final String path, final String contentType, final Instant lastModified )
    {
        this.path = Objects.requireNonNull( path );
        this.contentType = Objects.requireNonNull( contentType );
        this.lastModified = lastModified;
    }

    public String path()
    {
        return path;
    }

    public String contentType()
    {
        return contentType;
    }

    public Instant lastModified()
    {
        return lastModified;
    }

    @Override
    public boolean equals( final Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( !( o instanceof SitemapEntry ) )
        {
            return false;
        }
        final SitemapEntry that = (SitemapEntry) o;
        return path.equals( that.path ) && contentType.equals( that.contentType ) && Objects.equals( lastModified, that.lastModified );
    }

    @Override
    public int hashCode()
    {
        return Objects.hash( path, contentType, lastModified );
    }

    @Override
    public String toString()
    {
        return "SitemapEntry[" + path + ", " + contentType + ", " + lastModified + "]";
    }
}

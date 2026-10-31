package com.enonic.app.sitemapxml;

import java.util.Objects;

public final class ContentTypeSetting
{
    private final String contentType;

    private final String changefreq;

    private final String priority;

    public ContentTypeSetting( final String contentType, final String changefreq, final String priority )
    {
        this.contentType = Objects.requireNonNull( contentType );
        this.changefreq = Objects.requireNonNull( changefreq );
        this.priority = Objects.requireNonNull( priority );
    }

    public String contentType()
    {
        return contentType;
    }

    public String changefreq()
    {
        return changefreq;
    }

    public String priority()
    {
        return priority;
    }

    @Override
    public boolean equals( final Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( !( o instanceof ContentTypeSetting ) )
        {
            return false;
        }
        final ContentTypeSetting that = (ContentTypeSetting) o;
        return contentType.equals( that.contentType ) && changefreq.equals( that.changefreq ) && priority.equals( that.priority );
    }

    @Override
    public int hashCode()
    {
        return Objects.hash( contentType, changefreq, priority );
    }

    @Override
    public String toString()
    {
        return "ContentTypeSetting[" + contentType + ", " + changefreq + ", " + priority + "]";
    }
}

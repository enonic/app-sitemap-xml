package com.enonic.app.sitemapxml;

/**
 * A site URL taken from the request that a sitemap cannot have: its scheme is not {@code http} or {@code https}, or it has user info.
 */
public final class InvalidSiteUrlException
    extends RuntimeException
{
    InvalidSiteUrlException( final String siteUrl )
    {
        super( "Invalid site URL: " + siteUrl );
    }
}

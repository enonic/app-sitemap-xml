package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.google.common.io.ByteSource;

import com.enonic.xp.content.ContentService;
import com.enonic.xp.portal.url.PortalUrlService;
import com.enonic.xp.project.ProjectService;
import com.enonic.xp.script.ScriptValue;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.script.bean.ScriptBean;

public final class SitemapBean
    implements ScriptBean
{
    private @Nullable SitemapService service;

    @Override
    public void initialize( final BeanContext context )
    {
        this.service = new SitemapService( context.getService( ContentService.class ).get(),
                                           context.getService( ProjectService.class ).get(),
                                           context.getService( PortalUrlService.class ).get(),
                                           context.getService( SitemapCache.class ).get(), Clock.systemUTC() );
    }

    public SitemapResponse root( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.root() ), sitemap -> Optional.ofNullable( sitemap.rootETag( false ) ) );
    }

    public SitemapResponse file( final ScriptValue params, final int number )
    {
        return respond( params, sitemap -> sitemap.file( number ), sitemap -> sitemap.fileETag( number, false ) );
    }

    public SitemapResponse rootGzip( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.rootGzip() ), sitemap -> Optional.ofNullable( sitemap.rootGzipETag() ) );
    }

    public SitemapResponse rootEncoded( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.rootEncoded() ),
                        sitemap -> Optional.ofNullable( sitemap.rootETag( true ) ) );
    }

    public SitemapResponse fileGzip( final ScriptValue params, final int number )
    {
        return respond( params, sitemap -> sitemap.fileGzip( number ), sitemap -> sitemap.fileETag( number, true ) );
    }

    public String guillotineFiles( final ScriptValue params )
    {
        return guillotineSitemap( params ).filesJson();
    }

    public String guillotineUrlset( final ScriptValue params, final int count )
    {
        return guillotineSitemap( params ).urlsetJson( count );
    }

    public @Nullable String guillotineFile( final ScriptValue params, final int number )
    {
        return guillotineSitemap( params ).fileUrlsetJson( number ).orElse( null );
    }

    public @Nullable String portalBaseUrl( final String sitePath )
    {
        return Objects.requireNonNull( service, "Bean not initialized" ).portalBaseUrl( sitePath ).orElse( null );
    }

    private Sitemap guillotineSitemap( final ScriptValue params )
    {
        return Objects.requireNonNull( service, "Bean not initialized" ).sitemap( toParams( params.getMap() ), false );
    }

    private SitemapResponse respond( final ScriptValue params, final Function<Sitemap, Optional<ByteSource>> body,
                                     final Function<Sitemap, Optional<String>> etag )
    {
        final Map<String, Object> map = params.getMap();
        try
        {
            final Sitemap sitemap = Objects.requireNonNull( service, "Bean not initialized" )
                .sitemap( toParams( map ), Boolean.TRUE.equals( map.get( "siteUrlFromRequest" ) ) );
            final Optional<ByteSource> content = body.apply( sitemap );
            if ( content.isEmpty() )
            {
                return SitemapResponse.notFound();
            }
            final String tag = etag.apply( sitemap ).orElse( null );
            if ( tag != null && matches( map.get( "ifNoneMatch" ), tag ) )
            {
                return SitemapResponse.notModified( tag );
            }
            return SitemapResponse.ok( content.get(), tag );
        }
        catch ( SitemapBusyException e )
        {
            return SitemapResponse.tooManyRequests();
        }
        catch ( InvalidSiteUrlException e )
        {
            return SitemapResponse.badRequest();
        }
    }

    /**
     * Whether an {@code If-None-Match} header matches the entity tag, by weak comparison.
     */
    static boolean matches( final @Nullable Object ifNoneMatch, final String etag )
    {
        if ( !( ifNoneMatch instanceof String header ) )
        {
            return false;
        }
        final String opaque = opaqueTag( etag );
        for ( final String candidate : header.split( "," ) )
        {
            final String trimmed = candidate.trim();
            if ( trimmed.equals( "*" ) || opaqueTag( trimmed ).equals( opaque ) )
            {
                return true;
            }
        }
        return false;
    }

    private static String opaqueTag( final String etag )
    {
        return etag.startsWith( "W/" ) ? etag.substring( 2 ) : etag;
    }

    static SitemapParams toParams( final Map<String, Object> map )
    {
        final List<ContentTypeSetting> contentTypes = list( map.get( "contentTypes" ) ).stream()
            .filter( Map.class::isInstance )
            .map( Map.class::cast )
            .map( setting -> new ContentTypeSetting( string( setting.get( "contentType" ) ), string( setting.get( "changefreq" ) ),
                                                     string( setting.get( "priority" ) ) ) )
            .toList();
        final List<String> ignorePatterns = list( map.get( "ignorePatterns" ) ).stream().map( SitemapBean::string ).toList();

        return new SitemapParams( string( map.get( "sitePath" ) ), string( map.get( "siteUrl" ) ), contentTypes, ignorePatterns );
    }

    private static List<?> list( final @Nullable Object value )
    {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string( final @Nullable Object value )
    {
        return value == null ? "" : value.toString();
    }
}

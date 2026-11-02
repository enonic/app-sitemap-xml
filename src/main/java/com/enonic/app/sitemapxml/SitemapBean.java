package com.enonic.app.sitemapxml;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
import com.enonic.xp.web.vhost.VirtualHostService;

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
                                           context.getService( SitemapCache.class ).get(), Clock.systemUTC(),
                                           context.getService( VirtualHostService.class ).get() );
    }

    public SitemapResponse root( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.root() ), sitemap -> Optional.ofNullable( sitemap.rootETag( false ) ),
                        sitemap -> Optional.ofNullable( sitemap.rootLastModified() ) );
    }

    public SitemapResponse file( final ScriptValue params, final int number )
    {
        return respond( params, sitemap -> sitemap.file( number ), sitemap -> sitemap.fileETag( number, false ),
                        sitemap -> sitemap.fileLastModified( number ) );
    }

    public SitemapResponse rootGzip( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.rootGzip() ), sitemap -> Optional.ofNullable( sitemap.rootGzipETag() ),
                        sitemap -> Optional.ofNullable( sitemap.rootLastModified() ) );
    }

    public SitemapResponse rootEncoded( final ScriptValue params )
    {
        return respond( params, sitemap -> Optional.of( sitemap.rootEncoded() ),
                        sitemap -> Optional.ofNullable( sitemap.rootETag( true ) ),
                        sitemap -> Optional.ofNullable( sitemap.rootLastModified() ) );
    }

    public SitemapResponse fileGzip( final ScriptValue params, final int number )
    {
        return respond( params, sitemap -> sitemap.fileGzip( number ), sitemap -> sitemap.fileETag( number, true ),
                        sitemap -> sitemap.fileLastModified( number ) );
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

    private Sitemap guillotineSitemap( final ScriptValue params )
    {
        return Objects.requireNonNull( service, "Bean not initialized" )
            .sitemap( toParams( params.getMap() ).withSiteUrl( "" ).withFormat( SitemapParams.Format.GUILLOTINE ), false );
    }

    /**
     * Answers with the body, its entity tag and when it last changed, or {@code 304 Not Modified}: when {@code If-None-Match} is the
     * tag, or without {@code If-None-Match}, when {@code If-Modified-Since} is not before the last change.
     */
    private SitemapResponse respond( final ScriptValue params, final Function<SitemapFiles, Optional<ByteSource>> body,
                                     final Function<SitemapFiles, Optional<String>> etag,
                                     final Function<SitemapFiles, Optional<Instant>> lastModified )
    {
        final Map<String, Object> map = params.getMap();
        try
        {
            final SitemapFiles sitemap = Objects.requireNonNull( service, "Bean not initialized" )
                .files( toParams( map ), Boolean.TRUE.equals( map.get( "siteUrlFromRequest" ) ) );
            final Optional<ByteSource> content = body.apply( sitemap );
            if ( content.isEmpty() )
            {
                return SitemapResponse.notFound();
            }
            final String tag = etag.apply( sitemap ).orElse( null );
            final Instant modified = lastModified.apply( sitemap ).orElse( null );
            final Object ifNoneMatch = map.get( "ifNoneMatch" );
            if ( ifNoneMatch != null
                ? tag != null && tag.equals( ifNoneMatch )
                : modified != null && notModifiedSince( modified, map.get( "ifModifiedSince" ) ) )
            {
                return SitemapResponse.notModified( tag, modified );
            }
            return SitemapResponse.ok( content.get(), tag, modified );
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
     * Whether the file did not change after the {@code If-Modified-Since} date, which has whole seconds.
     */
    private static boolean notModifiedSince( final Instant modified, final @Nullable Object ifModifiedSince )
    {
        if ( !( ifModifiedSince instanceof String since ) || since.isBlank() )
        {
            return false;
        }
        try
        {
            final Instant date = ZonedDateTime.parse( since.trim(), DateTimeFormatter.RFC_1123_DATE_TIME ).toInstant();
            return !modified.truncatedTo( ChronoUnit.SECONDS ).isAfter( date );
        }
        catch ( DateTimeParseException e )
        {
            return false;
        }
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

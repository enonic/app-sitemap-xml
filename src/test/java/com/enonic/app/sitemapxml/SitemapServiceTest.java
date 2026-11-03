package com.enonic.app.sitemapxml;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.enonic.xp.app.ApplicationKey;
import com.enonic.xp.branch.Branch;
import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.portal.PortalRequest;
import com.enonic.xp.portal.PortalRequestAccessor;
import com.enonic.xp.portal.url.PortalScope;
import com.enonic.xp.portal.url.PortalScopeParams;
import com.enonic.xp.portal.url.PortalUrlService;
import com.enonic.xp.project.ProjectName;
import com.enonic.xp.project.ProjectService;
import com.enonic.xp.script.ScriptValue;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.security.IdProviderKey;
import com.enonic.xp.security.PrincipalKey;
import com.enonic.xp.security.RoleKeys;
import com.enonic.xp.security.User;
import com.enonic.xp.security.acl.AccessControlEntry;
import com.enonic.xp.security.acl.AccessControlList;
import com.enonic.xp.security.acl.Permission;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.site.SiteConfig;
import com.enonic.xp.site.SiteConfigs;
import com.enonic.xp.web.vhost.VirtualHost;
import com.enonic.xp.web.vhost.VirtualHostService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SitemapServiceTest
{
    private static final SitemapParams PARAMS =
        new SitemapParams( "/mysite", "https://request.example.com/mysite", List.of(), List.of() );

    @TempDir
    private Path temp;

    @Mock
    private ContentService contentService;

    @Mock
    private ProjectService projectService;

    @Mock
    private PortalUrlService portalUrlService;

    @Mock
    private SitemapCache cache;

    @Mock
    private VirtualHostService virtualHostService;

    private final List<VirtualHost> configured = new ArrayList<>();

    private @Nullable String served;

    @Test
    void usesBaseUrlOfSite()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( "https://www.example.com/" ) );

        final SitemapCache.Key key = files( true, false );

        assertThat( key.params().siteUrl() ).isEqualTo( "https://www.example.com" );
        assertThat( key.repositoryId() ).isEqualTo( "com.enonic.cms.project" );
        assertThat( key.branch() ).isEqualTo( "master" );
        final ArgumentCaptor<PortalScopeParams> params = ArgumentCaptor.forClass( PortalScopeParams.class );
        verify( portalUrlService ).portalScope( params.capture() );
        assertThat( params.getValue().getContentPath() ).isEqualTo( ContentPath.from( "/mysite" ) );
        assertThat( params.getValue().getProjectName() ).isEqualTo( ProjectName.from( "project" ) );
        assertThat( params.getValue().getBranch() ).isEqualTo( Branch.from( "master" ) );
    }

    @Test
    void givesPortalBaseUrlWithoutTrailingSlashForGuillotine()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( "https://www.example.com/" ) );

        assertThat( inContext( () -> new SitemapService( contentService, projectService, portalUrlService, cache,
                                                         Clock.systemUTC(), virtualHostService ).portalBaseUrl( "/mysite" ) ) ).contains(
            "https://www.example.com" );
    }

    @Test
    void draftGetsOnlyTheSiteWithoutCaching()
    {
        ContextBuilder.create().repositoryId( "com.enonic.cms.project" ).branch( "draft" ).build().callWith( () -> new SitemapService( contentService, projectService, portalUrlService, cache, Clock.systemUTC(), virtualHostService ).sitemap( PARAMS, false ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        final ArgumentCaptor<SitemapCache.Source> source = ArgumentCaptor.forClass( SitemapCache.Source.class );
        verify( cache ).uncached( key.capture(), any( AuthenticationInfo.class ), source.capture() );
        verify( cache, never() ).get( any() );
        assertThat( key.getValue().branch() ).isEqualTo( "master" );
        final List<SitemapEntry> entries = new ArrayList<>();
        assertThat( source.getValue().write( entries::add, () -> false ) ).isNull();
        assertThat( entries ).containsExactly( new SitemapEntry( "", SitemapParams.SITE_CONTENT_TYPE, null ) );
    }

    @Test
    void cachesPerUser()
    {
        final Content site = site( AccessControlList.empty() );
        when( contentService.getByPath( ContentPath.from( "/mysite" ) ) ).thenReturn( site );
        when( cache.get( any() ) ).thenReturn( write( PARAMS ) );

        asUser( () -> new SitemapService( contentService, projectService, portalUrlService, cache, Clock.systemUTC(), virtualHostService ).sitemap( PARAMS, false ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture() );
        assertThat( key.getValue().principals() ).contains( "user:system:editor" );
        assertThat( key.getValue().isPublic() ).isFalse();
        verify( cache, never() ).uncached( any(), any(), any() );
    }

    @Test
    void loggedInUserSharesPublicSitemapOfSiteReadableByEveryone()
    {
        final Content site =
            site( AccessControlList.of( AccessControlEntry.create().principal( RoleKeys.EVERYONE ).allow( Permission.READ ).build() ) );
        when( contentService.getByPath( ContentPath.from( "/mysite" ) ) ).thenReturn( site );
        when( cache.get( any() ) ).thenReturn( write( PARAMS ) );

        asUser( () -> new SitemapService( contentService, projectService, portalUrlService, cache, Clock.systemUTC(), virtualHostService ).sitemap( PARAMS, false ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture() );
        assertThat( key.getValue().principals() ).containsExactlyInAnyOrder( PrincipalKey.ofAnonymous().toString(),
                                                                             RoleKeys.EVERYONE.toString() );
        assertThat( key.getValue().isPublic() ).isTrue();
    }

    @Test
    void loggedInUserSharesPublicSitemapOfProjectReadableByEveryone()
    {
        final SitemapParams project = new SitemapParams( "", "", List.of(), List.of() );
        when( projectService.getPublicRead( ProjectName.from( "project" ) ) ).thenReturn( true );
        when( cache.get( any() ) ).thenReturn( write( project ) );

        asUser( () -> new SitemapService( contentService, projectService, portalUrlService, cache, Clock.systemUTC(), virtualHostService ).sitemap( project,
                                                                                                                                false ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture() );
        assertThat( key.getValue().principals() ).containsExactlyInAnyOrder( PrincipalKey.ofAnonymous().toString(),
                                                                             RoleKeys.EVERYONE.toString() );
        verify( contentService, never() ).getByPath( any() );
    }

    @Test
    void draftOfProjectListsNothing()
    {
        final SitemapParams project = new SitemapParams( "", "", List.of(), List.of() );
        ContextBuilder.create()
            .repositoryId( "com.enonic.cms.project" )
            .branch( "draft" )
            .build()
            .callWith( () -> new SitemapService( contentService, projectService, portalUrlService, cache, Clock.systemUTC(), virtualHostService ).sitemap( project,
                                                                                                                                        false ) );

        final ArgumentCaptor<SitemapCache.Source> source = ArgumentCaptor.forClass( SitemapCache.Source.class );
        verify( cache ).uncached( any(), any( AuthenticationInfo.class ), source.capture() );
        final List<SitemapEntry> entries = new ArrayList<>();
        assertThat( source.getValue().write( entries::add, () -> false ) ).isNull();
        assertThat( entries ).isEmpty();
    }

    @Test
    void beanAnswers400ForInvalidRequestUrl()
    {
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn( Map.of( "sitePath", "/mysite", "siteUrl", "ftp://example.com", "siteUrlFromRequest", true ) );

        final SitemapResponse response = inContext( () -> bean().root( value ) );

        assertThat( response.getStatus() ).isEqualTo( 400 );
        verify( cache, never() ).get( any() );
    }

    @Test
    void requestUrlOfVirtualHostMatchingItsHostExactlyWithoutPortHasItsOwnSitemap()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );
        virtualHosts( "site", "www.example.com Request.Example.com" );
        served = "site";

        final SitemapCache.Key key = files( true, false );
        assertThat( key.params().siteUrl() ).isEqualTo( PARAMS.siteUrl() );
        assertThat( key.isPublic() ).isTrue();
    }

    @Test
    void requestUrlOfVirtualHostMatchingItsHostByPatternIsConvertedFromGuillotine()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );
        virtualHosts( "site", "~.*\\.example\\.com" );
        served = "site";

        files( true, true );
    }

    @Test
    void requestUrlServedByPatternIsConvertedAlsoWhenAnotherVirtualHostHasTheHostExactly()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );
        virtualHosts( "exact", "request.example.com" );
        virtualHosts( "pattern", "~.*\\.example\\.com" );
        served = "pattern";

        files( true, true );
    }

    @Test
    void requestUrlWithoutVirtualHostOnTheRequestIsConverted()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );
        virtualHosts( "site", "request.example.com" );

        files( true, true );
    }

    @Test
    void requestUrlWithPortIsConverted()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );
        virtualHosts( "site", "request.example.com" );
        served = "site";
        when( cache.get( any() ) ).thenReturn( guillotine( new SitemapEntry( "/a", "portal:site", null ) ) );

        final SitemapFiles files = inServedRequest( () -> inContext(
            () -> service().files( PARAMS.withSiteUrl( "https://request.example.com:8443/mysite" ), true ) ) );

        assertThat( SitemapTest.text( files.root() ) ).contains( "<loc>https://request.example.com:8443/mysite/a</loc>" );
        verify( virtualHostService, never() ).getVirtualHosts();
    }

    @Test
    void requestUrlIsConvertedWhenVirtualHostsAreDisabled()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );
        served = "site";

        files( true, true );
    }

    @Test
    void requestUrlOfLoggedInUserOnSiteNotReadableByEveryoneHasItsOwnSitemap()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );
        final Content site = site( AccessControlList.empty() );
        when( contentService.getByPath( ContentPath.from( "/mysite" ) ) ).thenReturn( site );
        when( cache.get( any() ) ).thenReturn( write( PARAMS ) );

        assertThat( asUser( () -> service().files( PARAMS, true ) ) ).isInstanceOf( Sitemap.class );
        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture() );
        assertThat( key.getValue().isPublic() ).isFalse();
        assertThat( key.getValue().params().siteUrl() ).isEqualTo( PARAMS.siteUrl() );
    }

    @Test
    void lowerCasesHostOfRequestUrl()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );
        when( cache.get( any() ) ).thenReturn( guillotine( new SitemapEntry( "/a", "portal:site", null ) ) );

        final SitemapFiles files =
            inContext( () -> service().files( PARAMS.withSiteUrl( "https://WWW.Example.COM:8443/My%20Site" ), true ) );

        assertThat( SitemapTest.text( files.root() ) ).contains( "<loc>https://www.example.com:8443/My%20Site/a</loc>" );
    }

    @Test
    void usesRequestUrlWhenBaseUrlCannotBeResolved()
    {
        when( portalUrlService.portalScope( any() ) ).thenThrow( new IllegalStateException( "boom" ) );

        files( true, true );
    }

    @Test
    void ignoresBaseUrlWhenOverridden()
    {
        assertThat( files( false, false ).params().siteUrl() ).isEqualTo( PARAMS.siteUrl() );
    }

    @Test
    void beanAnswers400ForRequestUrlLongerThanAGuillotineSitemapAllows()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );
        when( cache.get( any() ) ).thenReturn( guillotine( new SitemapEntry( "/a", "portal:site", null ) ) );
        final String siteUrl = "https://example.com/" + "a".repeat( Sitemap.MAX_BASE_URL_LENGTH );
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn( Map.of( "sitePath", "/mysite", "siteUrl", siteUrl, "siteUrlFromRequest", true ) );

        assertThat( inContext( () -> bean().root( value ) ).getStatus() ).isEqualTo( 400 );
    }

    @Test
    void beanAnswers429WhenBusy()
    {
        when( cache.get( any() ) ).thenThrow( new SitemapBusyException() );

        final SitemapResponse response = inContext( () -> bean().root( scriptValue() ) );

        assertThat( response.getStatus() ).isEqualTo( 429 );
        assertThat( response.getRetryAfter() ).isEqualTo( SitemapResponse.RETRY_AFTER_SECONDS );
        assertThat( response.getBody() ).isNull();
    }

    @Test
    void beanAnswersFilesAnd404()
    {
        final List<SitemapEntry> entries =
            List.of( new SitemapEntry( "/a", "portal:site", null ), new SitemapEntry( "/b", "portal:site", null ) );
        // an anonymous request with the site URL of the request: converted from the Guillotine sitemap
        when( cache.get( any() ) ).thenReturn(
            SitemapTest.write( temp, new SitemapParams( "/mysite", "", List.of(), List.of(), SitemapParams.Format.GUILLOTINE ), entries,
                               1, Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES ) );
        final SitemapBean bean = bean();

        assertThat( SitemapTest.text( inContext( () -> bean.root( scriptValue() ) ).getBody() ) ).contains( "<sitemapindex" );
        assertThat( SitemapTest.text( inContext( () -> bean.file( scriptValue(), 2 ) ).getBody() ) ).contains( "https://example.com/b" );
        assertThat( inContext( () -> bean.file( scriptValue(), 3 ) ).getStatus() ).isEqualTo( 404 );
        assertThat( inContext( () -> bean.root( scriptValue() ) ).getRetryAfter() ).isZero();
        assertThat( inContext( () -> bean.rootGzip( scriptValue() ) ).getBody() ).isNotNull();
        assertThat( inContext( () -> bean.rootEncoded( scriptValue() ) ).getBody() ).isNotNull();
        assertThat( inContext( () -> bean.fileGzip( scriptValue(), 1 ) ).getBody() ).isNotNull();
        assertThat( inContext( () -> bean.fileGzip( scriptValue(), 3 ) ).getStatus() ).isEqualTo( 404 );
    }

    @Test
    void beanAnswers304WhenEntityTagMatches()
    {
        final List<SitemapEntry> entries = List.of( new SitemapEntry( "/a", "portal:site", null ) );
        when( cache.get( any() ) ).thenReturn(
            SitemapTest.write( temp, new SitemapParams( "/mysite", "https://example.com", List.of(), List.of() ), entries, 10,
                               Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES ) );
        final SitemapBean bean = bean();

        final SitemapResponse first = inContext( () -> bean.root( scriptValue() ) );
        assertThat( first.getStatus() ).isEqualTo( 200 );
        final String etag = first.getETag();
        assertThat( etag ).isNotNull();

        final SitemapResponse unchanged = inContext( () -> bean.root( scriptValue( etag ) ) );
        assertThat( unchanged.getStatus() ).isEqualTo( 304 );
        assertThat( unchanged.getBody() ).isNull();
        assertThat( unchanged.getETag() ).isEqualTo( etag );

        assertThat( inContext( () -> bean.root( scriptValue( "\"other\"" ) ) ).getStatus() ).isEqualTo( 200 );
        assertThat( inContext( () -> bean.root( scriptValue( "W/" + etag ) ) ).getStatus() ).isEqualTo( 200 );
        assertThat( inContext( () -> bean.rootEncoded( scriptValue( etag ) ) ).getStatus() ).isEqualTo( 200 );
        final String encoded = inContext( () -> bean.rootEncoded( scriptValue() ) ).getETag();
        assertThat( encoded ).isEqualTo( etag.substring( 0, etag.length() - 1 ) + "-gzip\"" );
        assertThat( inContext( () -> bean.rootEncoded( scriptValue( encoded ) ) ).getStatus() ).isEqualTo( 304 );
    }

    @Test
    void beanSendsLastModifiedAndAnswers304WhenNotModifiedSince()
    {
        final List<SitemapEntry> entries = List.of( new SitemapEntry( "/a", "portal:site", null ) );
        when( cache.get( any() ) ).thenReturn(
            SitemapTest.write( temp, new SitemapParams( "/mysite", "https://example.com", List.of(), List.of() ), entries, 10,
                               Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES ) );
        final SitemapBean bean = bean();

        final SitemapResponse first = inContext( () -> bean.root( scriptValue() ) );
        // the files were last changed at SitemapTest.NOW
        assertThat( first.getLastModified() ).isEqualTo( "Sat, 01 Jun 2024 00:00:00 GMT" );

        assertThat( inContext( () -> bean.root( since( "Sat, 01 Jun 2024 00:00:00 GMT", null ) ) ).getStatus() ).isEqualTo( 304 );
        assertThat( inContext( () -> bean.root( since( "Sun, 02 Jun 2024 00:00:00 GMT", null ) ) ).getStatus() ).isEqualTo( 304 );
        assertThat( inContext( () -> bean.root( since( "Fri, 31 May 2024 23:59:59 GMT", null ) ) ).getStatus() ).isEqualTo( 200 );
        assertThat( inContext( () -> bean.root( since( "not a date", null ) ) ).getStatus() ).isEqualTo( 200 );
        // If-None-Match decides when present
        assertThat( inContext( () -> bean.root( since( "Sun, 02 Jun 2024 00:00:00 GMT", "\"other\"" ) ) ).getStatus() ).isEqualTo(
            200 );
    }

    private static ScriptValue since( final String ifModifiedSince, final @Nullable String ifNoneMatch )
    {
        final Map<String, Object> map = new HashMap<>(
            Map.of( "sitePath", "/mysite", "siteUrl", "https://example.com", "siteUrlFromRequest", true, "ifModifiedSince",
                    ifModifiedSince ) );
        if ( ifNoneMatch != null )
        {
            map.put( "ifNoneMatch", ifNoneMatch );
        }
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn( map );
        return value;
    }

    @Test
    void beanAnswersGuillotineQueries()
    {
        final List<SitemapEntry> entries = List.of( new SitemapEntry( "/a", "portal:site", Instant.parse( "2024-01-01T00:00:00Z" ) ),
                                                    new SitemapEntry( "/b", "portal:site", null ) );
        when( cache.get( any() ) ).thenReturn(
            SitemapTest.write( temp.resolve( "g" ), new SitemapParams( "/mysite", "", List.of(), List.of(), SitemapParams.Format.GUILLOTINE ),
                               entries, 1, Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES ) );
        final SitemapBean bean = bean();

        assertThat( inContext( () -> bean.guillotineFiles( scriptValue() ) ) ).isEqualTo(
            "[{\"number\":1,\"lastmod\":\"2024-06-01T00:00:00Z\"},{\"number\":2,\"lastmod\":\"2024-06-01T00:00:00Z\"}]" );
        assertThat( inContext( () -> bean.guillotineUrlset( scriptValue(), 1 ) ) ).isEqualTo(
            "[{\"path\":\"/a\",\"lastmod\":\"2024-01-01T00:00:00Z\",\"changefreq\":\"hourly\",\"priority\":\"1.0\"}]" );
        assertThat( inContext( () -> bean.guillotineFile( scriptValue(), 2 ) ) ).isEqualTo(
            "[{\"path\":\"/b\",\"changefreq\":\"hourly\",\"priority\":\"1.0\"}]" );
        assertThat( inContext( () -> bean.guillotineFile( scriptValue(), 3 ) ) ).isNull();

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache, atLeastOnce() ).get( key.capture() );
        assertThat( key.getValue().isPublic() ).isTrue();
        assertThat( key.getValue().branch() ).isEqualTo( "master" );
        assertThat( key.getValue().params().format() ).isEqualTo( SitemapParams.Format.GUILLOTINE );
        assertThat( key.getValue().params().siteUrl() ).isEmpty();
    }

    /**
     * The files of {@code sitemap.xml} for an anonymous request: a sitemap of their own, or converted from the Guillotine sitemap.
     *
     * @return the key of the sitemap got from the cache
     */
    private SitemapCache.Key files( final boolean siteUrlFromRequest, final boolean converted )
    {
        when( cache.get( any() ) ).thenReturn( write( PARAMS ) );
        final SitemapFiles files = inServedRequest( () -> inContext( () -> service().files( PARAMS, siteUrlFromRequest ) ) );
        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture() );
        assertThat( files ).isInstanceOf( converted ? ConvertedSitemap.class : Sitemap.class );
        assertThat( key.getValue().params().format() ).isEqualTo(
            converted ? SitemapParams.Format.GUILLOTINE : SitemapParams.Format.XML );
        if ( converted )
        {
            assertThat( key.getValue().params().siteUrl() ).isEmpty();
        }
        return key.getValue();
    }

    private SitemapService service()
    {
        return new SitemapService( contentService, projectService, portalUrlService, cache, Clock.systemUTC(), virtualHostService );
    }

    private Sitemap guillotine( final SitemapEntry... entries )
    {
        return SitemapTest.write( temp.resolve( "g" + System.nanoTime() ),
                                  new SitemapParams( "/mysite", "", List.of(), List.of(), SitemapParams.Format.GUILLOTINE ), List.of( entries ),
                                  10, Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES );
    }

    private void virtualHosts( final String name, final String host )
    {
        final VirtualHost virtualHost = mock( VirtualHost.class );
        lenient().when( virtualHost.getName() ).thenReturn( name );
        lenient().when( virtualHost.getHost() ).thenReturn( host );
        configured.add( virtualHost );
        lenient().when( virtualHostService.isEnabled() ).thenReturn( true );
        lenient().when( virtualHostService.getVirtualHosts() ).thenReturn( configured );
    }

    /**
     * Runs as a portal request that came through the virtual host named {@link #served}, if any.
     */
    private <T> T inServedRequest( final Supplier<T> supplier )
    {
        if ( served == null )
        {
            return supplier.get();
        }
        final VirtualHost virtualHost = mock( VirtualHost.class );
        when( virtualHost.getName() ).thenReturn( served );
        final HttpServletRequest raw = mock( HttpServletRequest.class );
        when( raw.getAttribute( VirtualHost.class.getName() ) ).thenReturn( virtualHost );
        final PortalRequest request = new PortalRequest();
        request.setRawRequest( raw );
        return PortalRequestAccessor.callWith( request, supplier );
    }

    private SitemapBean bean()
    {
        final BeanContext context = mock( BeanContext.class );
        when( context.getService( ContentService.class ) ).thenReturn( () -> contentService );
        when( context.getService( ProjectService.class ) ).thenReturn( () -> projectService );
        when( context.getService( PortalUrlService.class ) ).thenReturn( () -> portalUrlService );
        when( context.getService( SitemapCache.class ) ).thenReturn( () -> cache );
        when( context.getService( VirtualHostService.class ) ).thenReturn( () -> virtualHostService );
        final SitemapBean bean = new SitemapBean();
        bean.initialize( context );
        return bean;
    }

    private static ScriptValue scriptValue()
    {
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn(
            Map.of( "sitePath", "/mysite", "siteUrl", "https://example.com", "siteUrlFromRequest", true ) );
        return value;
    }

    private static ScriptValue scriptValue( final String ifNoneMatch )
    {
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn(
            Map.of( "sitePath", "/mysite", "siteUrl", "https://example.com", "siteUrlFromRequest", true, "ifNoneMatch", ifNoneMatch ) );
        return value;
    }

    private static PortalScope scope( final String baseUrl )
    {
        final PortalScope.Builder builder =
            PortalScope.create()
                .projectName( ProjectName.from( "project" ) )
                .branch( Branch.from( "master" ) )
                .path( ContentPath.from( "/mysite" ) )
                .sitePath( ContentPath.from( "/mysite" ) );
        if ( baseUrl != null )
        {
            final PropertyTree config = new PropertyTree();
            config.setString( "baseUrl", baseUrl );
            builder.siteConfigs( SiteConfigs.from( SiteConfig.create().application( ApplicationKey.PORTAL ).config( config ).build() ) );
        }
        return builder.build();
    }

    private static <T> T inContext( final Supplier<T> supplier )
    {
        return ContextBuilder.create().repositoryId( "com.enonic.cms.project" ).branch( "master" ).build().callWith( supplier::get );
    }

    private Sitemap write( final SitemapParams params )
    {
        return SitemapTest.write( temp.resolve( "w" + System.nanoTime() ), params, List.of(), 10, Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES );
    }

    private static <T> T asUser( final Supplier<T> supplier )
    {
        final User user = User.create()
            .key( PrincipalKey.ofUser( IdProviderKey.system(), "editor" ) )
            .login( "editor" )
            .displayName( "Editor" )
            .build();
        return ContextBuilder.create()
            .repositoryId( "com.enonic.cms.project" )
            .branch( "master" )
            .authInfo( AuthenticationInfo.create().user( user ).build() )
            .build()
            .callWith( supplier::get );
    }

    private static Content site( final AccessControlList permissions )
    {
        final Content site = mock( Content.class );
        when( site.getPermissions() ).thenReturn( permissions );
        return site;
    }
}

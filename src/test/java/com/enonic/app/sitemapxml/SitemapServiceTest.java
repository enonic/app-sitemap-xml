package com.enonic.app.sitemapxml;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.enonic.xp.app.ApplicationKey;
import com.enonic.xp.branch.Branch;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.portal.url.PortalScope;
import com.enonic.xp.portal.url.PortalScopeParams;
import com.enonic.xp.portal.url.PortalUrlService;
import com.enonic.xp.project.ProjectName;
import com.enonic.xp.script.ScriptValue;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.site.SiteConfig;
import com.enonic.xp.site.SiteConfigs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SitemapServiceTest
{
    private static final SitemapParams PARAMS =
        new SitemapParams( "/mysite", "https://request.example.com/mysite", List.of(), List.of(), 10, 10 );

    @TempDir
    private Path temp;

    @Mock
    private ContentService contentService;

    @Mock
    private PortalUrlService portalUrlService;

    @Mock
    private SitemapCache cache;

    @Test
    void usesBaseUrlOfSite()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( "https://www.example.com/" ) );

        final SitemapCache.Key key = sitemap( true );

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
    void readsPublishedContentWhenRequestedFromDraft()
    {
        ContextBuilder.create()
            .repositoryId( "com.enonic.cms.project" )
            .branch( "draft" )
            .build()
            .callWith( () -> new SitemapService( contentService, portalUrlService, cache, Clock.systemUTC() ).sitemap( PARAMS, false ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture(), any( AuthenticationInfo.class ), any() );
        assertThat( key.getValue().branch() ).isEqualTo( "master" );
        assertThat( key.getValue().repositoryId() ).isEqualTo( "com.enonic.cms.project" );
    }

    @Test
    void usesRequestUrlWithoutBaseUrl()
    {
        when( portalUrlService.portalScope( any() ) ).thenReturn( scope( null ) );

        assertThat( sitemap( true ).params().siteUrl() ).isEqualTo( PARAMS.siteUrl() );
    }

    @Test
    void usesRequestUrlWhenBaseUrlCannotBeResolved()
    {
        when( portalUrlService.portalScope( any() ) ).thenThrow( new IllegalStateException( "boom" ) );

        assertThat( sitemap( true ).params().siteUrl() ).isEqualTo( PARAMS.siteUrl() );
    }

    @Test
    void ignoresBaseUrlWhenOverridden()
    {
        assertThat( sitemap( false ).params().siteUrl() ).isEqualTo( PARAMS.siteUrl() );
    }

    @Test
    void beanAnswers429WhenBusy()
    {
        when( cache.get( any(), any(), any() ) ).thenThrow( new SitemapBusyException() );

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
        when( cache.get( any(), any(), any() ) ).thenReturn(
            Sitemap.write( temp, new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10, 1 ), entries ) );
        final SitemapBean bean = bean();

        assertThat( SitemapTest.text( inContext( () -> bean.root( scriptValue() ) ).getBody() ) ).contains( "<sitemapindex" );
        assertThat( SitemapTest.text( inContext( () -> bean.file( scriptValue(), 2 ) ).getBody() ) ).contains( "https://example.com/b" );
        assertThat( inContext( () -> bean.file( scriptValue(), 3 ) ).getStatus() ).isEqualTo( 404 );
        assertThat( SitemapTest.text( inContext( () -> bean.json( scriptValue() ) ).getBody() ) ).startsWith( "[{" );
        assertThat( inContext( () -> bean.root( scriptValue() ) ).getRetryAfter() ).isZero();
        assertThat( inContext( () -> bean.rootGzip( scriptValue() ) ).getBody() ).isNotNull();
        assertThat( inContext( () -> bean.rootEncoded( scriptValue() ) ).getBody() ).isNotNull();
        assertThat( inContext( () -> bean.fileGzip( scriptValue(), 1 ) ).getBody() ).isNotNull();
        assertThat( inContext( () -> bean.fileGzip( scriptValue(), 3 ) ).getStatus() ).isEqualTo( 404 );
    }

    @Test
    void beanAnswersGuillotineQueries()
    {
        final List<SitemapEntry> entries = List.of( new SitemapEntry( "/a", "portal:site", Instant.parse( "2024-01-01T00:00:00Z" ) ),
                                                    new SitemapEntry( "/b", "portal:site", null ) );
        when( cache.get( any(), any(), any() ) ).thenReturn(
            Sitemap.write( temp.resolve( "g" ), new SitemapParams( "/mysite", "", List.of(), List.of(), 10, 1 ), entries ) );
        final SitemapBean bean = bean();

        assertThat( inContext( () -> bean.guillotineFiles( scriptValue() ) ) ).isEqualTo(
            "[{\"number\":1,\"lastmod\":\"2024-01-01T00:00:00Z\"},{\"number\":2}]" );
        assertThat( inContext( () -> bean.guillotineUrlset( scriptValue(), 1 ) ) ).isEqualTo(
            "[{\"path\":\"/a\",\"lastmod\":\"2024-01-01T00:00:00Z\",\"changefreq\":\"hourly\",\"priority\":\"1.0\"}]" );
        assertThat( inContext( () -> bean.guillotineFile( scriptValue(), 2 ) ) ).isEqualTo(
            "[{\"path\":\"/b\",\"changefreq\":\"hourly\",\"priority\":\"1.0\"}]" );
        assertThat( inContext( () -> bean.guillotineFile( scriptValue(), 3 ) ) ).isNull();

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache, atLeastOnce() ).get( key.capture(), any( AuthenticationInfo.class ), any() );
        assertThat( key.getValue().branch() ).isEqualTo( "master" );
    }

    private SitemapCache.Key sitemap( final boolean preferBaseUrl )
    {
        inContext( () -> new SitemapService( contentService, portalUrlService, cache, Clock.systemUTC() ).sitemap( PARAMS, preferBaseUrl ) );
        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture(), any( AuthenticationInfo.class ), any() );
        return key.getValue();
    }

    private SitemapBean bean()
    {
        final BeanContext context = mock( BeanContext.class );
        when( context.getService( ContentService.class ) ).thenReturn( () -> contentService );
        when( context.getService( PortalUrlService.class ) ).thenReturn( () -> portalUrlService );
        when( context.getService( SitemapCache.class ) ).thenReturn( () -> cache );
        final SitemapBean bean = new SitemapBean();
        bean.initialize( context );
        return bean;
    }

    private static ScriptValue scriptValue()
    {
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn(
            Map.of( "sitePath", "/mysite", "siteUrl", "https://example.com", "maxItems", 10, "urlsPerFile", 10 ) );
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
}

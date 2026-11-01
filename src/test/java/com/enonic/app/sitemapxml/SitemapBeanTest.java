package com.enonic.app.sitemapxml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentIds;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentQuery;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.content.FindContentIdsByQueryResult;
import com.enonic.xp.context.ContextBuilder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SitemapBeanTest
{
    @TempDir
    private Path temp;

    @Mock
    private ContentService contentService;

    @Mock
    private SitemapCache cache;

    @Test
    void answers429WhenBusy()
    {
        when( cache.get( any(), anyBoolean(), any(), any() ) ).thenThrow( new SitemapBusyException() );

        final SitemapResponse response = inContext( () -> bean().root( scriptValue() ) );

        assertThat( response.getStatus() ).isEqualTo( 429 );
        assertThat( response.getRetryAfter() ).isEqualTo( SitemapResponse.RETRY_AFTER_SECONDS );
        assertThat( response.getBody() ).isNull();
    }

    @Test
    void answersFilesAnd404()
    {
        final List<SitemapEntry> entries =
            List.of( new SitemapEntry( "/a", "portal:site", null ), new SitemapEntry( "/b", "portal:site", null ) );
        when( cache.get( any(), anyBoolean(), any(), any() ) ).thenReturn(
            write( new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10 ), 1, entries ) );
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
    void keysByContext()
    {
        when( cache.get( any(), anyBoolean(), any(), any() ) ).thenReturn(
            write( new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10 ), 10, List.of() ) );

        inContext( () -> bean().root( scriptValue() ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture(), eq( true ), any( AuthenticationInfo.class ), any() );
        assertThat( key.getValue().repositoryId() ).isEqualTo( "com.enonic.cms.project" );
        assertThat( key.getValue().branch() ).isEqualTo( "master" );
        assertThat( key.getValue().params().siteUrl() ).isEqualTo( "https://example.com" );
    }

    @Test
    void draftGetsOnlyTheSiteWithoutCaching()
    {
        when( cache.uncached( any(), any(), any() ) ).thenReturn(
            write( new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10 ) ) );
        ContextBuilder.create().repositoryId( "com.enonic.cms.project" ).branch( "draft" ).build().callWith( () -> bean().root( scriptValue() ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        final ArgumentCaptor<SitemapCache.Source> source = ArgumentCaptor.forClass( SitemapCache.Source.class );
        verify( cache ).uncached( key.capture(), any( AuthenticationInfo.class ), source.capture() );
        verify( cache, never() ).get( any(), anyBoolean(), any(), any() );
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
        when( cache.get( any(), anyBoolean(), any(), any() ) ).thenReturn( write( new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10 ) ) );

        asUser( () -> bean().root( scriptValue() ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture(), anyBoolean(), any( AuthenticationInfo.class ), any() );
        assertThat( key.getValue().principals() ).contains( "user:system:editor" );
        verify( cache, never() ).uncached( any(), any(), any() );
    }

    @Test
    void loggedInUserSharesPublicSitemapOfSiteReadableByEveryone()
    {
        final Content site =
            site( AccessControlList.of( AccessControlEntry.create().principal( RoleKeys.EVERYONE ).allow( Permission.READ ).build() ) );
        when( contentService.getByPath( ContentPath.from( "/mysite" ) ) ).thenReturn( site );
        when( cache.get( any(), anyBoolean(), any(), any() ) ).thenReturn( write( new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10 ) ) );

        asUser( () -> bean().root( scriptValue() ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        final ArgumentCaptor<AuthenticationInfo> authInfo = ArgumentCaptor.forClass( AuthenticationInfo.class );
        verify( cache ).get( key.capture(), anyBoolean(), authInfo.capture(), any() );
        assertThat( authInfo.getValue().isAuthenticated() ).isFalse();
        assertThat( key.getValue().principals() ).containsExactlyInAnyOrder( PrincipalKey.ofAnonymous().toString(),
                                                                             RoleKeys.EVERYONE.toString() );
    }

    @Test
    void beanAnswers400ForInvalidRequestUrl()
    {
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn( Map.of( "sitePath", "/mysite", "siteUrl", "ftp://example.com", "maxItems", 10, "siteUrlFromRequest", true ) );

        final SitemapResponse response = inContext( () -> bean().root( value ) );

        assertThat( response.getStatus() ).isEqualTo( 400 );
        verify( cache, never() ).get( any(), anyBoolean(), any(), any() );
    }

    @Test
    void answersGuillotineUrlset()
    {
        when( cache.get( any(), anyBoolean(), any(), any() ) ).thenReturn(
            write( new SitemapParams( "/mysite", "", List.of(), List.of(), 10 ), 1,
                   List.of( new SitemapEntry( "", "portal:site", null ), new SitemapEntry( "/b", "portal:site", null ) ) ) );

        assertThat( inContext( () -> bean().guillotineUrlset( scriptValue(), 10 ) ) ).isEqualTo(
            "[{\"path\":\"/\",\"changefreq\":\"hourly\",\"priority\":\"1.0\"},{\"path\":\"/b\",\"changefreq\":\"hourly\",\"priority\":\"1.0\"}]" );
    }

    @Test
    void generatesRequestBoundSitemapOnRequestOnly()
    {
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn(
            Map.of( "sitePath", "/mysite", "siteUrl", "https://example.com", "maxItems", 10, "siteUrlFromRequest", true ) );
        when( cache.get( any(), anyBoolean(), any(), any() ) ).thenReturn(
            write( new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10 ), 10, List.of() ) );

        inContext( () -> bean().root( value ) );

        final ArgumentCaptor<SitemapCache.Source> source = ArgumentCaptor.forClass( SitemapCache.Source.class );
        verify( cache ).get( any(), eq( false ), any( AuthenticationInfo.class ), source.capture() );

        when( contentService.find( any( ContentQuery.class ) ) ).thenReturn(
            FindContentIdsByQueryResult.create().contents( ContentIds.empty() ).totalHits( 0 ).build() );
        final List<SitemapEntry> entries = new ArrayList<>();
        assertThat( inContext( () -> source.getValue().write( entries::add, () -> false ) ) ).isNull();
        assertThat( entries ).isEmpty();
    }

    @Test
    void lowerCasesHostOfRequestUrl()
    {
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn(
            Map.of( "sitePath", "/mysite", "siteUrl", "https://WWW.Example.COM:8443/My%20Site", "maxItems", 10, "siteUrlFromRequest",
                    true ) );
        when( cache.get( any(), anyBoolean(), any(), any() ) ).thenReturn(
            write( new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10 ), 10, List.of() ) );

        inContext( () -> bean().root( value ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture(), eq( false ), any( AuthenticationInfo.class ), any() );
        assertThat( key.getValue().params().siteUrl() ).isEqualTo( "https://www.example.com:8443/My%20Site" );
    }

    private Sitemap write( final SitemapParams params, final int urlsPerFile, final List<SitemapEntry> entries )
    {
        return SitemapTest.write( temp.resolve( "s" + System.nanoTime() ), params, entries, urlsPerFile, Sitemap.MAX_FILE_BYTES,
                                  Sitemap.MAX_FILES );
    }

    private SitemapBean bean()
    {
        final BeanContext context = mock( BeanContext.class );
        when( context.getService( ContentService.class ) ).thenReturn( () -> contentService );
        when( context.getService( SitemapCache.class ) ).thenReturn( () -> cache );
        final SitemapBean bean = new SitemapBean();
        bean.initialize( context );
        return bean;
    }

    private static ScriptValue scriptValue()
    {
        final ScriptValue value = mock( ScriptValue.class );
        when( value.getMap() ).thenReturn(
            Map.of( "sitePath", "/mysite", "siteUrl", "https://example.com", "maxItems", 10 ) );
        return value;
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

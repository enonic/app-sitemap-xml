package com.enonic.app.sitemapxml;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.node.NodeService;
import com.enonic.xp.script.ScriptValue;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.security.auth.AuthenticationInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SitemapBeanTest
{
    @Mock
    private NodeService nodeService;

    @Mock
    private SitemapCache cache;

    @Test
    void answers503WhenBusy()
    {
        when( cache.get( any(), any(), any() ) ).thenThrow( new SitemapBusyException() );

        final SitemapResponse response = inContext( () -> bean().root( scriptValue() ) );

        assertThat( response.getStatus() ).isEqualTo( 503 );
        assertThat( response.getRetryAfter() ).isEqualTo( SitemapResponse.RETRY_AFTER_SECONDS );
        assertThat( response.getBody() ).isNull();
    }

    @Test
    void answersFilesAnd404()
    {
        final List<SitemapEntry> entries =
            List.of( new SitemapEntry( "/a", "portal:site", null ), new SitemapEntry( "/b", "portal:site", null ) );
        when( cache.get( any(), any(), any() ) ).thenReturn(
            new Sitemap( new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10, 1 ), entries ) );
        final SitemapBean bean = bean();

        assertThat( inContext( () -> bean.root( scriptValue() ) ).getBody() ).contains( "<sitemapindex" );
        assertThat( inContext( () -> bean.file( scriptValue(), 2 ) ).getBody() ).contains( "https://example.com/b" );
        assertThat( inContext( () -> bean.file( scriptValue(), 3 ) ).getStatus() ).isEqualTo( 404 );
        assertThat( inContext( () -> bean.json( scriptValue() ) ).getBody() ).startsWith( "[{" );
        assertThat( inContext( () -> bean.root( scriptValue() ) ).getRetryAfter() ).isZero();
    }

    @Test
    void keysByContext()
    {
        when( cache.get( any(), any(), any() ) ).thenReturn(
            new Sitemap( new SitemapParams( "/mysite", "https://example.com", List.of(), List.of(), 10, 10 ), List.of() ) );

        inContext( () -> bean().root( scriptValue() ) );

        final ArgumentCaptor<SitemapCache.Key> key = ArgumentCaptor.forClass( SitemapCache.Key.class );
        verify( cache ).get( key.capture(), any( AuthenticationInfo.class ), any() );
        assertThat( key.getValue().repositoryId() ).isEqualTo( "com.enonic.cms.project" );
        assertThat( key.getValue().branch() ).isEqualTo( "master" );
        assertThat( key.getValue().params().siteUrl() ).isEqualTo( "https://example.com" );
    }

    private SitemapBean bean()
    {
        final BeanContext context = mock( BeanContext.class );
        when( context.getService( NodeService.class ) ).thenReturn( () -> nodeService );
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

    private static <T> T inContext( final Supplier<T> supplier )
    {
        return ContextBuilder.create().repositoryId( "com.enonic.cms.project" ).branch( "master" ).build().callWith( supplier::get );
    }
}

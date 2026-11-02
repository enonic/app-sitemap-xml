package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.enonic.xp.script.bean.BeanContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateTaskBeanTest
{
    private static final SitemapCache.Key KEY =
        new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone" ),
                              new SitemapParams( "/mysite", "https://example.com", List.of(), List.of() ) );

    @Test
    void runsTaskOfKeyOnThisNode()
        throws Exception
    {
        final SitemapCache cache = mock( SitemapCache.class );
        final BeanContext context = mock( BeanContext.class );
        when( context.getService( SitemapCache.class ) ).thenReturn( () -> cache );
        final GenerateTaskBean bean = new GenerateTaskBean();
        bean.initialize( context );
        final String key = new ObjectMapper().writeValueAsString( KEY.store() );

        bean.run( key, true, "2024-01-01T00:00:00Z" );
        bean.run( key, false, null );

        verify( cache ).runTask( KEY, true, Instant.parse( "2024-01-01T00:00:00Z" ) );
        verify( cache ).runTask( KEY, false, null );
    }
}

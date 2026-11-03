package com.enonic.app.sitemapxml;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.enonic.xp.data.PropertySet;
import com.enonic.xp.data.PropertyTree;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapNodeDataTest
{
    private static final SitemapParams PARAMS =
        new SitemapParams( "/mysite", "", List.of( new ContentTypeSetting( "base:folder", "daily", "0.7" ),
                                                   new ContentTypeSetting( "portal:site", "", "" ) ), List.of( "/skip/", "*/foo" ) );

    private static final SitemapCache.Key KEY =
        new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "user:system:anonymous", "role:system.everyone" ), PARAMS );

    @TempDir
    private Path temp;

    @Test
    void keyAndSitemapAreNodeProperties()
    {
        final Sitemap sitemap = sitemap().withCount( 3, Instant.parse( "2024-06-01T00:00:00Z" ) );
        final PropertyTree data = new PropertyTree();

        SitemapNodeData.writeKey( data.getRoot(), KEY );
        SitemapNodeData.writeSitemap( data.getRoot(), sitemap );

        final PropertySet key = data.getSet( "key" );
        assertThat( key.getString( "repository" ) ).isEqualTo( "com.enonic.cms.project" );
        assertThat( key.getStrings( "principals" ) ).containsExactly( "role:system.everyone", "user:system:anonymous" );
        assertThat( key.getString( "siteUrl" ) ).isEmpty();
        assertThat( key.getSets( "contentTypes" ) ).extracting( set -> set.getString( "changefreq" ) ).containsExactly( "daily", "monthly" );
        assertThat( key.getStrings( "ignorePatterns" ) ).containsExactly( "/skip/", "*/foo" );

        final PropertySet described = data.getSet( "sitemap" );
        assertThat( described.getLong( "size" ) ).isEqualTo( 3 );
        assertThat( described.getLong( "count" ) ).isEqualTo( 3 );
        assertThat( described.getInstant( "cutoff" ) ).isEqualTo( Instant.parse( "2024-06-01T00:00:00Z" ) );
        assertThat( described.getInstant( "modified" ) ).isEqualTo( SitemapTest.NOW );
        assertThat( described.getSets( "files" ) ).extracting( file -> file.getInstant( "lastmod" ) )
            .containsExactlyElementsOf( sitemap.lastModified() );
        assertThat( described.getSets( "bytes" ) ).extracting( file -> file.getString( "name" ) ).contains( "sitemap.xml", "sitemap-1.xml" );
        assertThat( described.getSets( "namedDigests" ) ).extracting( file -> file.getString( "name" ) ).contains( "sitemap-index.xml" );
        assertThat( key.getString( "format" ) ).isEqualTo( "xml" );

        assertThat( SitemapNodeData.readKey( data.getRoot() ) ).isEqualTo( KEY );
        final Sitemap read = SitemapNodeData.readSitemap( data.getRoot(), Sitemap.FileSource.directory( sitemap.directory() ) );
        assertThat( read.store() ).isEqualTo( sitemap.store() );
        assertThat( read.rootETag( true ) ).isEqualTo( sitemap.rootETag( true ) );
    }

    @Test
    void placeholderHasNoSitemap()
    {
        final PropertyTree data = new PropertyTree();
        SitemapNodeData.writeKey( data.getRoot(), KEY );

        assertThat( SitemapNodeData.readSitemap( data.getRoot(), Sitemap.FileSource.directory( temp ) ) ).isNull();
    }

    private Sitemap sitemap()
    {
        return SitemapTest.write( temp.resolve( "sitemap" ), PARAMS,
                                  List.of( new SitemapEntry( "", "portal:site", null ), new SitemapEntry( "/a", "base:folder", null ),
                                           new SitemapEntry( "/b", "base:folder", null ) ), 2, Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES );
    }
}

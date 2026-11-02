package com.enonic.app.sitemapxml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.enonic.xp.branch.Branch;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.node.ApplyVersionAttributesParams;
import com.enonic.xp.node.Attributes;
import com.enonic.xp.node.CreateNodeParams;
import com.enonic.xp.node.Node;
import com.enonic.xp.node.NodeAlreadyExistAtPathException;
import com.enonic.xp.node.NodeId;
import com.enonic.xp.node.NodePath;
import com.enonic.xp.node.NodeService;
import com.enonic.xp.node.NodeVersion;
import com.enonic.xp.node.NodeVersionId;
import com.enonic.xp.node.UpdateNodeParams;
import com.enonic.xp.repository.RepositoryService;
import com.enonic.xp.security.RoleKeys;
import com.enonic.xp.security.acl.AccessControlEntry;
import com.enonic.xp.security.acl.AccessControlList;
import com.enonic.xp.util.BinaryReference;
import com.enonic.xp.util.GenericValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositorySitemapStoreTest
{
    private static final SitemapParams PARAMS = new SitemapParams( "/mysite", "https://example.com", List.of(), List.of() );

    private static final SitemapCache.Key KEY =
        new SitemapCache.Key( "com.enonic.cms.project", "master", Set.of( "role:system.everyone", "user:system:anonymous" ), PARAMS );

    private static final Instant NOW = Instant.parse( "2024-01-01T10:00:00Z" );

    @Mock
    private NodeService nodeService;

    @Mock
    private RepositoryService repositoryService;

    @TempDir
    private Path temp;

    @Test
    void storesFilesAsAttachmentsOfNodeNamedByKeyAndReadsThemFromTheVersion()
        throws Exception
    {
        final Sitemap local = SitemapTest.write( temp.resolve( "local" ), PARAMS, List.of( new SitemapEntry( "/a", "portal:site", null ),
                                                                                           new SitemapEntry( "/b", "portal:site", null ) ), 1,
                                                 Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES );
        final NodeVersionId versionId = NodeVersionId.from( "a1b2c3d4-0000-0000-0000-000000000001" );
        final NodeId nodeId = NodeId.from( "sitemap" );
        final AtomicReference<String> repository = new AtomicReference<>();
        when( nodeService.create( any() ) ).thenAnswer( invocation -> {
            repository.set( ContextAccessor.current().getRepositoryId() + " " + ContextAccessor.current().getBranch() );
            final CreateNodeParams params = invocation.getArgument( 0 );
            return Node.create()
                .id( nodeId )
                .name( params.getName() )
                .parentPath( NodePath.ROOT )
                .data( params.getData() )
                .nodeVersionId( versionId )
                .build();
        } );
        final NodeVersion version = version( Attributes.create().build() );
        when( nodeService.getVersion( nodeId, versionId ) ).thenReturn( version );
        when( nodeService.getBinary( eq( nodeId ), eq( versionId ), any() ) ).thenAnswer( invocation -> {
            final String name = invocation.<BinaryReference>getArgument( 2 ).toString();
            return Sitemap.FileSource.directory( local.directory() ).gzipped( name.substring( 0, name.length() - 3 ) );
        } );

        final SitemapStore.Stored stored =
            store().save( KEY.id(), KEY, true, local, NOW.plusSeconds( 3600 ), NOW.minusSeconds( 5 ), NOW );

        final ArgumentCaptor<CreateNodeParams> create = ArgumentCaptor.forClass( CreateNodeParams.class );
        verify( nodeService ).create( create.capture() );
        assertThat( repository.get() ).isEqualTo( "com.enonic.app.sitemapxml " + Branch.from( "master" ) );
        assertThat( create.getValue().getName().toString() ).isEqualTo( KEY.id() );
        assertThat( create.getValue().getData().getString( "repository" ) ).isEqualTo( "com.enonic.cms.project" );
        assertThat( create.getValue().getData().getString( "principals" ) ).isEqualTo( "role:system.everyone,user:system:anonymous" );
        assertThat( create.getValue().getData().getSet( "key" ).getString( "sitePath" ) ).isEqualTo( "/mysite" );
        assertThat( create.getValue().getData().getSet( "key" ).getStrings( "principals" ) ).containsExactly( "role:system.everyone",
                                                                                                            "user:system:anonymous" );
        assertThat( create.getValue().getData().getSet( "sitemap" ).getLong( "size" ) ).isEqualTo( 2 );
        assertThat( create.getValue().getData().getSet( "sitemap" ).getSets( "files" ) ).hasSize( 2 );
        assertThat( create.getValue().getData().getBinaryReferences( "file" ) ).extracting( BinaryReference::toString )
            .containsExactlyInAnyOrder( "sitemap.xml.gz", "sitemap-index.xml.gz", "sitemap-1.xml.gz", "sitemap-2.xml.gz",
                                        "urlset-1.jsonl.gz", "urlset-2.jsonl.gz" );
        assertThat( create.getValue().getBinaryAttachments().getSize() ).isEqualTo( 6 );
        assertThat( create.getValue().getPermissions() ).isEqualTo(
            AccessControlList.of( AccessControlEntry.create().principal( RoleKeys.ADMIN ).allowAll().build() ) );

        assertThat( stored.id() ).isEqualTo( KEY.id() );
        assertThat( stored.version() ).isEqualTo( versionId.toString() );
        assertThat( stored.key() ).isEqualTo( KEY );
        assertThat( stored.background() ).isTrue();
        assertThat( stored.validUntil() ).isEqualTo( NOW.plusSeconds( 3600 ) );
        assertThat( stored.startedAt() ).isEqualTo( NOW.minusSeconds( 5 ) );
        assertThat( stored.generatedAt() ).isEqualTo( NOW );
        assertThat( new String( stored.sitemap().file( 2 ).orElseThrow().read(), StandardCharsets.UTF_8 ) ).contains(
            "https://example.com/b" );
        assertThat( stored.sitemap().rootETag( false ) ).isEqualTo( local.rootETag( false ) );
    }

    @Test
    void claimOfNewSitemapCreatesNodeOnlyAdministratorsCanAccess()
    {
        when( nodeService.create( any() ) ).thenReturn( node( NodeVersionId.from( "a1b2c3d4-0000-0000-0000-000000000001" ) ) );

        assertThat( store().claimNew( KEY.id(), KEY, false, "me", NOW ) ).isTrue();

        final ArgumentCaptor<CreateNodeParams> create = ArgumentCaptor.forClass( CreateNodeParams.class );
        verify( nodeService ).create( create.capture() );
        assertThat( create.getValue().getPermissions() ).isEqualTo(
            AccessControlList.of( AccessControlEntry.create().principal( RoleKeys.ADMIN ).allowAll().build() ) );
        assertThat( create.getValue().isInheritPermissions() ).isFalse();
    }

    @Test
    void newVersionStaysObsoleteForChangeSinceGenerationStarted()
    {
        final Sitemap local = SitemapTest.write( temp.resolve( "local" ), PARAMS, List.of( new SitemapEntry( "/a", "portal:site", null ) ), 10,
                                                 Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES );
        final NodeVersionId previous = NodeVersionId.from( "a1b2c3d4-0000-0000-0000-000000000001" );
        final NodeVersionId next = NodeVersionId.from( "a1b2c3d4-0000-0000-0000-000000000002" );
        final Node existing = node( previous );
        when( nodeService.getByPath( new NodePath( "/" + KEY.id() ) ) ).thenReturn( existing );
        when( nodeService.update( any() ) ).thenReturn( node( next ) );
        final NodeVersion obsolete =
            version( Attributes.create().attribute( "obsolete", GenericValue.stringValue( NOW.plusSeconds( 1 ).toString() ) ).build() );
        when( nodeService.getVersion( existing.id(), previous ) ).thenReturn( obsolete );

        store().save( KEY.id(), KEY, false, local, null, NOW, NOW.plusSeconds( 5 ) );

        final ArgumentCaptor<ApplyVersionAttributesParams> apply = ArgumentCaptor.forClass( ApplyVersionAttributesParams.class );
        verify( nodeService ).applyVersionAttributes( apply.capture() );
        assertThat( apply.getValue().getNodeVersionId() ).isEqualTo( next );
        assertThat( apply.getValue().getAddAttributes().get( "obsolete" ).asString() ).isEqualTo( NOW.plusSeconds( 1 ).toString() );
    }

    @Test
    void replacesStoredSitemapWithNewVersionWithoutAttributes()
    {
        final Sitemap local = SitemapTest.write( temp.resolve( "local" ), PARAMS, List.of( new SitemapEntry( "/a", "portal:site", null ) ), 10,
                                                 Sitemap.MAX_FILE_BYTES, Sitemap.MAX_FILES );
        final Node existing = node( NodeVersionId.from( "a1b2c3d4-0000-0000-0000-000000000001" ) );
        final Node updated = node( NodeVersionId.from( "a1b2c3d4-0000-0000-0000-000000000002" ) );
        when( nodeService.getByPath( new NodePath( "/" + KEY.id() ) ) ).thenReturn( existing );
        when( nodeService.update( any() ) ).thenReturn( updated );

        store().save( KEY.id(), KEY, false, local, null, NOW, NOW );

        final ArgumentCaptor<UpdateNodeParams> update = ArgumentCaptor.forClass( UpdateNodeParams.class );
        verify( nodeService ).update( update.capture() );
        assertThat( update.getValue().getBinaryAttachments().getSize() ).isEqualTo( 2 );
        assertThat( update.getValue().getVersionAttributesResolver().resolve( existing, existing, Branch.from( "master" ), null ) )
            .isEqualTo( Attributes.create().build() );
    }

    @Test
    void readsStateFromAttributesOfCurrentVersion()
    {
        final NodeVersionId versionId = NodeVersionId.from( "a1b2c3d4-0000-0000-0000-000000000001" );
        final Node node = node( versionId );
        when( nodeService.getByPath( new NodePath( "/" + KEY.id() ) ) ).thenReturn( node );
        final NodeVersion version = version( Attributes.create()
                                                 .attribute( "obsolete", GenericValue.stringValue( NOW.toString() ) )
                                                 .attribute( "accessed", GenericValue.stringValue( NOW.minusSeconds( 60 ).toString() ) )
                                                 .attribute( "generating", GenericValue.newObject()
                                                     .put( "member", "other" )
                                                     .put( "since", NOW.toString() )
                                                     .build() )
                                                 .build() );
        when( nodeService.getVersion( node.id(), versionId ) ).thenReturn( version );

        final SitemapStore.Stored stored = store().load( KEY.id() );

        assertThat( stored.sitemap() ).isNull();
        assertThat( stored.obsolete() ).isEqualTo( NOW );
        assertThat( stored.accessed() ).isEqualTo( NOW.minusSeconds( 60 ) );
        assertThat( stored.generating() ).isEqualTo( new SitemapStore.Generating( "other", NOW ) );

        assertThat( store().claim( stored, "me", NOW.plusSeconds( 60 ), Duration.ofMinutes( 10 ) ) ).isFalse();
        verify( nodeService, never() ).applyVersionAttributes( any() );
        assertThat( store().claim( stored, "me", NOW.plusSeconds( 601 ), Duration.ofMinutes( 10 ) ) ).isTrue();

        store().markObsolete( new SitemapStore.Stored( stored.id(), stored.version(), stored.key(), false, null, null, null, null, null, null,
                                                       null, null ), NOW );
        final ArgumentCaptor<ApplyVersionAttributesParams> apply = ArgumentCaptor.forClass( ApplyVersionAttributesParams.class );
        verify( nodeService, times( 2 ) ).applyVersionAttributes( apply.capture() );
        assertThat( apply.getAllValues().get( 0 ).getAddAttributes().get( "generating" ).property( "member" ).asString() ).isEqualTo( "me" );
        assertThat( apply.getAllValues().get( 1 ).getNodeVersionId() ).isEqualTo( versionId );
        assertThat( apply.getAllValues().get( 1 ).getAddAttributes().get( "obsolete" ).asString() ).isEqualTo( NOW.toString() );
    }

    @Test
    void obsoleteMarkKeepsLatestChange()
    {
        final SitemapStore.Stored marked =
            new SitemapStore.Stored( KEY.id(), "a1b2c3d4-0000-0000-0000-000000000001", KEY, false, null, null, null, null, NOW, null, null,
                                     null );

        store().markObsolete( marked, NOW );
        verify( nodeService, never() ).applyVersionAttributes( any() );

        store().markObsolete( marked, NOW.plusSeconds( 1 ) );
        final ArgumentCaptor<ApplyVersionAttributesParams> apply = ArgumentCaptor.forClass( ApplyVersionAttributesParams.class );
        verify( nodeService ).applyVersionAttributes( apply.capture() );
        assertThat( apply.getValue().getAddAttributes().get( "obsolete" ).asString() ).isEqualTo( NOW.plusSeconds( 1 ).toString() );
    }

    @Test
    void claimOfNewSitemapFailsWhenItsNodeExists()
    {
        when( nodeService.create( any() ) ).thenThrow(
            new NodeAlreadyExistAtPathException( new NodePath( "/" + KEY.id() ), RepositorySitemapStore.REPOSITORY, Branch.from( "master" ) ) );

        assertThat( store().claimNew( KEY.id(), KEY, false, "me", NOW ) ).isFalse();
    }

    private RepositorySitemapStore store()
    {
        return new RepositorySitemapStore( nodeService, repositoryService );
    }

    private static NodeVersion version( final Attributes attributes )
    {
        final NodeVersion version = mock( NodeVersion.class );
        when( version.getAttributes() ).thenReturn( attributes );
        return version;
    }

    private static Node node( final NodeVersionId versionId )
    {
        final PropertyTree data = new PropertyTree();
        SitemapNodeData.writeKey( data.getRoot(), KEY );
        return Node.create()
            .id( NodeId.from( "sitemap" ) )
            .name( KEY.id() )
            .parentPath( NodePath.ROOT )
            .data( data )
            .nodeVersionId( versionId )
            .build();
    }
}

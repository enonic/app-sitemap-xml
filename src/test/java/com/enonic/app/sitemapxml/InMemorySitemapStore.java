package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import com.google.common.io.ByteSource;

/**
 * A {@link SitemapStore} in memory, shared by the caches of a test as the repository is by cluster nodes.
 */
final class InMemorySitemapStore
    implements SitemapStore
{
    private final Map<String, Stored> stored = new HashMap<>();

    final AtomicInteger saves = new AtomicInteger();

    @Override
    public synchronized @Nullable Stored load( final String id )
    {
        return stored.get( id );
    }

    @Override
    public synchronized boolean claimNew( final String id, final SitemapCache.Key key, final boolean background, final String member,
                                          final Instant now )
    {
        if ( stored.containsKey( id ) )
        {
            return false;
        }
        stored.put( id, new Stored( id, UUID.randomUUID().toString(), key, background, null, null, null, null, null,
                                    new Generating( member, now ), null ) );
        return true;
    }

    @Override
    public synchronized boolean claim( final Stored current, final String member, final Instant now, final Duration stale )
    {
        final Stored latest = stored.get( current.id() );
        if ( latest == null )
        {
            return false;
        }
        final Generating generating = latest.generating();
        if ( generating != null && !generating.member().equals( member ) && generating.since().plus( stale ).isAfter( now ) )
        {
            return false;
        }
        stored.put( latest.id(), with( latest, latest.obsolete(), new Generating( member, now ), latest.accessed() ) );
        return true;
    }

    @Override
    public synchronized Stored save( final String id, final SitemapCache.Key key, final boolean background, final Sitemap sitemap,
                                     final @Nullable Instant validUntil, final Instant startedAt, final Instant now )
    {
        final Map<String, byte[]> files = new HashMap<>();
        sitemap.storedFiles().forEach( ( name, content ) -> {
            try
            {
                files.put( name, content.read() );
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }
        } );
        final Stored saved = new Stored( id, UUID.randomUUID().toString(), key, background,
                                         sitemap.withFiles( name -> ByteSource.wrap( files.get( name ) ) ), validUntil, startedAt, now,
                                         null, null, null );
        stored.put( id, saved );
        saves.incrementAndGet();
        return saved;
    }

    @Override
    public synchronized void release( final String id, final String member )
    {
        final Stored latest = stored.get( id );
        if ( latest == null || latest.generating() == null || !latest.generating().member().equals( member ) )
        {
            return;
        }
        if ( latest.sitemap() == null )
        {
            stored.remove( id );
        }
        else
        {
            stored.put( id, with( latest, latest.obsolete(), null, latest.accessed() ) );
        }
    }

    @Override
    public synchronized void markObsolete( final Stored current, final Instant now )
    {
        final Stored latest = stored.get( current.id() );
        if ( latest != null && latest.version().equals( current.version() ) && latest.obsolete() == null )
        {
            stored.put( latest.id(), with( latest, now, latest.generating(), latest.accessed() ) );
        }
    }

    @Override
    public synchronized void touch( final Stored current, final Instant now )
    {
        final Stored latest = stored.get( current.id() );
        if ( latest != null && latest.version().equals( current.version() ) )
        {
            stored.put( latest.id(), with( latest, latest.obsolete(), latest.generating(), now ) );
        }
    }

    @Override
    public synchronized List<Stored> find( final String repositoryId, final @Nullable String branch )
    {
        return stored.values()
            .stream()
            .filter( s -> s.key().repositoryId().equals( repositoryId ) && ( branch == null || s.key().branch().equals( branch ) ) )
            .toList();
    }

    @Override
    public synchronized List<Stored> ofSite( final SitemapCache.Key key )
    {
        return stored.values()
            .stream()
            .filter( s -> s.key().repositoryId().equals( key.repositoryId() ) && s.key().branch().equals( key.branch() ) &&
                s.key().params().sitePath().equals( key.params().sitePath() ) && s.key().principals().equals( key.principals() ) )
            .toList();
    }

    @Override
    public synchronized void delete( final String id )
    {
        stored.remove( id );
    }

    synchronized int size()
    {
        return stored.size();
    }

    private static Stored with( final Stored s, final @Nullable Instant obsolete, final @Nullable Generating generating,
                                final @Nullable Instant accessed )
    {
        return new Stored( s.id(), s.version(), s.key(), s.background(), s.sitemap(), s.validUntil(), s.startedAt(), s.generatedAt(),
                           obsolete, generating, accessed );
    }
}

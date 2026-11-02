package com.enonic.app.sitemapxml;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.script.bean.ScriptBean;
import com.enonic.xp.task.TaskProgressReporterContext;

/**
 * Runs the {@code generate} task: generates and stores the sitemap of the given key on the node XP runs the task on, and reports its
 * progress to the task.
 */
public final class GenerateTaskBean
    implements ScriptBean
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private @Nullable SitemapCache cache;

    @Override
    public void initialize( final BeanContext context )
    {
        this.cache = context.getService( SitemapCache.class ).get();
    }

    public void run( final String key, final boolean regen, final @Nullable String changedAt )
        throws JsonProcessingException
    {
        Objects.requireNonNull( cache, "Bean not initialized" )
            .runTask( SitemapCache.Key.restore( MAPPER.readTree( key ) ), regen,
                      changedAt == null || changedAt.isEmpty() ? null : Instant.parse( changedAt ), TaskProgressReporterContext.current() );
    }
}

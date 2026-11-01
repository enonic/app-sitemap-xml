package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * Reads and writes small JSON files as maps and lists, and takes typed values out of them.
 */
final class Json
{
    private Json()
    {
    }

    static Map<String, Object> readObject( final Path file )
        throws IOException
    {
        try (InputStream in = Files.newInputStream( file ))
        {
            return JSON.std.mapFrom( in );
        }
    }

    static void writeObject( final Map<String, ?> object, final Path file )
        throws IOException
    {
        try (OutputStream out = Files.newOutputStream( file ))
        {
            JSON.std.write( object, out );
        }
    }

    static String string( final Map<String, ?> object, final String name )
    {
        final Object value = object.get( name );
        if ( !( value instanceof String ) )
        {
            throw new IllegalArgumentException( "No string " + name );
        }
        return (String) value;
    }

    static String optionalString( final Map<String, ?> object, final String name )
    {
        return object.get( name ) == null ? null : string( object, name );
    }

    static long number( final Map<String, ?> object, final String name )
    {
        final Object value = object.get( name );
        if ( !( value instanceof Integer || value instanceof Long ) )
        {
            throw new IllegalArgumentException( "No number " + name );
        }
        return ( (Number) value ).longValue();
    }

    static boolean bool( final Map<String, ?> object, final String name )
    {
        return Boolean.TRUE.equals( object.get( name ) );
    }

    static Map<String, ?> object( final Object value )
    {
        if ( !( value instanceof Map ) )
        {
            throw new IllegalArgumentException( "Not an object: " + value );
        }
        @SuppressWarnings("unchecked") final Map<String, ?> object = (Map<String, ?>) value;
        return object;
    }

    static List<?> list( final Map<String, ?> object, final String name )
    {
        final Object value = object.get( name );
        if ( !( value instanceof List ) )
        {
            throw new IllegalArgumentException( "No list " + name );
        }
        return (List<?>) value;
    }

    static List<String> strings( final Map<String, ?> object, final String name )
    {
        final List<String> strings = new ArrayList<>();
        for ( final Object value : list( object, name ) )
        {
            if ( !( value instanceof String ) )
            {
                throw new IllegalArgumentException( "Not a string in " + name + ": " + value );
            }
            strings.add( (String) value );
        }
        return strings;
    }
}

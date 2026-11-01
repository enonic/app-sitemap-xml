package com.enonic.app.sitemapxml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * Reads small JSON files into maps and lists, and takes typed values out of them.
 */
final class Json
{
    private Json()
    {
    }

    static Map<String, Object> readObject( final Path file )
        throws IOException
    {
        try (JsonParser parser = Sitemap.JSON.createParser( file.toFile() ))
        {
            if ( parser.nextToken() != JsonToken.START_OBJECT )
            {
                throw new IOException( "Not a JSON object: " + file );
            }
            return readObject( parser );
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
        if ( !( value instanceof Long ) )
        {
            throw new IllegalArgumentException( "No number " + name );
        }
        return (Long) value;
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

    private static Map<String, Object> readObject( final JsonParser parser )
        throws IOException
    {
        final Map<String, Object> object = new HashMap<>();
        while ( parser.nextToken() == JsonToken.FIELD_NAME )
        {
            final String name = parser.currentName();
            parser.nextToken();
            object.put( name, readValue( parser ) );
        }
        return object;
    }

    private static Object readValue( final JsonParser parser )
        throws IOException
    {
        final JsonToken token = parser.currentToken();
        if ( token == JsonToken.START_OBJECT )
        {
            return readObject( parser );
        }
        if ( token == JsonToken.START_ARRAY )
        {
            final List<Object> list = new ArrayList<>();
            while ( parser.nextToken() != JsonToken.END_ARRAY )
            {
                list.add( readValue( parser ) );
            }
            return list;
        }
        if ( token == JsonToken.VALUE_STRING )
        {
            return parser.getText();
        }
        if ( token == JsonToken.VALUE_NUMBER_INT )
        {
            return parser.getLongValue();
        }
        if ( token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE )
        {
            return parser.getBooleanValue();
        }
        if ( token == JsonToken.VALUE_NULL )
        {
            return null;
        }
        throw new IOException( "Unexpected " + token );
    }
}

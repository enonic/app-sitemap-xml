# Sitemap.xml

This app provides two ways of generating a sitemaps:

1. [Controller mapping](controller-mapping)
2. [Headless Integration](headless-intergration)


## Installation

Check Enonic Market for further details on installing apps in XP. You have multiple options. The easiest way is to just go to the Application admin tool inside Enonic XP and hit the "Install" button, now find this app and click "Install". Done!

## Controller mapping

This app comes with a controller mapping to the path of `sitemap.xml` on the root of the site - `[yoursite.something]/sitemap.xml`.

This means that any request that matches, for example `https://www.example.com/sitemap.xml`, will be sent to this app's sitemap controller.

The sitemap controller will respond with a sitemap.xml file.

It includes the required tags of a sitemap.xml (`<loc>`, `<lastmod>`, `<changefreq>` and `<priority>`).

Be aware that only content from the current site will be added to a site's sitemap.

When the sitemap is split into several files, the controller also responds to `[yoursite.something]/sitemap-1.xml`, `[yoursite.something]/sitemap-2.xml`, etc.

> **_NOTE:_** The order of the controller mapping is set to 12.

## Headless integration

When doing external rendering, via for example Next.XP, you can fetch the data needed to generate a sitemap.xml file via a Guillotine Extension.

Use the `Query playground` in Content Studio to study the Guillotine Schema. You can find a field named `sitemap` on the `HeadlessCms` object type.

The `sitemap` field is served from the same cached, published-only sitemap as `sitemap.xml`. `urlset(count: N)` returns the first `N` URLs, at most "Max included items", as paths relative to the site, so the client prefixes them with its own base URL. `changefreq` and `priority` are left out when they have the default values (`monthly` and `0.5`).

```graphql
query {
  guillotine {
    sitemap {
      baseUrl
      urlset(count: 50000) {
        path
        lastmod
        changefreq
        priority
      }
    }
  }
}
```

```json
{
  "data": {
    "guillotine": {
      "sitemap": {
        "baseUrl": "https://www.example.com",
        "urlset": [
          {"path": "/", "lastmod": "2024-06-01T10:15:00Z", "changefreq": "hourly", "priority": "1.0"},
          {"path": "/news/hello-world", "lastmod": "2024-05-30T12:00:00Z"}
        ]
      }
    }
  }
}
```

When the sitemap is not generated yet and too many requests are already waiting for sitemaps, the query fails with an error; retry it a few seconds later.

> **_TIP:_** In the Next.XP app there is a controller mapping with a order of 11, so that it can provide it's own sitemap.xml

## Site configuration

The app has a site configuration where you can add content types and configure their `change frequency` and `priority`.

The app will automatically add the site content, if not already added in the site configuration [mappings](#mappings).

### Max included items

Limit the number of items in the file. Default: 10000. Max: 50000.

> **_WARNING:_** The number of included items will affect the time it takes to generate the sitemap.xml file.

### Override the generated domain name

By default, the app will use the domain name of the site. If you want to override this, you can specify a custom domain name in this field.

### Size of the sitemap

The limits of the [sitemap protocol](https://www.sitemaps.org/protocol.html) are always obeyed: a sitemap file holds at most 50,000 URLs and 50MB (uncompressed), a sitemap index lists at most 50,000 sitemaps, and URLs longer than 2,048 characters are left out.

When the items do not fit in one file of 50MB, `sitemap.xml` becomes a [sitemap index](https://www.sitemaps.org/protocol.html#index) that points to `sitemap-1.xml`, `sitemap-2.xml`, etc. Those files are served by the same controller mapping.

URLs are sorted by path, which compresses better. When the site has more items than "Max included items", the most recently modified items are included, sorted newest first.

### Gzip

Every sitemap file is also served gzip-compressed, as allowed by the [sitemap protocol](https://www.sitemaps.org/protocol.html#index): `sitemap.xml.gz`, `sitemap-1.xml.gz`, `sitemap-2.xml.gz`, etc. A sitemap index requested as `sitemap.xml.gz` points to the `.xml.gz` files. The size limit of 50MB applies to the uncompressed file.

Crawlers usually only know `sitemap.xml` (from `robots.txt` or a Search Console submission), so the plain `.xml` URLs are compressed too: when a request accepts gzip (`Accept-Encoding: gzip`, which crawlers send), the stored file is sent as it is with `Content-Encoding: gzip`, so the response is the same XML, compressed for transfer only. Plain `.xml` responses carry `Vary: Accept-Encoding`. To have crawlers fetch the `.xml.gz` files instead, list `sitemap.xml.gz` in `robots.txt` (`Sitemap: https://example.com/sitemap.xml.gz`).

## Caching and background generation

The sitemap lists published content only: it is always generated from the `master` branch, also when requested in preview (draft). It is generated in Java, through the content API, and written to disk while the content is read, so it is never held in memory. Once generated, requests are answered from the stored files.

A generated sitemap is served until a newer one of the same site has been generated and replaces it, also after content changed and after XP or the app is restarted. How it is generated again when content in the project changes (any node event in the project repository, e.g. publish, unpublish, move or delete) depends on whether its URLs are known without a request:

* With a `Base URL` override in the site configuration, and for Guillotine, which returns paths: the sitemap is generated again in the background a few seconds after the change. A generation still running when content changes again is halted and started over. It is also generated again when scheduled content goes online or offline (`Online from` / `Online to`). Sitemaps not requested for an hour are generated again on their next request instead.
* Without a `Base URL` override, the URLs depend on the host of the request: the sitemap is generated again on its next request.

A request for a sitemap that is being generated again gets the previous one right away. Only the first request for a sitemap waits for it to be generated.

Sitemaps are generated one at a time, in the order they are needed. Any number of generations can be queued. Requests for generated sitemaps are always answered at once; when more than 10 requests are already waiting for sitemaps that are not generated yet, further such requests get `429 Too Many Requests` with a `Retry-After` header. Their sitemap is generated anyway, so it is ready when the request is retried.

Generated sitemap files are kept in `$XP_HOME/work/com.enonic.app.sitemapxml` (one directory per site and host, with the files of its current sitemap). They are stored gzip-compressed only: `.xml.gz` requests stream the stored files as they are, so are plain `.xml` requests that accept gzip; other plain `.xml` requests are decompressed on the fly. Files of a replaced sitemap are deleted a minute later. Sitemaps kept from before a restart are served right away and generated again on their first request, since content may have changed in the meantime. The 100 most recently used sitemaps are kept; older ones are deleted. The cache is per cluster node.

### Mappings

Here you can specify which content types you want to include in the file and their `change frequency` and `priority` per content type.

#### Priority

The `<priority>` tag has the default value of 0.5.

The site content itself will be added automatically with a `priority` set to `1.0` and `changeFrequency` set to `hourly`. If you want to overwrite these defaults, please add `portal:site` as a mapping manually and customize the settings.

### Ignore list

Here you can add exclusion patterns for specific content paths.
For example, to exclude all paths ending with `/foo`, you can add the following pattern: `*/foo`

To exclude a specific content use: `/full/path`

To exclude everything below a specific folder (all its descendants, at any depth), but not the folder itself, use: `/some/folder/*`

To exclude a specific folder AND everything below it use two patterns:
* `/some/folder`
* `/some/folder/*`

> **CAUTION:** Using a single pattern "/some/folder*", could be problematic as it also matches "/some/folderOrContentWithLongerName*"

To exclude all content that has a string anywhere in the path use: `*hasInTheMiddle*`

To exlude all content that ends with a string use: `*endsWith`

## Specification

Check out [Sitemaps XML format](https://www.sitemaps.org/protocol.html) to learn more about how it all works.

## Upgrade Notes

### 2.0.x to 2.0.3

#### JSON sitemap removed

A request for `sitemap.xml` with the header `Content-Type: application/json` no longer returns the sitemap as JSON; it returns the XML sitemap. For JSON, use the [Guillotine Extension](#headless-integration).

### 1.x.x to 2.0.0

#### Sitemap service replaced by Guillotine Extension

In version 2.0.0 the sitemap service has been removed in favour of a Guillotine Extension.

If you are using the controller mapping, this doesn't affect you.

If however you are doing external rendering, see [Headless integration](#headless-integration).

## Changelog

See the [Market page](https://market.enonic.com/vendors/enonic/com.enonic.app.sitemapxml)

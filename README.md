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

The `sitemap` field is served from the same cached, published-only sitemap as `sitemap.xml`, split into the same files (at most 50 000 URLs and 50MB each). URLs are returned as paths relative to the site, so the client prefixes them with its own base URL. `lastmod` is the time the content was last modified; `changefreq` and `priority` are left out when they have the default values (`monthly` and `0.5`).

The app can also be added to a project instead of a site. When the query is not scoped to a site (no `scopeKey`, or one outside any site), the project's configuration of the app is used, and the sitemap lists all published content of the project, with paths relative to the project root. `sitemap.xml` itself is only served for sites, as projects are not rendered by XP.

* `files { number lastmod }` lists the files of the sitemap with the time each file last changed. A file keeps its `lastmod` while its content stays the same, so crawlers can skip it. Use it to render a sitemap index, or to know how many files to fetch.
* `file(number: N) { urlset { ... } }` returns the URLs of file `N`, or `null` when there is no such file. Fetch the files one by one, so a huge sitemap never has to be loaded in one response.
* `urlset(count: N)` returns the first `N` URLs of the sitemap, or all of them without `count`. Fine for small sites.

Example: first fetch the list of files.

```graphql
query {
  guillotine {
    sitemap {
      baseUrl
      files {
        number
        lastmod
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
        "files": [
          {"number": 1, "lastmod": "2024-06-01T10:15:00Z"},
          {"number": 2, "lastmod": "2023-11-20T08:00:00Z"}
        ]
      }
    }
  }
}
```

Then fetch the URLs of each file, rendering one sitemap file per response (`<baseUrl><path>` gives the `<loc>`), and a sitemap index that lists all of them.

```graphql
query ($number: Int!) {
  guillotine {
    sitemap {
      file(number: $number) {
        number
        lastmod
        urlset {
          path
          lastmod
          changefreq
          priority
        }
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
        "file": {
          "number": 1,
          "lastmod": "2024-06-01T10:15:00Z",
          "urlset": [
            {"path": "/", "lastmod": "2024-06-01T10:15:00Z", "changefreq": "hourly", "priority": "1.0"},
            {"path": "/news/hello-world", "lastmod": "2024-05-30T12:00:00Z"}
          ]
        }
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

### Override the generated domain name

By default, the URLs in `sitemap.xml` use the scheme, host and port the site is requested through. To use fixed ones instead, enter them in the `Base URL` field (in the `Advanced` section of the site configuration), for instance `https://www.example.com`:

* The value is put in front of the path of each URL as XP renders it for the request, with the virtual host mapping applied. A site mapped to `/en` gets `https://www.example.com/en/...`.
* Include the scheme (`https://`) and leave out the trailing slash. The value may end with a path, which then comes before the site's path.
* The sitemap is then the same whichever host it is requested from, and it is generated again in the background when content changes.
* Guillotine returns the value as `baseUrl` (or the site's `portal` Base URL when the value is empty); the URLs it returns are paths relative to the site.

### Size of the sitemap

All published content of the site that matches the mappings is included, there is no limit on the number of items. The limits of the [sitemap protocol](https://www.sitemaps.org/protocol.html) are always obeyed: a sitemap file holds at most 50,000 URLs and 50MB (uncompressed), a sitemap index lists at most 50,000 sitemaps, and URLs longer than 2,048 characters are left out.

When the site has more items than fit in one file, `sitemap.xml` becomes a [sitemap index](https://www.sitemaps.org/protocol.html#index) that points to `sitemap-1.xml`, `sitemap-2.xml`, etc. Those files are served by the same controller mapping. The `<lastmod>` of each file in the index is the time that file last changed: a file whose content is the same as in the previous sitemap keeps its time.

URLs in a sitemap that fits in one file are sorted by path, which compresses better. When the sitemap is split into several files, URLs are sorted by the time the content was created, oldest first: new content is added to the last file and edited content stays in its file, so the other files keep their content and their `lastmod` in the index, and crawlers can skip them.

### Gzip

Every sitemap file is also served gzip-compressed, as allowed by the [sitemap protocol](https://www.sitemaps.org/protocol.html#index): `sitemap.xml.gz`, `sitemap-1.xml.gz`, `sitemap-2.xml.gz`, etc. A sitemap index requested as `sitemap.xml.gz` points to the `.xml.gz` files. The size limit of 50MB applies to the uncompressed file.

Crawlers usually only know `sitemap.xml` (from `robots.txt` or a Search Console submission), so the plain `.xml` URLs are compressed too: when a request accepts gzip (`Accept-Encoding: gzip`, which crawlers send), the stored file is sent as it is with `Content-Encoding: gzip`, so the response is the same XML, compressed for transfer only. Plain `.xml` responses carry `Vary: Accept-Encoding`. To have crawlers fetch the `.xml.gz` files instead, list `sitemap.xml.gz` in `robots.txt` (`Sitemap: https://example.com/sitemap.xml.gz`).

## Caching and background generation

The sitemap lists published content only: it is generated from the `master` branch. It is generated in Java, through the content API, and written to disk while the content is read, so it is never held in memory. Once generated, requests are answered from the stored files.

* The sitemap lists the content the requesting user can read, and is cached per user; anonymous requests (crawlers) share one. When the site itself is readable by everyone, logged-in users get that public sitemap too.
* In preview (`draft` branch), the sitemap lists the site URL only, and is not kept.
* A site URL taken from the request must be `http` or `https` (lower case) without user info; other requests get `400 Bad Request`. Query and fragment are left out.

A generated sitemap is served until a newer one of the same site has been generated and replaces it, also after content changed and after XP or the app is restarted. How it is generated again when content in the project changes (any node event in the project repository, e.g. publish, unpublish, move or delete) depends on whether its URLs are known without a request:

* With a `Base URL` (in this app's site configuration or the one configured for the site in XP), and for Guillotine, which returns paths: the sitemap is generated again in the background a few seconds after the change. A generation still running when content changes again is halted and started over. It is also generated again when scheduled content goes online or offline (`Online from` / `Online to`). Sitemaps not requested for an hour are generated again on their next request instead.
* Without a `Base URL`, the URLs depend on the host of the request: the sitemap is generated again on its next request.

A request for a sitemap that is being generated again gets the previous one right away. Only the first request for a sitemap waits for it to be generated.

Sitemaps of a project are generated one at a time, in the order they are needed; different projects generate in parallel. Any number of generations can be queued. Requests for generated sitemaps are always answered at once; when more than 10 requests are already waiting for sitemaps that are not generated yet, further such requests get `429 Too Many Requests` with a `Retry-After` header. Their sitemap is generated anyway, so it is ready when the request is retried.

Generated sitemap files are kept in `$XP_HOME/work/cache/com.enonic.app.sitemapxml`, next to XP's own image cache (one directory per generated sitemap, with an `entry.json` describing it, written once the sitemap is complete). They are stored gzip-compressed only: `.xml.gz` requests stream the stored files as they are, so are plain `.xml` requests that accept gzip; other plain `.xml` requests are decompressed on the fly. Files of a replaced sitemap are deleted a minute later. Sitemaps kept from before a restart are served right away and generated again on their first request, since content may have changed in the meantime. Without a Base URL, each host, port and scheme the site is requested through gets its own sitemap; the host is taken in lower case. Of the sitemaps of a site for the same user, 10 are kept: when there are more, those whose URL names a port are deleted first, then the least recently used. A Base URL makes the sitemap the same for every host. The cache is per cluster node.

### URLs in the sitemap

URLs are resolved in this order:

1. `Base URL` in this app's site configuration, see [Override the generated domain name](#override-the-generated-domain-name). This setting predates the one in XP and takes precedence when set.
2. The `Base URL` configured for the site in XP (the `portal` site configuration). This is the preferred way: sitemaps are generated in the background, without a request, much like a headless client renders URLs.
3. The URL of the site as seen by the request.

With either Base URL, the sitemap is the same whichever host it is requested from.

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

### 3.0.x to 3.1.0

#### JSON sitemap removed

A request for `sitemap.xml` with the header `Content-Type: application/json` no longer returns the sitemap as JSON; it returns the XML sitemap. For JSON, use the [Guillotine Extension](#headless-integration), which returns the same entries split into the same files.

### 1.x.x to 2.0.0

#### Sitemap service replaced by Guillotine Extension

In version 2.0.0 the sitemap service has been removed in favour of a Guillotine Extension.

If you are using the controller mapping, this doesn't affect you.

If however you are doing external rendering, see [Headless integration](#headless-integration).

## Changelog

See the [Market page](https://market.enonic.com/vendors/enonic/com.enonic.app.sitemapxml)

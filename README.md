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

You may also study how we did it in our own [site-enonic-next](https://github.com/enonic/site-enonic-next).

> **_TIP:_** In the Next.XP app there is a controller mapping with a order of 11, so that it can provide it's own sitemap.xml

## Site configuration

The app has a site configuration where you can add content types and configure their `change frequency` and `priority`.

The app will automatically add the site content, if not already added in the site configuration [mappings](#mappings).

### Max included items

Limit the total number of items in the sitemap. Default: 10000. Max: 1000000.

### URLs per file

Maximum number of URLs in a single sitemap file. Default and max: 50000 (the limit of the [sitemap protocol](https://www.sitemaps.org/protocol.html)).

When the site has more items than this, `sitemap.xml` becomes a [sitemap index](https://www.sitemaps.org/protocol.html#index) that points to `sitemap-1.xml`, `sitemap-2.xml`, etc. Those files are served by the same controller mapping.

## Caching and background generation

The sitemap is generated in Java and kept in memory. The first request for a sitemap waits for it to be generated; after that, requests are answered from memory.

When content in the project changes (any node event in the project repository and branch, e.g. publish, unpublish, move or delete), the sitemap is regenerated in the background a few seconds later. Until the new sitemap is ready, the previous one is served. On the `master` branch, the sitemap is also regenerated when scheduled content goes online or offline (`Online from` / `Online to`). Sitemaps not requested for an hour are dropped instead of regenerated.

Sitemaps are generated one at a time, never in parallel. When more than 10 sitemap requests are in progress at once (for example many cold sitemaps requested at the same time), further requests get `503 Service Unavailable` with a `Retry-After` header.

The cache is per cluster node and is emptied when the app is restarted.

### URLs in the sitemap

URLs are resolved in this order:

1. `Base URL` in this app's site configuration, see [Override the generated domain name](#override-the-generated-domain-name).
2. The `Base URL` configured for the site in XP (the `portal` site configuration). The sitemap is then the same whichever host it is requested from.
3. The URL of the site as seen by the request.

### Mappings

Here you can specify which content types you want to include in the file and their `change frequency` and `priority` per content type.

#### Priority

The `<priority>` tag has the default value of 0.5.

The site content itself will be added automatically with a `priority` set to `1.0` and `changeFrequency` set to `hourly`. If you want to overwrite these defaults, please add `portal:site` as a mapping manually and customize the settings.

### Ignore list

Here you can add exclusion patterns for specific content paths.
For example, to exclude all paths ending with `/foo`, you can add the following pattern: `*/foo`

To exclude a specific content use: `/full/path`

To exclude just the children of a specific folder use: `/some/folder/*`

To exclude a specific folder AND it's children use two patterns:
* `/some/folder`
* `/some/folder/*`

> **CAUTION:** Using a single pattern "/some/folder*", could be problematic as it also matches "/some/folderOrContentWithLongerName*"

To exclude all content that has a string anywhere in the path use: `*hasInTheMiddle*`

To exlude all content that ends with a string use: `*endsWith`

## Specification

Check out [Sitemaps XML format](https://www.sitemaps.org/protocol.html) to learn more about how it all works.

## Upgrade Notes

### 1.x.x to 2.0.0

#### Sitemap service replaced by Guillotine Extension

In version 2.0.0 the sitemap service has been removed in favour of a Guillotine Extension.

If you are using the controller mapping, this doesn't affect you.

If however you are doing external rendering, see [Headless integration](#headless-integration).

## Changelog

See the [Market page](https://market.enonic.com/vendors/enonic/com.enonic.app.sitemapxml)

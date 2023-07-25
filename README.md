# Sitemap.xml

This app creates the sitemap.xml file for your site. It includes the required tags of a sitemap.xml (`<loc>`, `<lastmod>`, `<changefreq>` and `<priority>`). The app's features are very basic, so sending pull requests are encouraged.

The app has a setting to add any number of content types and their `change frequency` and `priority` to the sitemap.xml file. It also adds a controller mapping to the path of `sitemap.xml` on the root of the site - `[yoursite.something]/sitemap.xml`.

Be aware that only content from the current site will be added to a site's sitemap.

## Defaults

The `<priority>` tag has the default value of 0.5.

The site content itself will be added automatically with a `priority` set to `1.0` and `changeFrequency` set to `hourly`. If you want to overwrite these defaults, please add `portal:site` as a mapping manually and customize the settings.

## Specification

Check out [Sitemaps XML format](https://www.sitemaps.org/protocol.html) to learn more about how it all works.

## Installation

Check Enonic Market for further details on installing apps in XP. You have multiple options. The easiest way is to just go to the Application admin tool inside Enonic XP and hit the "Install" button, now find this app and click "Install". Done!

## Releases and Compatibility
| Version | XP version |
| ------------- | ------------- |
| 1.4.0 | 7.1.0 |
| 1.3.0 | 7.1.0 |
| 1.2.0 | 7.0.0 |
| 1.1.1 | 6.10.2 |
| 1.1.0 | 6.10.2 |
| 1.0.1 | 6.8.0 |
| 1.0.0 | 6.8.0 |

## Changelog

**Version 1.3 and newer see releases**

### Version 1.1.0

Cudo's to [@nerdegutt](https://github.com/nerdegutt) for suggestions and pull requests that are part of this release.

**Code improvements**:
* Refactoring and improvements of code.
* Move most code logic out of view file and into controller.
* Send much less (a 20th) of the data from controller to view (XSL).
* Required XP version: **6.10**.

**New functionality**:
* Automatically add the site content to sitemap.xml output, if not added already in app settings.
* Let users control `priority` field from app settings per content type.
* Only fetch content from the current site, even if mapped content types exists on other sites.
* If present, the robot settings from [SEO Meta Fields](https://market.enonic.com/vendors/enonic/com.enonic.app.metafields) app will be respected and used to filter the sitemap items.

### Version 1.0.1

* **Bug fixed:** Generate absolute URLs for `<loc>`-field.
* **Bug fixed:** Add missing `<priority>`-field (mentioned in the docs but not added in output).
* Add two more options to the field `<changefreq>` - "always" and "never", according to specification.
* Upgrade wrappers and build files to Gradle 3.

### Version 1.0.0

* First release

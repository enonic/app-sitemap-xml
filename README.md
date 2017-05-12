# Sitemap.xml

This app creates the sitemap.xml file for your site. It includes the main tags of sitemap (`<loc>`, `<lastmod>` and `<changefreq>`). `<priority>` tag has the default value of 0.5. The app's features are very basic, so forking (or sending pull requests with improvements) are encouraged.

The app has a setting to add content types and their change frequency to sitemap.xml file. It also adds a controller mapping to the path of sitemap.xml on the root of the site - `[yoursite.something]/sitemap.xml`

Check out [Sitemaps XML format](https://www.sitemaps.org/protocol.html) to learn more about how it works.

## Installation

Check Enonic Market for further details on installing apps in XP. You have multiple options. The easiest way is to just go to the Application admin tool inside Enonic XP and hit the "Install" button, now find this app and click "Install". Done!

## Releases and Compatibility
| Version | XP version |
| ------------- | ------------- |
| 1.0.1 | 6.8.0 |
| 1.0.0 | 6.8.0 |

## Changelog

### Version 1.0.1

* **Bug fixed:** Generate absolute URLs for `<loc>`-field.  
* **Bug fixed:** Add missing `<priority>`-field (mention in the docs but not really added).  
* Add two more options to the field `<changefreq>` - "always" and "never", according to specification.  
* Upgrade wrappers and build files to Gradle 3.

### Version 1.0.0

* First release

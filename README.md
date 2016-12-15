# app-sitemap-xml

This app creates the sitemap.xml file for your site. It has setting for the main tags of sitemap (`\`<loc>  , <lastmod> and <priority>). Priority tag has the default value of 0.5. App's features can be extended based on your request. 
It aslo adds a controller mapping to the path of sitemap.xml on the root of site.
Check out [Sitemaps XML format] (https://www.sitemaps.org/protocol.html) to find more about it.

## Installation

Check Enonic Market for further details on installing apps in XP. You have multiple options. The easiest way is to just go to the Application admin tool inside Enonic XP and hit the "Install" button, now find this app and click "Install". Done!

To build the source manually, just download this repo (or use the [XP Toolbox "init-project"](http://xp.readthedocs.io/en/6.5/developer/projects/init.html)), and use the command ["gradlew deploy"](http://xp.readthedocs.io/en/6.5/developer/projects/build.html) in the terminal.

## Releases and Compatibility

| Version | XP version |
| ------------- | ------------- |
| 1.0.0 | 6.8.0 |

## Changelog

### Version 1.0.0

* First release

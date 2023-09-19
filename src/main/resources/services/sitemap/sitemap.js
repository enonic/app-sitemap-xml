 const portalLib = require('/lib/xp/portal');
 const xsltLib = require('/lib/xslt');
 const gridLib = require('/lib/xp/grid');

 const sitemapLib = require('/lib/sitemap');

const handleGet = (req) => {
  const start = Date.now();
  const site = portalLib.getSite();
  const sharedMap = gridLib.getMap(`sitemap-${site._id}`);
  let sitemapJson = sharedMap.get('sitemap');

  if (!sitemapJson) {
    // Did not find sitemap in shared map, generate it. This should hopefully never happen, might give a timeout
    sitemapLib.debugLog(`Sitemap - Did not find sitemap in shared map, generating it`);
    sitemapJson = sitemapLib.runForSite(site);
  } else {
    sitemapJson = JSON.parse(sitemapJson);
  }

  /**
   * Generating URL'S for each item with portal.pageUrl function is too much of a timesink,
   * so we generate once for site then append path of each item to that.
   * This works for most sites, but maybe not those with nested sites and custom vhosts.
   * pageUrl does not work in task/event context and they are too costly to use here.
   */
  const siteUrl = portalLib.pageUrl({ path: site._path, type: 'absolute' });
  const model = {
    result: Object.keys(sitemapJson).map((key) => {
      sitemapJson[key].url = siteUrl + encodeURI(sitemapJson[key].path);
      return sitemapJson[key]
    }),
  };

  if (req.headers['Content-Type'] === 'application/json') {
    return {
      contentType: 'application/json',
      body: model.result,
    }
  } else {
    /**
     * Pure string concat is about 20-30 times faster than xslt rendering.
     * Even faster from cold start.
     * Sitemap XML generated in 225ms.
     * Sitemap string generated in 2ms.
     */

    let body;
    if (app.config.xsltRender === 'true') {
      body = renderXslt(model);
    } else {
      body = renderString(model);
    }

    sitemapLib.debugLog(`Sitemap - Complete in ${Date.now() - start}ms`);
    return {
      contentType: 'text/xml',
      body,
    };
  }
}
exports.get = handleGet;

const renderXslt = (model) => {
  const startXslt = Date.now();
  const body = xsltLib.render(resolve('sitemap.xsl'), model)
  sitemapLib.debugLog(`Sitemap - XML generated in ${Date.now() - startXslt}ms for ${model.result.length} items`);
  return body;
};

const renderString = (model) => {
  const startString = Date.now();
  let body = `<?xml version="1.0" encoding="UTF-8"?>
    <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">`
    
  model.result.forEach((item) => {
    body += `<url>
      <loc>${item.url}</loc>
      <lastmod>${item.modifiedTime.split('T')[0]}</lastmod>
      <changefreq>${item.changeFreq}</changefreq>
      <priority>${item.priority}</priority>
    </url>`
  });

  body += '</urlset>'
  
  sitemapLib.debugLog(`Sitemap - string generated in ${Date.now() - startString}ms for ${model.result.length} items`);
  return body;
};
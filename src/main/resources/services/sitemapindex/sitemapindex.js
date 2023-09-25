const portalLib = require('/lib/xp/portal');
const contentLib = require('/lib/xp/content');
const utilLib = require('/lib/util');

const handleGet = () => {
  const siteConfig = portalLib.getSiteConfig();
  const sitemapXmlAppConfig = siteConfig.sitemapXmlFileIds || [];

  const sitemapIndexXmlList = contentLib.query({
    start: 0,
    count: sitemapXmlAppConfig.length + 1,
    filters: {
      ids: {
        values: utilLib.data.forceArray(sitemapXmlAppConfig).map((id) => id),
      },
    },
  }).hits.map((content) => {
    return {
      url: portalLib.attachmentUrl({
        id: content._id,
        type: 'absolute',
      }),
      lastModified: content.modifiedTime.split('T')[0],
    }
  });

  const body = `<?xml version="1.0" encoding="UTF-8"?>
    <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
        ${sitemapIndexXmlList
      .map((sitemapXml) => {
        return `<sitemap>
                  <loc>${sitemapXml.url}</loc>
                  <lastmod>${sitemapXml.lastModified}</lastmod>
                </sitemap>`
      })
      .join('')}
    </sitemapindex>`;

  return {
    contentType: 'text/xml',
    body,
  };
}
exports.get = handleGet
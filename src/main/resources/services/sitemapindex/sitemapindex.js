var libs = {
    portal: require('/lib/xp/portal'),
    content: require('/lib/xp/content'),
    util: require('/lib/util'),
};

function get() {
    var sitemapXmlAppConfig = libs.portal.getSiteConfig().sitemapXmlFileIds ? libs.portal.getSiteConfig().sitemapXmlFileIds : [];

    var sitemapIndexXmlList = libs.content.query({
        start: 0,
        count: sitemapXmlAppConfig.length + 1,
        filters: {
            ids: {
                values: libs.util.data.forceArray(sitemapXmlAppConfig).map((id) => id),
            },
        },
    }).hits.map((content) => {
        return {
            url: libs.portal.attachmentUrl({
                id: content._id,
                type: 'absolute',
            }),
            lastModified: content.modifiedTime.split('T')[0],
        }
    });

    var body = `<?xml version="1.0" encoding="UTF-8"?>
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
exports.get = get;
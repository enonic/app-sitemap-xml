var libs = {
    portal: require('/lib/xp/portal'),
    content: require('/lib/xp/content'),
    util : require('/lib/enonic/util'),
    xslt: require('/lib/xp/xslt')
};

var view = resolve('sitemap.xsl');

function handleGet(req) {

    var site = libs.portal.getSite();
    var siteConfig = libs.portal.getSiteConfig();

    var arrContentTypes = [];
    var siteMapSettings = siteConfig.siteMap ? libs.util.data.forceArray(siteConfig.siteMap) : null;
    var changefreq = {}, priority = {};
	 var siteAdded = false;
    if (siteMapSettings) {
        for (var j = 0; j < siteMapSettings.length; j++) {
            var cty = siteMapSettings[j].contentType || "";
				if (cty === 'portal:site') { siteAdded = true; } // To be able to automatically add site content type if not already added by user.
				arrContentTypes.push(cty);
            changefreq[cty] = siteMapSettings[j].updatePeriod || "monthly";
            priority[cty] = siteMapSettings[j].priority || "0.5";
        }
    }
	 // Default settings for site (frontpage) set to be changed and prioritized often.
	 if (!siteAdded) {
		 cty = 'portal:site';
		 arrContentTypes.push(cty);
		 changefreq[cty] = "Hourly";
		 priority[cty] = "1.0";
	 }

	 // Only allow content from current Site to populate the RSS feed.
	 var folderPath = site._path;
	 var contentRoot = '/content' + folderPath + '/';
	 var query = '_path LIKE "' + contentRoot + '*"';

    var queryRes = libs.content.query({
        query: query,
        sort : 'modifiedTime DESC',
        contentTypes: arrContentTypes,
        count: 1000,
    });

    for(var i = 0 ; i < queryRes.hits.length; i++ ) {
        if(queryRes.hits[i].type){
            queryRes.hits[i].changeFreq = changefreq[queryRes.hits[i].type];
            queryRes.hits[i].priority = priority[queryRes.hits[i].type];
        } else {
            queryRes.hits = null;
        }
    }

    var model = {
        result: queryRes.hits
    };

    return {
        contentType: 'text/xml',
        body: libs.xslt.render(view, model)
    };
}
exports.get = handleGet;

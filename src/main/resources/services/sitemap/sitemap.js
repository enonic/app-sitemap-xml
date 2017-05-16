var libs = {
    portal: require('/lib/xp/portal'),
    content: require('/lib/xp/content'),
    util: require('/lib/enonic/util'),
    xslt: require('/lib/xp/xslt')
};

var globals = {
	view: resolve('sitemap.xsl'),
	updatePeriod: "monthly",
	priority: "0.5",
	alwaysAdd: "portal:site"
};

function handleGet(req) {

    var site = libs.portal.getSite();
    var siteConfig = libs.portal.getSiteConfig();

    var arrContentTypes = [];
	 var changefreq = {}, priority = {};
	 var siteAdded = false;
    var siteMapSettings = siteConfig.siteMap ? libs.util.data.forceArray(siteConfig.siteMap) : null;
    if (siteMapSettings) {
        for (var j = 0; j < siteMapSettings.length; j++) {
            var cty = siteMapSettings[j].contentType || "";
				if (cty === globals.alwaysAdd) { siteAdded = true; log.info("site added by user"); } // To be able to automatically add site content type if not already added by user.
				arrContentTypes.push(cty);
            changefreq[cty] = siteMapSettings[j].updatePeriod || globals.updatePeriod;
            priority[cty] = siteMapSettings[j].priority || globals.priority;
        }
    }

	 // Default settings for site (frontpage) set to be changed and prioritized often.
	 if (!siteAdded) {
		 cty = globals.alwaysAdd;
		 arrContentTypes.push(cty);
		 changefreq[cty] = "Hourly";
		 priority[cty] = "1.0";
		 log.info("site added from code");
	 }

	 // Only allow content from current Site to populate the sitemap.
	 var folderPath = site._path;
	 var contentRoot = '/content' + folderPath + '/';
	 var query = '_path LIKE "' + contentRoot + '*" OR _path = "' + contentRoot + '"';

    var result = libs.content.query({
        query: query,
        sort : 'modifiedTime DESC',
        contentTypes: arrContentTypes,
        count: 1000,
    });

	 libs.util.log(result);

	 // Go through the results and add the corresponding settings for each match.
    for(var i = 0 ; i < result.hits.length; i++ ) {
        if(result.hits[i].type){
            result.hits[i].changeFreq = changefreq[result.hits[i].type];
            result.hits[i].priority = priority[result.hits[i].type];
        } else {
            result.hits = null;
        }
    }

    var model = {
        result: result.hits
    };

    return {
        contentType: 'text/xml',
        body: libs.xslt.render(globals.view, model)
    };
}
exports.get = handleGet;

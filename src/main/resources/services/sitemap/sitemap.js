const libs = {
	portal: require('/lib/xp/portal'),
	content: require('/lib/xp/content'),
	util: require('/lib/util'),
	xslt: require('/lib/xslt')
};

var globals = {
	view: resolve('sitemap.xsl'),
	updatePeriod: "monthly",
	priority: "0.5",
	alwaysAdd: "portal:site"
};

function handleGet() {

	var site = libs.portal.getSite();
	var siteConfig = libs.portal.getSiteConfig();

	var arrContentTypes = [];
	var changefreq = {}, priority = {};
	var siteAdded = false;
	var siteMapSettings = siteConfig.siteMap ? libs.util.data.forceArray(siteConfig.siteMap) : null;
	if (siteMapSettings) {
		for (var j = 0; j < siteMapSettings.length; j++) {
			let cty = siteMapSettings[j].contentType || "";
			if (cty === globals.alwaysAdd) { siteAdded = true; } // To be able to automatically add site content type if not already added by user.
			arrContentTypes.push(cty);
			changefreq[cty] = siteMapSettings[j].updatePeriod || globals.updatePeriod;
			priority[cty] = siteMapSettings[j].priority || globals.priority;
		}
	}

	// Default settings for site (frontpage) set to be changed and prioritized often.
	if (!siteAdded) {
		arrContentTypes.push(globals.alwaysAdd);
		changefreq.cty = "Hourly";
		priority.cty = "1.0";
	}

	// Only allow content from current Site to populate the sitemap.
	var contentRoot = '/content' + site._path + '';
	var query = '_path LIKE "' + contentRoot + '/*" OR _path = "' + contentRoot + '"';

	// Query that respects the settings from SEO Metafield plugin, if present, using 6.10 query filters - @nerdegutt.
	var result = libs.content.query({
		query: query,
		sort: 'modifiedTime DESC',
		contentTypes: arrContentTypes,
		count: 1000,
		filters: {
			boolean: {
				mustNot: {
					hasValue: {
						field: "x.com-enonic-app-metafields.meta-data.blockRobots",
						values: "true"
					}
				}
			}
		}
	});

	// Go through the results and add the corresponding settings for each match.
	var items = new Array(result.hits.length);
	for (var i = 0; i < result.hits.length; i++) {
		let hit = result.hits[i];
		//When does content not have a type?
		if (result.hits[i].type) {
			let url = libs.portal.pageUrl({
				path: hit._path,
				type: 'absolute'
			});
			items[i] = {
				changeFreq: changefreq[hit.type] || globals.updatePeriod,
				priority: priority[hit.type] || globals.priority,
				url,
				modifiedTime: hit.modifiedTime,
			};
		} else {
			result.hits = null;
		}
	}

	var model = {
		result: items
	};

	return {
		contentType: 'text/xml',
		body: libs.xslt.render(globals.view, model)
	};
}
exports.get = handleGet;

import type {Site} from '@enonic-types/lib-content';
import type {
	SitemapXmlSiteConfig,
	tChangeFreq,
	tPriority,
} from '/types';


import {forceArray} from '@enonic/js-utils/array/forceArray';
import {
	query as contentQuery
} from '/lib/xp/content';
import {
	DEFAULT_PRIORITY,
	DEFAULT_UPDATE_PERIOD,
} from '/lib/app-sitemapxml/constants';


const GLOBALS: {
	alwaysAdd: string
} = {
	alwaysAdd: 'portal:site',
};


export function queryForSitemapContent({
	count = 10,
	site,
	siteConfig,
	start = 0,
}: {
	count?: number
	site: Site<SitemapXmlSiteConfig>
	siteConfig: SitemapXmlSiteConfig|null
	start?: number
}) {
	const {
		ignoreList = [],
		siteMap = [],
	} = siteConfig || {}; // Handle null (aka no config)

	const ignoreListArray = ignoreList ? forceArray(ignoreList) : [];
	const siteMapArray = siteMap ? forceArray(siteMap) : [];

	let siteAdded = false;
	const arrContentTypes: string[] = [];
	const changefreq: Record<string, tChangeFreq> = {};
	const priority: Record<string, tPriority> = {};

	for (let j = 0; j < siteMapArray.length; j++) {
		const cty = siteMapArray[j].contentType || "";

		// To be able to automatically add site content type if not already added by user.
		if (cty === GLOBALS.alwaysAdd) { siteAdded = true; }

		arrContentTypes.push(cty);
		changefreq[cty] = siteMapArray[j].updatePeriod || DEFAULT_UPDATE_PERIOD;
		priority[cty] = siteMapArray[j].priority || DEFAULT_PRIORITY;
	}

	// Default settings for site (frontpage) set to be changed and prioritized often.
	if (!siteAdded) {
		const cty = GLOBALS.alwaysAdd;
		arrContentTypes.push(cty);
		changefreq[cty] = 'hourly';
		priority[cty] = '1.0';
	}

	// Only allow content from current Site to populate the sitemap.
	const folderPath = site._path;
	const contentRoot = '/content' + folderPath + '';
	const query = `(_path LIKE "${contentRoot}/*" OR _path = "${contentRoot}") ${
		ignoreListArray.map(item => `AND _path NOT LIKE "${item.path}"`).join(" ")
	}`;

	// Query that respects the settings from SEO Metafield plugin, if present, using 6.10 query filters - @nerdegutt.
	return {
		changefreq,
		priority,
		result: contentQuery({
			contentTypes: arrContentTypes,
			count,
			// @ts-ignore TODO
			filters: {
				boolean: {
					mustNot: {
						hasValue: {
							field: "x.com-enonic-app-metafields.meta-data.blockRobots",
							values: "true"
						}
					}
				}
			},
			query: query,
			sort: 'modifiedTime DESC',
			start
		})
	};
}

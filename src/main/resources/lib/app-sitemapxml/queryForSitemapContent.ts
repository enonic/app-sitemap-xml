import type {Site} from '@enonic-types/lib-content';
import type {
	SitemapXmlSiteConfig,
	tChangeFreq,
	tPriority,
} from '/types';


import {forceArray} from '@enonic/js-utils/array/forceArray';
import {isSet} from '@enonic/js-utils/value/isSet';
import {
	query as contentQuery
} from '/lib/xp/content';


const GLOBALS: {
	updatePeriod: tChangeFreq
	priority: tPriority
	alwaysAdd: string
} = {
	updatePeriod: 'monthly',
	priority: '0.5',
	alwaysAdd: 'portal:site',
};


export function queryForSitemapContent({
	count,
	site,
	siteConfig,
}: {
	count?: number
	site: Site<SitemapXmlSiteConfig>
	siteConfig: SitemapXmlSiteConfig|null
}) {
	const {
		ignoreList = [],
		maxItems = 10000,
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
		changefreq[cty] = siteMapArray[j].updatePeriod || GLOBALS.updatePeriod;
		priority[cty] = siteMapArray[j].priority || GLOBALS.priority;
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
			query: query,
			sort: 'modifiedTime DESC',
			contentTypes: arrContentTypes,
			count: isSet(count) ? count : maxItems,
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
			}
		})
	};
}

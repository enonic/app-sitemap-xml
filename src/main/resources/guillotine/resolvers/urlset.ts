import type {
	GraphQL,
	Resolver,
} from '@enonic-types/guillotine';
import type {Site} from '@enonic-types/lib-content';
import type {
	SiteMapResolverData,
	SiteMapResolverLocaleContext
} from '/guillotine/guillotine.d';
import type {
	SitemapXmlSiteConfig,
	tChangeFreq,
	tPriority,
} from '/types';


import {toStr} from '@enonic/js-utils/value/toStr';
import {
	get as getContext,
	run as runInContext
} from '/lib/xp/context';
import {
	DEBUG,
	// PROFILING,
	TRACE,
} from '/constants';
import {
	DEFAULT_PRIORITY,
	DEFAULT_UPDATE_PERIOD,
	MAX_ITEMS_LIMIT,
} from '/lib/app-sitemapxml/constants';
import {queryForSitemapContent} from '/lib/app-sitemapxml/queryForSitemapContent';
import {URLSET_FIELD_NAME} from '/guillotine/constants';
// import {safeMs} from '/guillotine/safeMs';


interface SitemapUrl {
	changefreq?: tChangeFreq
	lastmod?: string
	priority?: tPriority
	path: string
}

// @ts-ignore
// const {currentTimeMillis} = Java.type('java.lang.System') as {
// 	currentTimeMillis: () => number
// }

export const urlset = (graphQL: GraphQL): Resolver<
	{
		count?: number
	},
	SiteMapResolverLocaleContext,
	SiteMapResolverData,
	{}
> => (env) => {
	// const startMs = PROFILING ? currentTimeMillis() : 0;

	TRACE && log.debug('%s resolver env: %s', URLSET_FIELD_NAME, toStr(env));
	const {
		args,
		localContext,
		// source
	} = env;

	const site = JSON.parse(localContext.siteJson as string) as Site<SitemapXmlSiteConfig>;
	TRACE && log.debug('%s resolver site: %s', URLSET_FIELD_NAME, toStr(site));

	const siteConfig = JSON.parse(localContext.siteConfigJson as string) as SitemapXmlSiteConfig;
	TRACE && log.debug('%s resolver siteConfig: %s', URLSET_FIELD_NAME, toStr(siteConfig));

	const {
		maxItems = 10000,
	} = siteConfig || {}; // Handle null (aka no config)

	const maxItemsInt = parseInt(maxItems as string, 10);

	const {
		count = maxItemsInt
	} = args;
	const limitedCount = count < 0
		? MAX_ITEMS_LIMIT
		: Math.min(count, MAX_ITEMS_LIMIT);

	const {
		branch,
		project,
	} = localContext;

	const context = getContext();
	const {
		authInfo: {
			principals = [] // Handle undefined
		} = {}
	} = context;

	return runInContext({
		branch,
		repository: `com.enonic.cms.${project}`,
		principals: principals || [] // Handle null
	}, () => {
		const {
			changefreq,
			priority,
			result
		} = queryForSitemapContent({
			count: limitedCount,
			site,
			siteConfig
		});

		// Go through the results and add the corresponding settings for each match.
		const items: SitemapUrl[] = [];
		for (let i = 0; i < result.hits.length; i++) {
			if (result.hits[i].type) {
				TRACE && log.debug('%s resolver result.hits[%s].type: %s', URLSET_FIELD_NAME, i, result.hits[i].type);
				const path = result.hits[i]._path.replace(site._path, '') || '/';
				items.push({
					changefreq: changefreq[result.hits[i].type] === DEFAULT_UPDATE_PERIOD ? undefined : changefreq[result.hits[i].type],
					lastmod: result.hits[i].modifiedTime,
					priority: priority[result.hits[i].type] === DEFAULT_PRIORITY ? undefined : priority[result.hits[i].type],
					path
				});
			}
		}

		DEBUG && log.debug('%s resolver first item: %s', URLSET_FIELD_NAME, toStr(items[0]));
		TRACE && log.debug('%s resolver all items: %s', URLSET_FIELD_NAME, toStr(items));

		// if (PROFILING) {
		// 	const endMs = currentTimeMillis();
		// 	const durationMs = endMs - startMs;
		// 	log.debug(`resolvers ${URLSET_FIELD_NAME} durationMs: ${safeMs(durationMs)}`);
		// }

		return items;
	}); // runInContext
} // urlset

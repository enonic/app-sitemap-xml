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
	tChangeFreq,
	tPriority,
	SitemapXmlSiteConfig
} from '/types';


import {toStr} from '@enonic/js-utils/value/toStr';
import {
	decodeCursor,
	encodeCursor,
	// @ts-expect-error No types yet.
} from '/lib/graphql-connection';
import {
	// DEBUG,
	TRACE,
	URLSET_CONNECTION_FIELD_NAME
} from '/guillotine/constants';
import {
	DEFAULT_PRIORITY,
	DEFAULT_UPDATE_PERIOD,
} from '/lib/app-sitemapxml/constants';
import {queryForSitemapContent} from '/lib/app-sitemapxml/queryForSitemapContent';


declare interface SitemapUrl {
	changefreq?: tChangeFreq
	lastmod?: string
	priority?: tPriority
	path: string
}

declare type Cursor = string|number // becomes string after Guillotine has done it's thing

declare interface Edge {
	cursor: Cursor
	node: SitemapUrl

}

declare type Edges = Edge[]

declare interface PageInfo {
	startCursor: Cursor
	endCursor: Cursor
	hasNext: boolean
}

const MAX_COUNT_PER_PAGE = 1000;


export const urlsetConnection = (graphQL: GraphQL): Resolver<
	{
		after?: string
		first?: number
	},
	SiteMapResolverLocaleContext,
	SiteMapResolverData,
	{
		edges: Edges,
		pageInfo: PageInfo,
		totalCount: number
	}
> => (env) => {
	TRACE && log.debug('%s resolver env: %s', URLSET_CONNECTION_FIELD_NAME, toStr(env));

	const {
		args,
		localContext,
		// source
	} = env;

	const site = JSON.parse(localContext.siteJson as string) as Site<SitemapXmlSiteConfig>;
	TRACE && log.debug('%s resolver site: %s', URLSET_CONNECTION_FIELD_NAME, toStr(site));

	const siteConfig = JSON.parse(localContext.siteConfigJson as string) as SitemapXmlSiteConfig;
	TRACE && log.debug('%s resolver siteConfig: %s', URLSET_CONNECTION_FIELD_NAME, toStr(siteConfig));

	const {
		maxItems = 10000,
	} = siteConfig || {}; // Handle null (aka no config)

	const maxItemsInt = parseInt(maxItems as string, 10);

	const {
		after,
		first = maxItemsInt,
	} = args;

	const start = after ? parseInt(decodeCursor(after), 10) + 1 : 0;
	TRACE && log.debug('%s resolver after: %s', URLSET_CONNECTION_FIELD_NAME, after);
	TRACE && log.debug('%s resolver start: %s', URLSET_CONNECTION_FIELD_NAME, start);

	const count = first < 0
		? MAX_COUNT_PER_PAGE
		: Math.min(
			MAX_COUNT_PER_PAGE,
			first
		);
	TRACE && log.debug('%s resolver first: %s', URLSET_CONNECTION_FIELD_NAME, first);
	TRACE && log.debug('%s resolver count: %s', URLSET_CONNECTION_FIELD_NAME, count);

	const {
		changefreq,
		priority,
		result
	} = queryForSitemapContent({
		count,
		site,
		siteConfig,
		start
	});

	const {
		hits,
		total
	} = result;
	TRACE && log.debug('%s resolver hits: %s', URLSET_CONNECTION_FIELD_NAME, toStr(hits));

	// Go through the results and add the corresponding settings for each match.
	const items: SitemapUrl[] = [];
	for (let i = 0; i < result.hits.length; i++) {
		if (result.hits[i].type) {
			TRACE && log.debug('%s resolver result.hits[%s].type: %s', URLSET_CONNECTION_FIELD_NAME, i, result.hits[i].type);
			const path = result.hits[i]._path.replace(site._path, '') || '/';
			items.push({
				changefreq: changefreq[result.hits[i].type] === DEFAULT_UPDATE_PERIOD ? undefined : changefreq[result.hits[i].type],
				lastmod: result.hits[i].modifiedTime,
				priority: priority[result.hits[i].type] === DEFAULT_PRIORITY ? undefined : priority[result.hits[i].type],
				path
			});
		}
	}

	const edges: Edges = [];
	for (let i = 0; i < items.length; i++) {
		edges.push({
			node: items[i],
			cursor: encodeCursor(start + i)
		});
	}

	const countInPage = edges.length;
	const pageInfo: PageInfo = {
		startCursor: start,
		endCursor: start + (countInPage === 0 ? 0 : (countInPage - 1)),
		hasNext: (start + countInPage) < total
	}

	return {
		edges,
		pageInfo,
		totalCount: total
	}
};

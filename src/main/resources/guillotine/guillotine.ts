import type {
	Extensions,
	GraphQL,
} from '@enonic-types/guillotine';
import type {Site} from '@enonic-types/lib-content';
import type {
	SitemapXmlSiteConfig,
	tChangeFreq,
	tPriority,
} from '/types';


import {ObjectTypeName} from '@enonic-types/guillotine';
import {
	get as getContentByKey,
} from '/lib/xp/content';
import {
	get as getContext,
	run as runInContext
} from '/lib/xp/context';
import {getSiteConfigFromSite} from '/guillotine/getSiteConfigFromSite';
// import {safeMs} from '/guillotine/safeMs';
import {queryForSitemapContent} from '/lib/app-sitemapxml/queryForSitemapContent';


const DEBUG = false;
const TRACE = false;
// const PROFILING = false;

// @ts-ignore
// const {currentTimeMillis} = Java.type('java.lang.System') as {
// 	currentTimeMillis: () => number
// }


// In type names first letter should be uppercase
const enum GraphQLTypeName {
	SITEMAP = 'Sitemap',
	SITEMAP_URL = 'SitemapUrl',
}

// In fields names first letter should be lowercase
const SITEMAP_FIELD_NAME = 'sitemap';
const URLSET_FIELD_NAME = 'urlset';


interface SitemapUrl {
	changefreq?: tChangeFreq
	lastmod?: string
	priority?: tPriority
	url: string
}


export const extensions = (graphQL: GraphQL): Extensions => ({
	types: {
		[GraphQLTypeName.SITEMAP]: {
			description: 'Sitemap',
			fields: {
				urlset: {
					type: graphQL.list(graphQL.reference(GraphQLTypeName.SITEMAP_URL)),
				}
			}
		},
		[GraphQLTypeName.SITEMAP_URL]: {
			description: 'Sitemap URL item',
			fields: {
				url: {
					type: graphQL.nonNull(graphQL.GraphQLString),
				},
				changefreq: {
					type: graphQL.GraphQLString
				},
				lastmod: {
					type: graphQL.DateTime,
				},
				priority: {
					type: graphQL.GraphQLString
				},
			}
		}
	},
	creationCallbacks: {
		[ObjectTypeName.HeadlessCms]: (params) => {
			params.addFields({
				[SITEMAP_FIELD_NAME]: {
					type: graphQL.reference(GraphQLTypeName.SITEMAP),
				},
			});
		},
		[GraphQLTypeName.SITEMAP]: (params) => {
			params.addFields({
				[URLSET_FIELD_NAME]: {
					args: {
						count: graphQL.GraphQLInt
					},
					type: graphQL.list(graphQL.reference(GraphQLTypeName.SITEMAP_URL)),
				},
			});
		}
	},
	resolvers: {
		[ObjectTypeName.HeadlessCms]: {
			[SITEMAP_FIELD_NAME]: (env) => {
				TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} env: ${JSON.stringify(env, null, 4)}`);
				const {
					args,
					localContext,
					// source
				} = env;
				const {
					branch,
					project,
					siteKey // NOTE: Can be undefined when x-guillotine-sitekey is missing
				} = localContext;
				TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} siteKey: ${siteKey}`);
				if (!siteKey) {
					return null;
				}
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
					const site = getContentByKey<Site<SitemapXmlSiteConfig>>({key: siteKey});
					TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} site: ${JSON.stringify(site, null, 4)}`);
					if (!site) {
						return null;
					}
					const siteConfig = getSiteConfigFromSite({
						applicationKey: app.name,
						site,
					});
					return {
						_site: site,
						_siteConfig: siteConfig,
					};
				}); // runInContext
			} // SITEMAP_FIELD_NAME
		}, // HeadlessCms
		[GraphQLTypeName.SITEMAP]: {
			[URLSET_FIELD_NAME]: (env) => {
				TRACE && log.debug(`resolvers ${URLSET_FIELD_NAME} env: ${JSON.stringify(env, null, 4)}`);

				const {
					args,
					localContext,
					source
				} = env;
				TRACE && log.debug(`resolvers ${URLSET_FIELD_NAME} source: ${JSON.stringify(source, null, 4)}`);

				const {
					_site,
					_siteConfig
				} = source as {
					_site: Site<SitemapXmlSiteConfig>,
					_siteConfig: SitemapXmlSiteConfig
				};

				const {
					branch,
					project,
					siteKey // NOTE: Can be undefined when x-guillotine-sitekey is missing
				} = localContext;
				TRACE && log.debug(`resolvers ${URLSET_FIELD_NAME} siteKey: ${siteKey}`);

				if (!siteKey) {
					return null;
				}
				// const startMs = PROFILING ? currentTimeMillis() : 0;
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
					TRACE && log.debug(`resolvers ${URLSET_FIELD_NAME} _siteConfig: ${JSON.stringify(_siteConfig, null, 4)}`);
					const {
						overrideDomain = '',
					} = _siteConfig || {};

					const {
						count
					} = args;

					const {
						changefreq,
						priority,
						result
					} = queryForSitemapContent({
						count,
						site: _site,
						siteConfig: _siteConfig
					})

					// Go through the results and add the corresponding settings for each match.
					const items: SitemapUrl[] = [];
					for (let i = 0; i < result.hits.length; i++) {
						if (result.hits[i].type) {
							TRACE && log.debug(`resolvers ${URLSET_FIELD_NAME} result.hits[${i}].type: ${result.hits[i].type}`);
							const path = result.hits[i]._path.replace(_site._path, '') || '/';
							items.push({
								changefreq: changefreq[result.hits[i].type],
								lastmod: result.hits[i].modifiedTime,
								priority: priority[result.hits[i].type],
								url: overrideDomain ? `${overrideDomain}${path}`.replace(/\/\//g, '/') : path
							});
						}
					}

					DEBUG && log.debug(`resolvers ${URLSET_FIELD_NAME} first item: ${JSON.stringify(items[0], null, 4)}`);
					TRACE && log.debug(`resolvers ${URLSET_FIELD_NAME} all items: ${JSON.stringify(items, null, 4)}`);

					// if (PROFILING) {
					// 	const endMs = currentTimeMillis();
					// 	const durationMs = endMs - startMs;
					// 	log.debug(`resolvers ${URLSET_FIELD_NAME} durationMs: ${safeMs(durationMs)}`);
					// }

					return items;
					// return {
					// 	urlset: items
					// };
				}); // runInContext
			}, // SITEMAP_XML
		}, // HEADLESS_CMS
	} // resolvers
});

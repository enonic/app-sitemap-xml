import type {Site} from '@enonic-types/lib-content';
import type {Branded} from '/guillotine/Branded';
import type {SitemapXmlSiteConfig} from '/guillotine/SitemapXmlSiteConfig';
import type {
	tChangeFreq,
	tPriority,
} from '/guillotine/Sitemap.d';


import {forceArray} from '@enonic/js-utils/array/forceArray';
import {
	get as getContentByKey,
	query as contentQuery
} from '/lib/xp/content';
import {
	get as getContext,
	run as runInContext
} from '/lib/xp/context';
import {getSiteConfigFromSite} from '/guillotine/getSiteConfigFromSite';


const enum GraphQLTypeName {
	HEADLESS_CMS = 'HeadlessCms',
	SITEMAP = 'Sitemap',
	SITEMAP_URL = 'SitemapUrl',
}

enum HeadlessCms { // Can't prefix with const nor declare
	SITEMAP_FIELD = 'Sitemap',
}

// enum Sitemap { // Can't prefix with const nor declare
// 	URLSET_FIELD = 'urlset'
// }

const GRAPH_QL_TYPE_FIELDS = {
	[GraphQLTypeName.HEADLESS_CMS]: HeadlessCms
}

declare interface Resolvers {
	[GraphQLTypeName.HEADLESS_CMS]: {
		[HeadlessCms.SITEMAP_FIELD]: Resolver
	}
	// [GraphQLTypeName.SITEMAP]: {
	// 	[Sitemap.URLSET_FIELD]: Resolver
	// }
}

declare interface CreationCallback {
	(params: {
		addFields: (fields: Record<string, {
			args: Record<string, GraphQLType>
			type: GraphQLType
		}>) => void
	}): void
}

declare interface Env <
	ARGS extends Record<string, any> = Record<string, any>,
	SOURCE extends Record<string, any> = Record<string, any> // Content
> {
	args: ARGS
	localContext: LocalContext
	source: SOURCE
}

declare interface ExtensionEnum {
	description: string
	values: Record<string, string>
}

declare interface Extensions {
	inputTypes?: Record<string, any>
	enums?: Record<string, ExtensionEnum>
	interfaces?: Record<string, any>
	unions?: Record<string, any>
	types?: Record<string, ExtensionType>
	creationCallbacks?: Record<string, CreationCallback>
	resolvers?: Resolvers
	typeResolvers?: Record<string, any>
}

declare interface ExtensionType {
	description: string
	fields: Record<string, {
		type: GraphQLType
	}>
}

declare type GraphQL = Record<GraphQLTypes, GraphQLType> & {
	nonNull: (type: GraphQLType) => GraphQLType
	list: (type: GraphQLType) => GraphQLType
	reference: (type: GraphQLTypeName) => GraphQLType
}

declare type GraphQLString = Branded<string, 'GraphQLString'>
declare type GraphQLInt = Branded<number, 'GraphQLInt'>
declare type GraphQLID = Branded<string, 'GraphQLID'>
declare type GraphQLBoolean = Branded<boolean, 'GraphQLBoolean'>
declare type GraphQLFloat = Branded<number, 'GraphQLFloat'>
declare type GraphQLJson = Branded<string, 'Json'>
declare type GraphQLDateTime = Branded<string, 'DateTime'>
declare type GraphQLDate = Branded<string, 'Date'>
declare type GraphQLLocalTime = Branded<string, 'LocalTime'>
declare type GraphQLLocalDateTime = Branded<string, 'LocalDateTime'>

declare type GraphQLType =
	| GraphQLString
	| GraphQLInt
	| GraphQLID
	| GraphQLBoolean
	| GraphQLFloat
	| GraphQLJson
	| GraphQLDateTime
	| GraphQLDate
	| GraphQLLocalTime
	| GraphQLLocalDateTime
	// Extensions:

declare type GraphQLTypes =
	|'GraphQLString'
	|'GraphQLInt'
	|'GraphQLID'
	|'GraphQLBoolean'
	|'GraphQLFloat'
	|'Json'
	|'DateTime'
	|'Date'
	|'LocalTime'
	|'LocalDateTime'

declare interface LocalContext {
	branch: string
	project: string
	siteKey?: string
}

// declare type PartialRecord<K extends keyof any, T> = {
// 	[P in K]?: T;
// }

declare interface Resolver<
	ARGS extends Record<string, any> = Record<string, any>,
	SOURCE extends Record<string, any> = Record<string, any>, // Content,
	RETURN = any
> {
	(env: Env<ARGS,SOURCE>): RETURN
}


const APP_NAME = 'com.enonic.app.sitemapxml';

const GLOBALS: {
	updatePeriod: tChangeFreq
	priority: tPriority
	alwaysAdd: string
} = {
	updatePeriod: 'monthly',
	priority: '0.5',
	alwaysAdd: 'portal:site',
};


export const extensions = (graphQL: GraphQL): Extensions => ({
	// Enums are only useful in inputTypes, not in types
	// enums: {
	// },
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
				path: {
					type: graphQL.nonNull(graphQL.GraphQLString),
				},
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
		[GraphQLTypeName.HEADLESS_CMS]: function (params) {
			params.addFields({
				[GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD]: {
					args: {
						count: graphQL.GraphQLInt
					},
					type: graphQL.reference(GraphQLTypeName.SITEMAP),
				},
			});
		},
	},
	resolvers: {
		[GraphQLTypeName.HEADLESS_CMS]: {
			[GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD]: (env) => {
				// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} env: ${JSON.stringify(env, null, 4)}`);
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
				// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} siteKey: ${siteKey}`);
				if (!siteKey) {
					return null;
				}
				const context = getContext();
				const {
					authInfo: {
						// user: { // NOTE: Can be undefined when not logged in
						// 	login: userLogin,
						// 	idProvider: userIdProvider
						// },
						principals = [] // Handle undefined
					} = {}
				} = context;
				return runInContext({
					branch,
					repository: `com.enonic.cms.${project}`,
					// user: {
					// 	idProvider: userIdProvider,
					// 	login: userLogin,
					// },
					principals: principals || [] // Handle null
				}, () => {
					const site = getContentByKey<Site<SitemapXmlSiteConfig>>({key: siteKey});
					// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} site: ${JSON.stringify(site, null, 4)}`);
					if (!site) {
						return null;
					}
					const siteConfig = getSiteConfigFromSite({
						applicationKey: APP_NAME,
						site,
					});
					const {
						ignoreList = [],
						maxItems = 10000,
						overrideDomain = '',
						siteMap = [],
					} = siteConfig || {};
					// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} siteConfig: ${JSON.stringify(siteConfig, null, 4)}`);

					const {
						count = maxItems
					} = args;

					const siteMapArray = siteMap ? forceArray(siteMap) : [];
					// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} siteMapArray: ${JSON.stringify(siteMapArray, null, 4)}`);

					const ignoreListArray = ignoreList ? forceArray(ignoreList) : [];
					let siteAdded = false;
					const arrContentTypes: string[] = [];
					const changefreq: Record<string, tChangeFreq> = {};
					const priority: Record<string, tPriority> = {};

					for (let j = 0; j < siteMapArray.length; j++) {
						const cty = siteMapArray[j].contentType || "";
						// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} cty: ${cty}`);

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

					// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} changefreq: ${JSON.stringify(changefreq, null, 4)}`);
					// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} priority: ${JSON.stringify(priority, null, 4)}`);

					// Only allow content from current Site to populate the sitemap.
					const folderPath = site._path;
					const contentRoot = '/content' + folderPath + '';
					const query = `(_path LIKE "${contentRoot}/*" OR _path = "${contentRoot}") ${
						ignoreListArray.map(item => `AND _path NOT LIKE "${item.path}"`).join(" ")
					}`;

					// Query that respects the settings from SEO Metafield plugin, if present, using 6.10 query filters - @nerdegutt.
					const result = contentQuery({
						query: query,
						sort: 'modifiedTime DESC',
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
						}
					});

					// Go through the results and add the corresponding settings for each match.
					const items = [];
					for (let i = 0; i < result.hits.length; i++) {
						const item: Partial<{
							changefreq?: tChangeFreq
							priority?: tPriority
							path: string
							lastmod?: string
							url?: string
						}> = {};
						if (result.hits[i].type) {
							// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} result.hits[${i}].type: ${result.hits[i].type}`);
							item.changefreq = changefreq[result.hits[i].type];
							item.priority = priority[result.hits[i].type];
							item.path = result.hits[i]._path.replace(site._path, '') || '/';
							item.url = overrideDomain ? `${overrideDomain}${item.path}`.replace(/\/\//g, '/') : item.path;
							item.lastmod = result.hits[i].modifiedTime;
							items.push(item);
						// } else { // This makes no sense, overwriting the hits array within the loop
						// 	result.hits = [];
						}
					}

					// log.info(`resolvers ${GRAPH_QL_TYPE_FIELDS[GraphQLTypeName.HEADLESS_CMS].SITEMAP_FIELD} first item: ${JSON.stringify(items[0], null, 4)}`);

					return {
						urlset: items
					};
				}); // runInContext
			}, // SITEMAP_XML
		}, // HEADLESS_CMS
	} // resolvers
});

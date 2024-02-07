import type {Site} from '@enonic-types/lib-content';
import type {Branded} from '/guillotine/Branded';
import type {SitemapXmlSiteConfig} from '/guillotine/SitemapXmlSiteConfig';


import {forceArray} from '@enonic/js-utils/array/forceArray';
import {
	get as getContentByKey,
	query as contentQuery
} from '/lib/xp/content';
// @ts-expect-error No types yet
import {render} from '/lib/xslt';
import {getSiteConfigFromSite} from '/guillotine/getSiteConfigFromSite';


const enum GraphQLTypeName {
	HEADLESS_CMS = 'HeadlessCms',
	SITEMAP_XML = 'SitemapXml',
}

const enum GraphQLFieldName {
	SITEMAP_XML = 'SitemapXml',
}

declare interface CreationCallback {
	(params: {
		addFields: (fields: Record<string, {
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
	resolvers?: PartialRecord<GraphQLTypeName, PartialRecord<GraphQLFieldName, Resolver>>
	// resolvers?: PartialRecord<GraphQLTypeName, Resolver>
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

declare type PartialRecord<K extends keyof any, T> = {
	[P in K]?: T;
}

declare interface Resolver<
	ARGS extends Record<string, any> = Record<string, any>,
	SOURCE extends Record<string, any> = Record<string, any>, // Content,
	RETURN = any
> {
	(env: Env<ARGS,SOURCE>): RETURN
}


const APP_NAME = 'com.enonic.app.sitemapxml';
const VIEW = resolve('sitemap.xsl');

const GLOBALS = {
    updatePeriod: "monthly",
    priority: "0.5",
    alwaysAdd: "portal:site"
};

export const extensions = (graphQL: GraphQL): Extensions => {
	return {
		types: {
			[GraphQLTypeName.SITEMAP_XML]: {
				description: 'Sitemap',
				fields: {
					xml: {
						type: graphQL.GraphQLString,
					}
				}
			},
		},
		creationCallbacks: {
			[GraphQLTypeName.HEADLESS_CMS]: function (params) {
				params.addFields({
					[GraphQLFieldName.SITEMAP_XML]: {
						type: graphQL.reference(GraphQLTypeName.SITEMAP_XML),
					},
				});
			},
		},
		resolvers: {
			[GraphQLTypeName.HEADLESS_CMS]: {
				[GraphQLFieldName.SITEMAP_XML]: (env) => {
					log.info(`resolvers ${GraphQLFieldName.SITEMAP_XML} env: ${JSON.stringify(env, null, 4)}`);
					const {
						// args,
						localContext,
						// source
					} = env;
					const {
						branch,
						project,
						siteKey // NOTE: Can be undefined when x-guillotine-sitekey is missing
					} = localContext;
					log.info(`resolvers ${GraphQLFieldName.SITEMAP_XML} siteKey: ${siteKey}`);
					if (!siteKey) {
						return null;
					}
					const site = getContentByKey<Site<SitemapXmlSiteConfig>>({key: siteKey});
					log.info(`resolvers ${GraphQLFieldName.SITEMAP_XML} site: ${JSON.stringify(site, null, 4)}`);
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
						siteMap = []
						// contentType
						// updatePeriod
						// priority
					} = siteConfig || {};
					log.info(`resolvers ${GraphQLFieldName.SITEMAP_XML} siteConfig: ${JSON.stringify(siteConfig, null, 4)}`);

					const siteMapArray = siteMap ? forceArray(siteMap) : [];
					const ignoreListArray = ignoreList ? forceArray(ignoreList) : [];
					let siteAdded = false;
					const arrContentTypes: string[] = [];
					const changefreq: Record<string, string> = {};
					const priority: Record<string, string> = {};

					for (let j = 0; j < siteMapArray.length; j++) {
						const cty = siteMapArray[j].contentType || "";
						if (cty === GLOBALS.alwaysAdd) { siteAdded = true; } // To be able to automatically add site content type if not already added by user.
						arrContentTypes.push(cty);
						changefreq[cty] = siteMapArray[j].updatePeriod || GLOBALS.updatePeriod;
						priority[cty] = siteMapArray[j].priority || GLOBALS.priority;
					}

					// Default settings for site (frontpage) set to be changed and prioritized often.
					if (!siteAdded) {
						const cty = GLOBALS.alwaysAdd;
						arrContentTypes.push(cty);
						changefreq[cty] = "Hourly";
						priority[cty] = "1.0";
					}

					// Only allow content from current Site to populate the sitemap.
					const folderPath = site._path;
					const contentRoot = '/content' + folderPath + '';
					const query = `(_path LIKE "${contentRoot}/*" OR _path = "${contentRoot}") ${
						ignoreList.map(item => `AND _path NOT LIKE "${item.path}"`).join(" ")
					}`;

					// Query that respects the settings from SEO Metafield plugin, if present, using 6.10 query filters - @nerdegutt.
					const result = contentQuery({
						query: query,
						sort: 'modifiedTime DESC',
						contentTypes: arrContentTypes,
						count: maxItems,
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
						const item: {
							changeFreq?: string
							priority?: string
							url?: string
							modifiedTime?: string
						} = {};
						if (result.hits[i].type) {
							item.changeFreq = changefreq[result.hits[i].type];
							item.priority = priority[result.hits[i].type];
							// item.url = overrideDomain ? getServerPageUrl(result.hits[i]._path, overrideDomain) : getAbsolutePageUrl(result.hits[i]._path);
							item.modifiedTime = result.hits[i].modifiedTime;
							items.push(item);
						} else {
							result.hits = [];
						}
					}

					const model = {
						result: items
					};

					const xml = render(VIEW, model);
					// log.info('xml: %s', xml);

					return {
						xml
					};
				},
			},
		} // resolvers
	}; // return
} // extensions

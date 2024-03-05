// Make sure ScriptValue type exists in global scope:
/// <reference types="@enonic-types/global"/>


import type {
	Site,
} from '@enonic-types/lib-content';
import type {
	BaseFolderContent,
	GraphQL,
	GraphQLBoolean,
	GraphQLDate,
	GraphQLDateTime,
	GraphQLFloat,
	GraphQLID,
	GraphQLInt,
	GraphQLJson,
	GraphQLLocalDateTime,
	GraphQLLocalTime,
	GraphQLString,
	// LocalContext,
} from '@enonic-types/guillotine';
import type {SitemapXmlSiteConfig} from '/types';


import {
	describe,
	expect,
	test as it,
} from '@jest/globals';
// @ts-expect-error No types
import {print} from 'q-i';
import {mockLibXpContent} from '../mocks/mockLibXpContent';
import {mockLibXpContext} from '../mocks/mockLibXpContext';


// @ts-ignore TS2339: Property 'log' does not exist on type 'typeof globalThis'.
globalThis.log = {
	error: console.error,
	warning: console.warn,
	info: console.info,
	debug: console.debug,
}

// @ts-ignore TS2339: Property '__' does not exist on type 'typeof globalThis'.
globalThis.__ = {
	toScriptValue: (value: unknown) => value
}

const graphQL: GraphQL = {
	Date: '2021-01-01' as GraphQLDate,
	DateTime: '2021-01-01T00:00:00Z' as GraphQLDateTime,
	GraphQLBoolean: true as GraphQLBoolean,
	GraphQLFloat: 1.1 as GraphQLFloat,
	GraphQLID: 'id' as GraphQLID,
	GraphQLInt: 1 as GraphQLInt,
	GraphQLString: 'string' as GraphQLString,
	Json: '{"json": "value"}' as GraphQLJson,
	LocalTime: '00:00:00' as GraphQLLocalTime,
	LocalDateTime: '2021-01-01T00:00:00' as GraphQLLocalDateTime,
	createDataFetcherResult: ({data, localContext, parentLocalContext}) => ({
		source: data as any,
		localContext: {...parentLocalContext, ...localContext}
	}),
	// createDataFetcherResult: () => ({
	// 	data: null as unknown as ScriptValue,
	// 	// localContext: {}// as LocalContext,
	// 	// parentLocalContext: {}// as LocalContext
	// }),
	list: (type) => [type],
	nonNull: (type) => type,
	reference: (typeName) => {
		if (typeName === 'Sitemap_Url') {
			return {
				changefreq: null,
				lastmod: '2021-01-01T00:00:00Z',
				path: null,
				priority: null,
			};
		}
		console.debug('reference typeName', typeName);
		return null;
	},
};

const siteConfig: SitemapXmlSiteConfig = {
	ignoreList: [],
	maxItems: 1000,
	overrideDomain: 'https://enonic.com',
	siteMap: []
}

const siteContent: Site<SitemapXmlSiteConfig> = {
	_id: 'siteId',
	_name: 'siteName',
	_path: '/sitePath',
	attachments: {},
	creator: 'user:system:creator',
	createdTime: '2021-01-01T00:00:00Z',
	data: {
		description: 'siteDescription',
		siteConfig: {
			applicationKey: 'com.enonic.app.sitemapxml',
			config: siteConfig,
		},
	},
	displayName: 'folderContentDisplayName',
	owner: 'user:system:owner',
	type: 'portal:site',
	hasChildren: false,
	valid: true,
	x: {},
}

const folderContent: BaseFolderContent = {
	_id: 'folderContentId',
	_name: 'folderContentName',
	_path: '/sitePath/folderContentPath',
	attachments: {},
	creator: 'user:system:creator',
	createdTime: '2021-01-01T00:00:00Z',
	data: {},
	displayName: 'folderContentDisplayName',
	owner: 'user:system:owner',
	type: 'base:folder',
	hasChildren: false,
	valid: true,
	x: {},
}


mockLibXpContent({
	contents: {
		// [siteContent._id]: siteContent,
		[siteContent._path]: siteContent,
		// [folderContent._id]: folderContent,
		[folderContent._path]: folderContent,
	},
	siteContent
});
mockLibXpContext();


describe('guillotine extensions', () => {
	it("does it's thing", () => {
		import('/guillotine/guillotine').then(({extensions}) => {
			const res = extensions(graphQL);
			// print(JSON.parse(JSON.stringify(res)));
			expect(JSON.parse(JSON.stringify(res))).toEqual({
				creationCallbacks: {
				},
				resolvers: {
					portal_Site: {
					},
					Sitemap: {
					}
				},
				types: {
					Sitemap: {
						description: 'Sitemap',
						fields: {
							baseUrl: {
								type: 'string'
							},
							urlset: {
								type: [{
									changefreq: null,
									lastmod: '2021-01-01T00:00:00Z',
									path: null,
									priority: null,
								}]
							}
						}
					},
					Sitemap_Url: {
						description: 'Sitemap URL item',
						fields: {
							changefreq: {
								type: 'string'
							},
							lastmod: {
								type: '2021-01-01T00:00:00Z'
							},
							path: {
								type: 'string'
							},
							priority: {
								type: 'string'
							},
						}
					}
				}
			}); // expect

			// print(res);
			const {
				// creationCallbacks: {
				// 	HeadlessCms: HeadlessCmsFunction,
				// 	Sitemap: SitemapFunction
				// },
				resolvers: {
					portal_Site: {
						sitemap: sitemapResolver
					},
					Sitemap: {
						urlset: urlsetResolver
					}
				}
			} = res;

			const sitemapResolverResult = sitemapResolver({
				args: {},
				localContext: {
					branch: 'master',
					project: 'project',
				},
				source: siteContent
			});
			// print(sitemapResolverResult);
			expect(sitemapResolverResult).toEqual({
				localContext: {
					branch: 'master',
					project: 'project',
					siteJson: JSON.stringify(siteContent),
					siteConfigJson: JSON.stringify(siteConfig)
				},
				source: {
					baseUrl: 'https://enonic.com'
				},
			});

			const urlsetResolverResult = urlsetResolver({
				args: {
					count: 10
				},
				localContext: sitemapResolverResult.localContext,
				source: sitemapResolverResult.source,
			});
			// print(urlsetResolverResult);
			expect(urlsetResolverResult).toEqual([
				{
					changefreq: 'hourly',
					lastmod: undefined,
					path: '/',
					priority: '1.0',
				},{
					changefreq: undefined,
					lastmod: undefined,
					path: '/folderContentPath',
					priority: undefined,
				}
			]);
		}); // import
	}); // it
}); // guillotine extensions

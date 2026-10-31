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
	jest,
	test as it,
} from '@jest/globals';
import {mockLibXpContent} from '../mocks/mockLibXpContent';
import {mockLibXpContext} from '../mocks/mockLibXpContext';


// @ts-ignore TS2339: Property 'log' does not exist on type 'typeof globalThis'.
globalThis.log = {
	error: console.error,
	warning: console.warn,
	info: console.info,
	debug: console.debug,
}

const URLS = [
	{changefreq: 'hourly', path: '/', priority: '1.0'},
	{lastmod: '2021-01-01T00:00:00Z', path: '/folderContentPath'},
];

const bean = {
	guillotineUrlset: jest.fn((_params: unknown, count: number) => JSON.stringify(URLS.slice(0, count))),
	guillotineFiles: jest.fn((_params: unknown) => JSON.stringify([{number: 1, lastmod: '2021-01-01T00:00:00Z'}, {number: 2}])),
	guillotineFile: jest.fn((_params: unknown, number: number) => (number === 1 ? JSON.stringify(URLS) : null)),
};

// @ts-ignore TS2339: Property '__' does not exist on type 'typeof globalThis'.
globalThis.__ = {
	newBean: () => bean,
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
	} as unknown as ReturnType<GraphQL['createDataFetcherResult']>),
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
	maxItems: '1000',
	overrideDomain: 'https://enonic.com',
	siteMap: []
}

const projectConfig: SitemapXmlSiteConfig = {
	...siteConfig,
	overrideDomain: 'https://project.example.com'
};

jest.mock('/lib/xp/project', () => ({
	get: jest.fn().mockReturnValue({
		siteConfig: [{applicationKey: 'com.enonic.app.sitemapxml', config: projectConfig}]
	})
}), {virtual: true});

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
		[siteContent._path]: siteContent,
		[folderContent._path]: folderContent,
	},
	siteContent
});
mockLibXpContext();


describe('guillotine extensions', () => {
	it("does it's thing", () => {
		return import('/guillotine/guillotine').then(async ({extensions}) => {
			const res = extensions(graphQL);
			// print(JSON.parse(JSON.stringify(res)));
			expect(Object.keys(res.types)).toEqual(['Sitemap', 'Sitemap_Url', 'Sitemap_File']);
			expect(Object.keys(res.types.Sitemap.fields)).toEqual(['baseUrl', 'urlset', 'files', 'file']);
			expect(Object.keys(res.types.Sitemap_File.fields)).toEqual(['number', 'lastmod', 'urlset']);
			expect(Object.keys(res.resolvers.Sitemap)).toEqual(['urlset', 'files', 'file']);
			expect(Object.keys(res.resolvers.Sitemap_File)).toEqual(['urlset']);

			// print(res);
			const {
				// creationCallbacks: {
				// 	HeadlessCms: HeadlessCmsFunction,
				// 	Sitemap: SitemapFunction
				// },
				resolvers: {
					HeadlessCms: {
						sitemap: sitemapResolver
					},
					Sitemap: {
						urlset: urlsetResolver,
						files: filesResolver,
						file: fileResolver,
					},
					Sitemap_File: {
						urlset: fileUrlsetResolver,
					}
				}
			} = res;


			const sitemapResolverResult = sitemapResolver({
				args: {},
				localContext: {
					branch: 'master',
					project: 'project',
					scopeKey: siteContent._path
				},
				source: null
			});
			// print(sitemapResolverResult);
			expect(sitemapResolverResult).toEqual({
				localContext: {
					branch: 'master',
					project: 'project',
					siteJson: JSON.stringify(siteContent),
					scopeKey: siteContent._path,
					siteConfigJson: JSON.stringify(siteConfig)
				},
				source: {
					baseUrl: 'https://enonic.com'
				},
			});

			for (const keys of [
				{siteKey: siteContent._path},
				{scopeKey: siteContent._path, siteKey: '/other-site'},
				{scopeKey: folderContent._path},
				{scopeKey: null, siteKey: siteContent._path},
			]) {
				const result = sitemapResolver({
					args: {},
					localContext: {
						branch: 'master',
						project: 'project',
						...keys,
					},
					source: null
				});
				expect(result.source).toEqual(sitemapResolverResult.source);
				expect(result.localContext.siteJson).toBe(JSON.stringify(siteContent));
			}

			for (const keys of [
				{},
				{scopeKey: '/'},
				{scopeKey: '/', siteKey: siteContent._path},
				{scopeKey: '/outside-site'},
			]) {
				const result = sitemapResolver({
					args: {},
					localContext: {branch: 'master', project: 'project', ...keys},
					source: null
				});
				expect(result.source).toEqual({baseUrl: projectConfig.overrideDomain});
				expect(result.localContext.siteJson).toBe('null');
				expect(result.localContext.siteConfigJson).toBe(JSON.stringify(projectConfig));
				const urls = urlsetResolver({
					args: {},
					localContext: result.localContext,
					source: result.source
				});
				expect(urls).toEqual(URLS);
				expect(bean.guillotineUrlset).toHaveBeenLastCalledWith(expect.objectContaining({
					sitePath: '',
					siteUrl: projectConfig.overrideDomain,
					maxItems: 1000,
				}), 1000);
				const {run} = await import('/lib/xp/context');
				expect(jest.mocked(run)).toHaveBeenLastCalledWith(
					expect.objectContaining({branch: 'master', repository: 'com.enonic.cms.project'}), expect.any(Function)
				);
			}

			const urlsetResolverResult = urlsetResolver({
				args: {
					count: 10
				},
				localContext: sitemapResolverResult.localContext,
				source: sitemapResolverResult.source,
			});
			// print(urlsetResolverResult);
			expect(urlsetResolverResult).toEqual(URLS);
			expect(bean.guillotineUrlset).toHaveBeenLastCalledWith(expect.objectContaining({
				sitePath: siteContent._path,
				siteUrl: 'https://enonic.com',
			}), 10);

			expect(urlsetResolver({
				args: {count: 5000},
				localContext: sitemapResolverResult.localContext,
				source: sitemapResolverResult.source,
			})).toEqual(URLS);
			expect(bean.guillotineUrlset).toHaveBeenLastCalledWith(expect.anything(), 1000);

			const files = filesResolver({args: {}, localContext: sitemapResolverResult.localContext, source: sitemapResolverResult.source});
			expect(files).toEqual([{number: 1, lastmod: '2021-01-01T00:00:00Z'}, {number: 2}]);
			expect(fileResolver({args: {number: 2}, localContext: sitemapResolverResult.localContext, source: sitemapResolverResult.source}))
				.toEqual({number: 2});
			expect(fileResolver({args: {number: 3}, localContext: sitemapResolverResult.localContext, source: sitemapResolverResult.source}))
				.toBeNull();
			expect(fileUrlsetResolver({args: {}, localContext: sitemapResolverResult.localContext, source: files[0]})).toEqual(URLS);
			expect(fileUrlsetResolver({args: {}, localContext: sitemapResolverResult.localContext, source: files[1]})).toEqual([]);
			expect(bean.guillotineFile).toHaveBeenLastCalledWith(expect.anything(), 2);
		}); // import
	}); // it
}); // guillotine extensions

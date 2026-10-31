import type {
	GraphQL,
	LocalContext,
	Resolver,
} from '@enonic-types/guillotine';
import type {ScriptValue} from '@enonic-types/core';
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


import {
	get as getContext,
	run as runInContext
} from '/lib/xp/context';
import {
	newSitemapBean,
	toSitemapParams,
} from '/lib/app-sitemapxml/sitemapBean';


export interface SitemapUrl {
	changefreq?: tChangeFreq
	lastmod?: string
	priority?: tPriority
	path: string
}

export interface SitemapFile {
	number: number
	lastmod?: string
}

// Runs the given function with the bean and params of the sitemap the local context points to,
// in the context of its project (the sitemap itself is always generated from published content).
export function withSitemap<T>(
	localContext: LocalContext<SiteMapResolverLocaleContext>,
	fn: (bean: ReturnType<typeof newSitemapBean>, params: ScriptValue) => T
): T {
	const site = JSON.parse(localContext.siteJson) as Site<SitemapXmlSiteConfig> | null;
	const siteConfig = JSON.parse(localContext.siteConfigJson) as SitemapXmlSiteConfig;
	const {project} = localContext;
	const {
		authInfo: {
			principals = []
		} = {}
	} = getContext();
	return runInContext({
		branch: 'master',
		repository: `com.enonic.cms.${project}`,
		principals: principals || []
	}, () => fn(newSitemapBean(), __.toScriptValue(toSitemapParams({
		sitePath: site?._path || '',
		siteUrl: siteConfig?.overrideDomain || '',
		siteConfig: siteConfig || {},
	}))));
}

export const urlset = (_graphQL: GraphQL): Resolver<
	{
		count?: number
	},
	SiteMapResolverData,
	SitemapUrl[]
> => (env) => {
	const localContext = env.localContext as LocalContext<SiteMapResolverLocaleContext>;
	const siteConfig = JSON.parse(localContext.siteConfigJson) as SitemapXmlSiteConfig;
	const maxItems = toSitemapParams({sitePath: '', siteUrl: '', siteConfig: siteConfig || {}}).maxItems;
	const count = env.args.count && env.args.count > 0 ? Math.min(env.args.count, maxItems) : maxItems;
	return withSitemap(localContext, (bean, params) => JSON.parse(bean.guillotineUrlset(params, count)) as SitemapUrl[]);
};

export const files = (_graphQL: GraphQL): Resolver<
	{},
	SiteMapResolverData,
	SitemapFile[]
> => (env) => withSitemap(env.localContext as LocalContext<SiteMapResolverLocaleContext>,
	(bean, params) => JSON.parse(bean.guillotineFiles(params)) as SitemapFile[]);

export const file = (_graphQL: GraphQL): Resolver<
	{
		number: number
	},
	SiteMapResolverData,
	SitemapFile | null
> => (env) => withSitemap(env.localContext as LocalContext<SiteMapResolverLocaleContext>,
	(bean, params) => (JSON.parse(bean.guillotineFiles(params)) as SitemapFile[])
		.filter(({number}) => number === env.args.number)[0] || null);

export const fileUrlset = (_graphQL: GraphQL): Resolver<
	{},
	SitemapFile,
	SitemapUrl[]
> => (env) => withSitemap(env.localContext as LocalContext<SiteMapResolverLocaleContext>,
	(bean, params) => JSON.parse(bean.guillotineFile(params, env.source.number) || '[]') as SitemapUrl[]);

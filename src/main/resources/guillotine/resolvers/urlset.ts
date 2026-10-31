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


import {
	get as getContext,
	run as runInContext
} from '/lib/xp/context';
import {
	newSitemapBean,
	toSitemapParams,
} from '/lib/app-sitemapxml/sitemapBean';


interface SitemapUrl {
	changefreq?: tChangeFreq
	lastmod?: string
	priority?: tPriority
	path: string
}

// Served from the same cached, published-only sitemap as sitemap.xml.
export const urlset = (_graphQL: GraphQL): Resolver<
	{
		count?: number
	},
	SiteMapResolverLocaleContext,
	SiteMapResolverData,
	SitemapUrl[]
> => (env) => {
	const {
		args,
		localContext,
	} = env;
	const site = JSON.parse(localContext.siteJson as string) as Site<SitemapXmlSiteConfig>;
	const siteConfig = JSON.parse(localContext.siteConfigJson as string) as SitemapXmlSiteConfig;
	const params = toSitemapParams({
		sitePath: site._path,
		siteUrl: siteConfig?.overrideDomain || '',
		siteConfig: siteConfig || {},
	});
	const count = args.count && args.count > 0 ? Math.min(args.count, params.maxItems) : params.maxItems;

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
	}, () => JSON.parse(newSitemapBean().guillotineUrlset(__.toScriptValue(params), count)) as SitemapUrl[]);
};

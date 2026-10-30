import type {SiteConfig} from '@enonic-types/lib-content';
import type {
	GraphQL,
	Resolver,
} from '@enonic-types/guillotine';
import type {
	SiteMapResolverData,
	SiteMapResolverLocaleContext
} from '/guillotine/guillotine.d';
import type {SitemapXmlSiteConfig} from '/types';


import {getSite} from '/lib/xp/content';
import {forceArray} from '@enonic/js-utils/array/forceArray';
import {
	get as getContext,
	run as runInContext
} from '/lib/xp/context';
import {
	// DEBUG,
	TRACE,
} from '/constants';
import {SITEMAP_FIELD_NAME} from '/guillotine/constants';
import {resolveSourceConfig} from '/lib/app-sitemapxml/resolveSourceConfig';


export const sitemap = (graphQL: GraphQL): Resolver<
	{}, // args
	{}, // source
	ReturnType<GraphQL['createDataFetcherResult']>|null
> => (env) => {
	TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} env: ${JSON.stringify(env, null, 4)}`);
	const {
		// args,
		localContext,
		// source
	} = env;

	const {
		branch,
		project,
	} = localContext;
	const scopeKey = localContext.scopeKey ?? (localContext.siteKey as string | undefined);
	TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} scopeKey: ${scopeKey}`);

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
		const site = scopeKey && scopeKey !== '/'
			? getSite<SitemapXmlSiteConfig>({key: scopeKey})
			: null;
		TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} site: ${JSON.stringify(site, null, 4)}`);
		const sourceConfig = resolveSourceConfig(project, branch, scopeKey);
		const siteConfig = getAppConfig(sourceConfig);
		if (!siteConfig) {
			return null;
		}
		const {
			overrideDomain: baseUrl = null
		} = siteConfig || {};
		return graphQL.createDataFetcherResult({
			data: __.toScriptValue<SiteMapResolverData>({
				baseUrl,
			}),
			localContext: {
				siteJson: JSON.stringify(site),
				siteConfigJson: JSON.stringify(siteConfig),
			} satisfies SiteMapResolverLocaleContext,
			parentLocalContext: localContext,
		});
	}); // runInContext
};

function getAppConfig(sourceConfig: ReturnType<typeof resolveSourceConfig>) {
	return forceArray(sourceConfig as SiteConfig<SitemapXmlSiteConfig> | SiteConfig<SitemapXmlSiteConfig>[])
		.filter(config => config && config.applicationKey === app.name)[0]?.config;
}

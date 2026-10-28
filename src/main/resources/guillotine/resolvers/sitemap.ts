import type {Site} from '@enonic-types/lib-content';
import type {
	GraphQL,
	Resolver,
} from '@enonic-types/guillotine';
import type {
	SiteMapResolverData,
	SiteMapResolverLocaleContext
} from '/guillotine/guillotine.d';
import type {SitemapXmlSiteConfig} from '/types';


import {get as getContentByKey} from '/lib/xp/content';
import {
	get as getContext,
	run as runInContext
} from '/lib/xp/context';
import {
	// DEBUG,
	TRACE,
} from '/constants';
import {SITEMAP_FIELD_NAME} from '/guillotine/constants';
import {getSiteConfigFromSite} from '/guillotine/getSiteConfigFromSite';


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
		scopeKey // Can be undefined when no scope is selected
	} = localContext;
	TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} scopeKey: ${scopeKey}`);
	if (!scopeKey) {
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
		const site = getContentByKey<Site<SitemapXmlSiteConfig>>({key: scopeKey});
		TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} site: ${JSON.stringify(site, null, 4)}`);
		const siteConfig = getSiteConfigFromSite({
			applicationKey: app.name,
			site: site as Site<SitemapXmlSiteConfig>,
		});
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

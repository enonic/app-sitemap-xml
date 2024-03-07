import type {Site} from '@enonic-types/lib-content';
import type {
	DataFetcherResult,
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
	{}, // localContext
	Site<SitemapXmlSiteConfig>,
	DataFetcherResult<SiteMapResolverData,SiteMapResolverLocaleContext>|null
> => (env) => {
	TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} env: ${JSON.stringify(env, null, 4)}`);
	const {
		// args,
		localContext,
		source: siteWithoutSiteConfig
	} = env;
	TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} siteWithoutSiteConfig: ${JSON.stringify(siteWithoutSiteConfig, null, 4)}`);

	const {
		branch,
		project,
		// siteKey // NOTE: Can be undefined when x-guillotine-sitekey is missing
	} = localContext;
	// TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} siteKey: ${siteKey}`);
	// if (!siteKey) {
	// 	return null;
	// }
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
		const site = getContentByKey<Site<SitemapXmlSiteConfig>>({key: siteWithoutSiteConfig._path});
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
		return graphQL.createDataFetcherResult<
			SiteMapResolverData,
			SiteMapResolverLocaleContext
		>({
			data: __.toScriptValue<SiteMapResolverData>({
				baseUrl,
			}),
			localContext: {
				siteJson: JSON.stringify(site),
				siteConfigJson: JSON.stringify(siteConfig),
			},
			parentLocalContext: localContext,
		});
	}); // runInContext
};

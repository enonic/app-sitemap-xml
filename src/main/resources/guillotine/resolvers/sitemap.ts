import type {Site, SiteConfig} from '@enonic-types/lib-content';
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


import {forceArray} from '@enonic/js-utils/array/forceArray';
import {get as getContentByKey} from '/lib/xp/content';
import {
	get as getContext,
	run as runInContext
} from '/lib/xp/context';
import {get as getProject} from '/lib/xp/project';
import {
	// DEBUG,
	TRACE,
} from '/constants';
import {SITEMAP_FIELD_NAME} from '/guillotine/constants';
import {getSiteConfigFromSite} from '/guillotine/getSiteConfigFromSite';


export const sitemap = (graphQL: GraphQL): Resolver<
	{}, // args
	{}, // localContext
	{},
	DataFetcherResult<SiteMapResolverData,SiteMapResolverLocaleContext>|null
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
		siteKey // NOTE: Can be undefined when x-guillotine-sitekey is missing
	} = localContext;
	TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} siteKey: ${siteKey}`);
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
		const site = siteKey ? getContentByKey<Site<SitemapXmlSiteConfig>>({key: siteKey}) : null;
		TRACE && log.debug(`resolvers ${SITEMAP_FIELD_NAME} site: ${JSON.stringify(site, null, 4)}`);
		if (siteKey && !site) {
			return null;
		}
		const siteConfig = site
			? getSiteConfigFromSite({
				applicationKey: app.name,
				site: site as Site<SitemapXmlSiteConfig>,
			})
			: getProjectConfig(project);
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

function getProjectConfig(project?: string): SitemapXmlSiteConfig | undefined {
	if (!project) {
		return undefined;
	}
	const projectEntity = runInContext({principals: ['role:system.admin']}, () => getProject({id: project}));
	const configs = forceArray(projectEntity?.siteConfig || []) as unknown as SiteConfig<SitemapXmlSiteConfig>[];
	return configs.filter(config => config && config.applicationKey === app.name)[0]?.config;
}

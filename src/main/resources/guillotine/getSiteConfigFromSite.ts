import type {Site} from '@enonic-types/lib-content';
import type {SitemapXmlSiteConfig} from '/types';


import {forceArray} from '@enonic/js-utils/array/forceArray';


// Find the site config even when the context is not known.
export const getSiteConfigFromSite = ({
	applicationKey, // Avoid app.name so it can be used in Guillotine Extension Context
	site
}: {
	applicationKey: string
	site: Site<SitemapXmlSiteConfig>,
}
) => {
	// Code courtesy of PVMerlo at Enonic Discuss - https://discuss.enonic.com/u/PVMerlo
	if (site?.data?.siteConfig) {
		const siteConfigs = forceArray(site.data.siteConfig);
		let siteConfig: Partial<typeof siteConfigs[0]> = {};
		siteConfigs.forEach((cfg) => {
			if (cfg.applicationKey == applicationKey) {
				siteConfig = cfg;
			}
		});
		return siteConfig.config;
	}
};

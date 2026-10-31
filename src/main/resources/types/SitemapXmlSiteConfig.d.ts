import type {
	tChangeFreq,
	tPriority,
} from './Sitemap.d';


export declare interface SitemapXmlSiteConfig {
	ignoreList?: {
		path?: string
	}[]
	maxItems?: string
	overrideDomain?: string
	urlsPerFile?: string
	siteMap: {
		contentType?: string
		updatePeriod?: tChangeFreq
		priority?: tPriority
	}[]
}

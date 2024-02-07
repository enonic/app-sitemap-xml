export declare interface SitemapXmlSiteConfig {
	ignoreList?: {
		path?: string
	}[]
	maxItems?: number
	overrideDomain?: string
	siteMap: {
		contentType?: string
		updatePeriod?: 'always' | 'hourly' | 'daily' | 'weekly' | 'monthly' | 'yearly' | 'never'
		priority?: '0.0' | '0.1' | '0.2' | '0.3' | '0.4' | '0.5' | '0.6' | '0.7' | '0.8' | '0.9' | '1.0'
	}[]
}

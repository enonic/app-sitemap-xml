import type {Site} from '@enonic-types/lib-content';
import type {
	Request,
	Response,
	SitemapXmlSiteConfig,
	tUrl,
} from '/types';


import {toStr} from '@enonic/js-utils/value/toStr';
import {
	getSite,
	getSiteConfig,
	pageUrl
} from '/lib/xp/portal';
// @ts-expect-error No types yet.
import {render} from '/lib/xslt';
import {queryForSitemapContent} from '/lib/app-sitemapxml/queryForSitemapContent';


const DEBUG = false;
const TRACE = false;
const VIEW = resolve('sitemap.xsl');


function getServerPageUrl(path: string, overrideDomain: string) {
	return overrideDomain + pageUrl({
		path,
		type: 'server'
	})
}

function getAbsolutePageUrl(path: string) {
	return pageUrl({
		path,
		type: 'absolute'
	})
}

export function get(request: Request<{
	headers: {
		'Content-Type'?: string
	}
}>): Response {
	TRACE && log.debug('Sitemap request:%s', toStr(request));

	// This controller runs in site context, so site can't be null.
	const site = getSite<SitemapXmlSiteConfig>() as Site<SitemapXmlSiteConfig>;
	TRACE && log.debug('site:%s', toStr(site));

	const siteConfig = getSiteConfig<SitemapXmlSiteConfig>();
	DEBUG && log.debug('siteConfig:%s', toStr(siteConfig));

	const {
		maxItems = 10000,
		overrideDomain = '',
	} = siteConfig || {}; // Handle null (aka no config)

	const {
		changefreq,
		priority,
		result
	} = queryForSitemapContent({
		count: parseInt(maxItems as string, 10),
		site,
		siteConfig,
	});

	// Go through the results and add the corresponding settings for each match.
	const items: tUrl[] = [];
	for (let i = 0; i < result.hits.length; i++) {
		if (result.hits[i].type) {
			items.push({
				changefreq: changefreq[result.hits[i].type],
				lastmod: result.hits[i].modifiedTime,
				priority: priority[result.hits[i].type],
				loc: overrideDomain
					? getServerPageUrl(result.hits[i]._path, overrideDomain)
					: getAbsolutePageUrl(result.hits[i]._path)
			});
		}
	}

	if (request.headers['Content-Type'] === 'application/json') {
		return {
			body: JSON.stringify(items),
			contentType: 'application/json',
		}
	}

	return {
		body: render(VIEW, {
			result: items
		}),
		contentType: 'text/xml',
	};
}

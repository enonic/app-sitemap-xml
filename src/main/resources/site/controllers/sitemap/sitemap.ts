import type {Site} from '@enonic-types/lib-content';
import type {
	Request,
	Response,
	SitemapXmlSiteConfig,
} from '/types';


import {
	getSite,
	getSiteConfig,
	pageUrl
} from '/lib/xp/portal';
import {acceptsGzip} from '/lib/app-sitemapxml/acceptEncoding';
import {
	newSitemapBean,
	toSitemapParams,
} from '/lib/app-sitemapxml/sitemapBean';


const PATH_REGEXP = /\/sitemap(?:-(\d+))?\.xml(\.gz)?$/;


function getSiteUrl(sitePath: string, overrideDomain: string): string {
	return overrideDomain
		? overrideDomain + pageUrl({path: sitePath, type: 'server'})
		: pageUrl({path: sitePath, type: 'absolute'});
}

export function get(request: Request<{
	headers: {
		'Content-Type'?: string
	}
}>): Response {
	// This controller runs in site context, so site can't be null.
	const site = getSite<SitemapXmlSiteConfig>() as Site<SitemapXmlSiteConfig>;
	const siteConfig = getSiteConfig<SitemapXmlSiteConfig>() || {} as Partial<SitemapXmlSiteConfig>;

	const {overrideDomain = ''} = siteConfig;

	const params = toSitemapParams({
		sitePath: site._path,
		siteUrl: getSiteUrl(site._path, overrideDomain),
		siteConfig,
	});

	const bean = newSitemapBean();
	const scriptParams = __.toScriptValue(params);

	const match = PATH_REGEXP.exec(request.path);
	const fileNumber = match && match[1] ? parseInt(match[1], 10) : 0;
	const isGzip = !!(match && match[2]);
	const isJson = !isGzip && request.headers['Content-Type'] === 'application/json';
	// Files are stored gzip-compressed, so a plain .xml request that accepts gzip gets the stored file as is.
	const isEncoded = !isGzip && !isJson && acceptsGzip(request.headers);
	const result = isJson
		? bean.json(scriptParams)
		: isGzip
			? fileNumber ? bean.fileGzip(scriptParams, fileNumber) : bean.rootGzip(scriptParams)
			: isEncoded
				? fileNumber ? bean.fileGzip(scriptParams, fileNumber) : bean.rootEncoded(scriptParams)
				: fileNumber ? bean.file(scriptParams, fileNumber) : bean.root(scriptParams);

	const status = result.getStatus();
	if (status === 429) {
		// A body is needed, otherwise XP renders its own error page without the Retry-After header.
		return {
			body: 'Too many sitemap requests in progress, retry later.',
			contentType: 'text/plain',
			headers: {'Retry-After': String(result.getRetryAfter())},
			status,
		};
	}
	if (status !== 200) {
		return {
			body: null,
			status,
		};
	}
	if (isJson || isGzip) {
		return {
			body: result.getBody(),
			contentType: isJson ? 'application/json; charset=utf-8' : 'application/gzip',
		};
	}
	return {
		body: result.getBody(),
		contentType: 'text/xml; charset=utf-8',
		headers: isEncoded
			? {'Content-Encoding': 'gzip', Vary: 'Accept-Encoding'}
			: {Vary: 'Accept-Encoding'},
	};
}

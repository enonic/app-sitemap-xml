import type {Site} from '@enonic-types/lib-content';
import type {
	Request,
	Response,
	SitemapXmlSiteConfig,
} from '/types';


import {forceArray} from '@enonic/js-utils/array/forceArray';
import {
	getSite,
	getSiteConfig,
	pageUrl
} from '/lib/xp/portal';
import {
	DEFAULT_MAX_ITEMS,
	MAX_ITEMS_LIMIT,
	MAX_URLS_PER_FILE,
} from '/lib/app-sitemapxml/constants';


interface SitemapParams {
	sitePath: string
	siteUrl: string
	contentTypes: {
		contentType: string
		changefreq: string
		priority: string
	}[]
	ignorePatterns: string[]
	maxItems: number
	urlsPerFile: number
}

interface SitemapResponse {
	getStatus(): number
	getBody(): object | null
	getRetryAfter(): number
}

interface SitemapBean {
	root(params: ScriptValue): SitemapResponse
	file(params: ScriptValue, number: number): SitemapResponse
	json(params: ScriptValue): SitemapResponse
	rootGzip(params: ScriptValue): SitemapResponse
	fileGzip(params: ScriptValue, number: number): SitemapResponse
}

const PATH_REGEXP = /\/sitemap(?:-(\d+))?\.xml(\.gz)?$/;


function toInt(value: string | undefined, defaultValue: number, max: number): number {
	const parsed = parseInt(value || '', 10);
	if (isNaN(parsed) || parsed < 1) {
		return defaultValue;
	}
	return Math.min(parsed, max);
}

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

	const {
		ignoreList,
		maxItems,
		overrideDomain = '',
		siteMap,
		urlsPerFile,
	} = siteConfig;

	const params: SitemapParams = {
		sitePath: site._path,
		siteUrl: getSiteUrl(site._path, overrideDomain),
		contentTypes: (siteMap ? forceArray(siteMap) : []).map(({contentType, updatePeriod, priority}) => ({
			contentType: contentType || '',
			changefreq: updatePeriod || '',
			priority: priority || '',
		})),
		ignorePatterns: (ignoreList ? forceArray(ignoreList) : []).map(({path}) => path || ''),
		maxItems: toInt(maxItems, DEFAULT_MAX_ITEMS, MAX_ITEMS_LIMIT),
		urlsPerFile: toInt(urlsPerFile, MAX_URLS_PER_FILE, MAX_URLS_PER_FILE),
	};

	const bean = __.newBean<SitemapBean>('com.enonic.app.sitemapxml.SitemapBean');
	const scriptParams = __.toScriptValue(params);

	const match = PATH_REGEXP.exec(request.path);
	const fileNumber = match && match[1] ? parseInt(match[1], 10) : 0;
	const isGzip = !!(match && match[2]);
	const isJson = !isGzip && request.headers['Content-Type'] === 'application/json';
	const result = isJson
		? bean.json(scriptParams)
		: isGzip
			? fileNumber ? bean.fileGzip(scriptParams, fileNumber) : bean.rootGzip(scriptParams)
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
	return {
		body: result.getBody(),
		contentType: isJson ? 'application/json; charset=utf-8' : isGzip ? 'application/gzip' : 'text/xml; charset=utf-8',
	};
}

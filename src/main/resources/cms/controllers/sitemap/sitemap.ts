import type {ScriptValue} from '@enonic-types/core';
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

interface SitemapBean {
	root(params: ScriptValue): string
	file(params: ScriptValue, number: number): string | null
	json(params: ScriptValue): string
}

const FILE_PATH_REGEXP = /\/sitemap-(\d+)\.xml$/;


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

	if (request.headers['Content-Type'] === 'application/json') {
		return {
			body: bean.json(scriptParams),
			contentType: 'application/json',
		};
	}

	const fileMatch = FILE_PATH_REGEXP.exec(request.path);
	if (fileMatch) {
		const body = bean.file(scriptParams, parseInt(fileMatch[1], 10));
		if (body === null) {
			return {
				body: null,
				status: 404,
			};
		}
		return {
			body,
			contentType: 'text/xml',
		};
	}

	return {
		body: bean.root(scriptParams),
		contentType: 'text/xml',
	};
}

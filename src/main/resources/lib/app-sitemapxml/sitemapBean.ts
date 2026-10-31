import type {ScriptValue} from '@enonic-types/core';
import type {SitemapXmlSiteConfig} from '/types';


import {forceArray} from '@enonic/js-utils/array/forceArray';
import {
	DEFAULT_MAX_ITEMS,
	MAX_ITEMS_LIMIT,
	MAX_URLS_PER_FILE,
} from '/lib/app-sitemapxml/constants';


export interface SitemapParams {
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
	preferBaseUrl?: boolean
}

export interface SitemapResponse {
	getStatus(): number
	getBody(): object | null
	getRetryAfter(): number
}

export interface SitemapBean {
	root(params: ScriptValue): SitemapResponse
	file(params: ScriptValue, number: number): SitemapResponse
	json(params: ScriptValue): SitemapResponse
	rootGzip(params: ScriptValue): SitemapResponse
	rootEncoded(params: ScriptValue): SitemapResponse
	fileGzip(params: ScriptValue, number: number): SitemapResponse
	guillotineFiles(params: ScriptValue): string
	guillotineUrlset(params: ScriptValue, count: number): string
	guillotineFile(params: ScriptValue, number: number): string | null
}

export function toInt(value: string | number | undefined, defaultValue: number, max: number): number {
	const parsed = typeof value === 'number' ? value : parseInt(value || '', 10);
	if (isNaN(parsed) || parsed < 1) {
		return defaultValue;
	}
	return Math.min(parsed, max);
}

export function toSitemapParams({
	sitePath,
	siteUrl,
	siteConfig,
}: {
	sitePath: string
	siteUrl: string
	siteConfig: Partial<SitemapXmlSiteConfig>
}): SitemapParams {
	const {
		ignoreList,
		maxItems,
		siteMap,
		urlsPerFile,
	} = siteConfig;
	return {
		sitePath,
		siteUrl,
		contentTypes: (siteMap ? forceArray(siteMap) : []).map(({contentType, updatePeriod, priority}) => ({
			contentType: contentType || '',
			changefreq: updatePeriod || '',
			priority: priority || '',
		})),
		ignorePatterns: (ignoreList ? forceArray(ignoreList) : []).map(({path}) => path || ''),
		maxItems: toInt(maxItems, DEFAULT_MAX_ITEMS, MAX_ITEMS_LIMIT),
		urlsPerFile: toInt(urlsPerFile, MAX_URLS_PER_FILE, MAX_URLS_PER_FILE),
	};
}

export function newSitemapBean(): SitemapBean {
	return __.newBean<SitemapBean>('com.enonic.app.sitemapxml.SitemapBean');
}

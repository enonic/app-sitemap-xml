import type {ScriptValue} from '@enonic-types/core';
import type {SitemapXmlSiteConfig} from '/types';


import {forceArray} from '@enonic/js-utils/array/forceArray';


export interface SitemapParams {
	sitePath: string
	siteUrl: string
	contentTypes: {
		contentType: string
		changefreq: string
		priority: string
	}[]
	ignorePatterns: string[]
	// True when siteUrl is taken from the request.
	siteUrlFromRequest?: boolean
}

export interface SitemapResponse {
	getStatus(): number
	getBody(): object | null
	getRetryAfter(): number
}

export interface SitemapBean {
	root(params: ScriptValue): SitemapResponse
	file(params: ScriptValue, number: number): SitemapResponse
	rootGzip(params: ScriptValue): SitemapResponse
	rootEncoded(params: ScriptValue): SitemapResponse
	fileGzip(params: ScriptValue, number: number): SitemapResponse
	guillotineFiles(params: ScriptValue): string
	guillotineUrlset(params: ScriptValue, count: number): string
	guillotineFile(params: ScriptValue, number: number): string | null
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
		siteMap,
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
	};
}

export function newSitemapBean(): SitemapBean {
	return __.newBean<SitemapBean>('com.enonic.app.sitemapxml.SitemapBean');
}

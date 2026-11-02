/// <reference types="@enonic-types/global"/>
import type {Request} from '@enonic-types/core';

import {
	beforeEach,
	describe,
	expect,
	jest,
	test as it,
} from '@jest/globals';


const site = {_path: '/mysite'};
let siteConfig: Record<string, unknown> | null = null;

const pageUrl = jest.fn(({path, type}: {path: string, type: string}) =>
	type === 'absolute' ? `https://example.com${path}` : path);

jest.mock('/lib/xp/portal', () => ({
	getSite: () => site,
	getSiteConfig: () => siteConfig,
	pageUrl,
}), {virtual: true});

function response(status: number, body: string | object | null, etag: string | null = null) {
	return {
		getStatus: () => status,
		getBody: () => body,
		getRetryAfter: () => (status === 429 ? 10 : 0),
		getETag: () => etag,
	};
}

const bean = {
	root: jest.fn((_params: unknown) => response(200, '<urlset/>')),
	file: jest.fn((_params: unknown, number: number) => (number === 2 ? response(200, '<urlset n="2"/>') : response(404, null))),
	rootGzip: jest.fn((_params: unknown) => response(200, 'gzipped root')),
	rootEncoded: jest.fn((_params: unknown) => response(200, 'encoded root')),
	fileGzip: jest.fn((_params: unknown, number: number) => (number === 2 ? response(200, 'gzipped 2') : response(404, null))),
};

// @ts-ignore TS2339: Property '__' does not exist on type 'typeof globalThis'.
globalThis.__ = {
	newBean: () => bean,
	toScriptValue: (value: unknown) => value,
};

function request(path: string, headers: Record<string, string> = {}): Request {
	return {
		branch: 'master',
		cookies: {},
		getHeader: (name: string) => {
			const key = Object.keys(headers).filter((header) => header.toLowerCase() === name.toLowerCase())[0];
			return key ? headers[key] : null;
		},
		headers,
		host: 'example.com',
		method: 'GET',
		mode: 'live',
		params: {},
		path,
		port: 443,
		rawPath: path,
		scheme: 'https',
		url: `https://example.com${path}`,
	} as unknown as Request;
}

describe('sitemap controller', () => {
	beforeEach(() => {
		siteConfig = null;
		jest.clearAllMocks();
	});

	it('renders the root sitemap with defaults', async () => {
		const {get} = await import('/cms/controllers/sitemap/sitemap');

		const res = get(request('/mysite/sitemap.xml'));

		expect(res).toEqual({body: '<urlset/>', contentType: 'text/xml; charset=utf-8', headers: {Vary: 'Accept-Encoding'}});
		expect(bean.root).toHaveBeenCalledWith({
			sitePath: '/mysite',
			siteUrl: 'https://example.com/mysite',
			contentTypes: [],
			ignorePatterns: [],
			siteUrlFromRequest: true,
		});
	});

	it('passes the site configuration', async () => {
		siteConfig = {
			ignoreList: {path: '/skip'},
			overrideDomain: 'https://override.example.org',
			siteMap: [{contentType: 'base:folder', updatePeriod: 'daily', priority: '0.7'}, {}],
		};
		const {get} = await import('/cms/controllers/sitemap/sitemap');

		get(request('/mysite/sitemap.xml'));

		expect(bean.root).toHaveBeenCalledWith({
			sitePath: '/mysite',
			siteUrl: 'https://override.example.org/mysite',
			contentTypes: [
				{contentType: 'base:folder', changefreq: 'daily', priority: '0.7'},
				{contentType: '', changefreq: '', priority: ''},
			],
			ignorePatterns: ['/skip'],
			siteUrlFromRequest: false,
		});
	});

	it('serves numbered files and 404 for unknown ones', async () => {
		const {get} = await import('/cms/controllers/sitemap/sitemap');

		expect(get(request('/mysite/sitemap-2.xml'))).toEqual({body: '<urlset n="2"/>', contentType: 'text/xml; charset=utf-8', headers: {Vary: 'Accept-Encoding'}});
		expect(get(request('/mysite/sitemap-3.xml'))).toEqual({body: null, status: 404});
		expect(get(request('/mysite/sitemap-0.xml'))).toEqual({body: null, status: 404});
		expect(get(request('/mysite/sitemap-0.xml.gz'))).toEqual({body: null, status: 404});
		expect(get(request('/mysite/sitemap-99999999999.xml'))).toEqual({body: null, status: 404});
		expect(bean.file).toHaveBeenCalledWith(expect.anything(), 3);
	});

	it('sends the entity tag and answers 304 to a matching If-None-Match', async () => {
		const {get} = await import('/cms/controllers/sitemap/sitemap');
		bean.root.mockReturnValueOnce(response(200, '<urlset/>', '"abc"'));
		expect(get(request('/mysite/sitemap.xml'))).toEqual({
			body: '<urlset/>',
			contentType: 'text/xml; charset=utf-8',
			headers: {ETag: '"abc"', Vary: 'Accept-Encoding'},
		});

		bean.root.mockReturnValueOnce(response(304, null, '"abc"'));
		expect(get(request('/mysite/sitemap.xml', {'If-None-Match': '"abc"'}))).toEqual({
			body: null,
			headers: {ETag: '"abc"', Vary: 'Accept-Encoding'},
			status: 304,
		});
		expect(bean.root).toHaveBeenLastCalledWith(expect.objectContaining({ifNoneMatch: '"abc"'}));

		bean.rootGzip.mockReturnValueOnce(response(200, 'gzipped root', 'W/"abc-gzip"'));
		expect(get(request('/mysite/sitemap.xml.gz'))).toEqual({
			body: 'gzipped root',
			contentType: 'application/gzip',
			headers: {ETag: 'W/"abc-gzip"'},
		});
	});

	it('serves gzipped sitemaps', async () => {
		const {get} = await import('/cms/controllers/sitemap/sitemap');

		expect(get(request('/mysite/sitemap.xml.gz'))).toEqual({body: 'gzipped root', contentType: 'application/gzip'});
		expect(get(request('/mysite/sitemap-2.xml.gz'))).toEqual({body: 'gzipped 2', contentType: 'application/gzip'});
		expect(get(request('/mysite/sitemap-3.xml.gz'))).toEqual({body: null, status: 404});
	});

	it('sends stored gzip files with Content-Encoding when the client accepts gzip', async () => {
		const {get} = await import('/cms/controllers/sitemap/sitemap');
		const encoded = {Vary: 'Accept-Encoding', 'Content-Encoding': 'gzip'};

		expect(get(request('/mysite/sitemap.xml', {'Accept-Encoding': 'gzip, deflate, br'})))
			.toEqual({body: 'encoded root', contentType: 'text/xml; charset=utf-8', headers: encoded});
		expect(get(request('/mysite/sitemap-2.xml', {'accept-encoding': 'gzip'})))
			.toEqual({body: 'gzipped 2', contentType: 'text/xml; charset=utf-8', headers: encoded});
		expect(get(request('/mysite/sitemap-3.xml', {'accept-encoding': 'gzip'}))).toEqual({body: null, status: 404});
		expect(get(request('/mysite/sitemap.xml', {'Accept-Encoding': 'br, gzip;q=0'})))
			.toEqual({body: '<urlset/>', contentType: 'text/xml; charset=utf-8', headers: {Vary: 'Accept-Encoding'}});
		expect(get(request('/mysite/sitemap.xml.gz', {'Accept-Encoding': 'gzip'})))
			.toEqual({body: 'gzipped root', contentType: 'application/gzip'});
		expect(bean.rootGzip).toHaveBeenCalledTimes(1);
	});

	it('answers 429 with Retry-After when too many requests are in progress', async () => {
		bean.root.mockReturnValueOnce(response(429, null));
		const {get} = await import('/cms/controllers/sitemap/sitemap');

		expect(get(request('/mysite/sitemap.xml'))).toEqual({
			body: 'Too many sitemap requests in progress, retry later.',
			contentType: 'text/plain',
			headers: {'Retry-After': '10'},
			status: 429,
		});
	});
});

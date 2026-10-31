/// <reference types="@enonic-types/global"/>

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

function response(status: number, body: string | null) {
	return {
		getStatus: () => status,
		getBody: () => body,
		getRetryAfter: () => (status === 503 ? 10 : 0),
	};
}

const bean = {
	root: jest.fn((_params: unknown) => response(200, '<urlset/>')),
	file: jest.fn((_params: unknown, number: number) => (number === 2 ? response(200, '<urlset n="2"/>') : response(404, null))),
	json: jest.fn((_params: unknown) => response(200, '[]')),
};

// @ts-ignore TS2339: Property '__' does not exist on type 'typeof globalThis'.
globalThis.__ = {
	newBean: () => bean,
	toScriptValue: (value: unknown) => value,
};

function request(path: string, headers: Record<string, string> = {}) {
	return {
		branch: 'master',
		headers,
		host: 'example.com',
		method: 'GET',
		mode: 'live',
		path,
		port: 443,
		scheme: 'https',
		url: `https://example.com${path}`,
	} as const;
}

describe('sitemap controller', () => {
	beforeEach(() => {
		siteConfig = null;
		jest.clearAllMocks();
	});

	it('renders the root sitemap with defaults', async () => {
		const {get} = await import('/site/controllers/sitemap/sitemap');

		const res = get(request('/mysite/sitemap.xml'));

		expect(res).toEqual({body: '<urlset/>', contentType: 'text/xml'});
		expect(bean.root).toHaveBeenCalledWith({
			sitePath: '/mysite',
			siteUrl: 'https://example.com/mysite',
			contentTypes: [],
			ignorePatterns: [],
			maxItems: 10000,
			urlsPerFile: 50000,
		});
	});

	it('passes the site configuration', async () => {
		siteConfig = {
			ignoreList: {path: '/skip'},
			maxItems: '2000000',
			overrideDomain: 'https://override.example.org',
			siteMap: [{contentType: 'base:folder', updatePeriod: 'daily', priority: '0.7'}, {}],
			urlsPerFile: 'abc',
		};
		const {get} = await import('/site/controllers/sitemap/sitemap');

		get(request('/mysite/sitemap.xml'));

		expect(bean.root).toHaveBeenCalledWith({
			sitePath: '/mysite',
			siteUrl: 'https://override.example.org/mysite',
			contentTypes: [
				{contentType: 'base:folder', changefreq: 'daily', priority: '0.7'},
				{contentType: '', changefreq: '', priority: ''},
			],
			ignorePatterns: ['/skip'],
			maxItems: 1000000,
			urlsPerFile: 50000,
		});
	});

	it('serves numbered files and 404 for unknown ones', async () => {
		const {get} = await import('/site/controllers/sitemap/sitemap');

		expect(get(request('/mysite/sitemap-2.xml'))).toEqual({body: '<urlset n="2"/>', contentType: 'text/xml'});
		expect(get(request('/mysite/sitemap-3.xml'))).toEqual({body: null, status: 404});
		expect(bean.file).toHaveBeenCalledWith(expect.anything(), 3);
	});

	it('serves json when asked for', async () => {
		const {get} = await import('/site/controllers/sitemap/sitemap');

		expect(get(request('/mysite/sitemap.xml', {'Content-Type': 'application/json'})))
			.toEqual({body: '[]', contentType: 'application/json'});
	});

	it('answers 503 with Retry-After when busy', async () => {
		bean.root.mockReturnValueOnce(response(503, null));
		const {get} = await import('/site/controllers/sitemap/sitemap');

		expect(get(request('/mysite/sitemap.xml'))).toEqual({
			body: 'Too many sitemap requests in progress, retry later.',
			contentType: 'text/plain',
			headers: {'Retry-After': '10'},
			status: 503,
		});
	});
});

import {
	describe,
	expect,
	test as it,
} from '@jest/globals';
import {acceptsGzip} from '/lib/app-sitemapxml/acceptEncoding';


describe('acceptsGzip', () => {
	it.each([
		[undefined, false],
		[null, false],
		['', false],
		['identity', false],
		['gzip', true],
		['GZIP', true],
		['gzip, deflate, br', true],
		['x-gzip', true],
		['br;q=1.0, gzip;q=0.8, *;q=0.1', true],
		['gzip;q=0', false],
		['gzip; q=0.0, *', false],
		['*', true],
		['*;q=0', false],
		['br, *;q=0', false],
	])('%j accepts gzip: %s', (value: string | null | undefined, expected: boolean) => {
		expect(acceptsGzip(value)).toBe(expected);
	});
});

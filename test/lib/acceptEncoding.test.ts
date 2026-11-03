import {
	describe,
	expect,
	test as it,
} from '@jest/globals';
import {acceptsGzip} from '/lib/app-sitemapxml/acceptEncoding';


describe('acceptsGzip', () => {
	it.each([
		[{}, false],
		[{'Accept-Encoding': ''}, false],
		[{'Accept-Encoding': 'identity'}, false],
		[{'Accept-Encoding': 'gzip'}, true],
		[{'accept-encoding': 'GZIP'}, true],
		[{'Accept-Encoding': 'gzip, deflate, br'}, true],
		[{'Accept-Encoding': 'x-gzip'}, true],
		[{'Accept-Encoding': 'br;q=1.0, gzip;q=0.8, *;q=0.1'}, true],
		[{'Accept-Encoding': 'gzip;q=0'}, false],
		[{'Accept-Encoding': 'gzip; q=0.0, *'}, false],
		[{'Accept-Encoding': '*'}, true],
		[{'Accept-Encoding': '*;q=0'}, false],
		[{'Accept-Encoding': 'br, *;q=0'}, false],
	])('%j accepts gzip: %s', (headers: Record<string, string>, expected: boolean) => {
		expect(acceptsGzip(headers)).toBe(expected);
	});
});

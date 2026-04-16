import type {
	attachmentUrl,
	pageUrl,
} from '/lib/xp/portal';


import {jest} from '@jest/globals';


/**
 * Minimal portal mocks so resolvers that call {@link pageUrl} / {@link attachmentUrl} run in Jest.
 */
export function mockLibXpPortal() {
	jest.mock(
		'/lib/xp/portal',
		() => ({
			attachmentUrl: jest.fn<typeof attachmentUrl>().mockImplementation((params) => {
				const path = params.path || '';
				const name = params.name ? `/${params.name}` : '';
				const suffix = `${path}${name}`;
				if (params.type === 'absolute') {
					return `https://example.invalid/_/attachment${suffix}`;
				}
				return `/_/attachment${suffix}`;
			}),
			getSite: jest.fn(),
			getSiteConfig: jest.fn(),
			pageUrl: jest.fn<typeof pageUrl>().mockImplementation((params) => {
				const path = params.path || '';
				if (params.type === 'absolute') {
					return `https://example.invalid${path}`;
				}
				return path;
			}),
		}),
		{virtual: true}
	);
}

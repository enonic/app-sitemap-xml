import type {
	Content,
	Site,
	get,
	getSite
} from '/lib/xp/content';
import type {SitemapXmlSiteConfig} from '/types';


import {jest} from '@jest/globals';


export function mockLibXpContent({
	contents = {},
	siteContent = {} as Site<SitemapXmlSiteConfig>
} :{
	contents?: Record<string, Content<unknown>>
	siteContent?: Site<SitemapXmlSiteConfig>
} = {
}) {
	jest.mock(
		'/lib/xp/content',
		() => ({
			// @ts-ignore
			get: jest.fn<typeof get>().mockImplementation((getContentParams) => {
				const {
					key,
					// versionId
				} = getContentParams;
				const content = contents[key];
				if (content) {
					return content;
				}
				console.error(`No content found for key: ${key}`);
				return null;
			}),
			// @ts-ignore
			getSite: jest.fn<typeof getSite>().mockReturnValue(siteContent),
			getOutboundDependencies: jest.fn().mockReturnValue([]),
			query: jest.fn().mockReturnValue({
				count: contents.length,
				hits: Object.values(contents),
				total: contents.length,
			})
		}),
		{virtual: true}
	);
}

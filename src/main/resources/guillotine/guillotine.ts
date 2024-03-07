import type {
	Extensions,
	GraphQL,
} from '@enonic-types/guillotine';


import {ObjectTypeName} from '@enonic-types/guillotine';
import {
	SITEMAP_FIELD_NAME,
	URLSET_FIELD_NAME,
	GraphQLTypeName,
} from '/guillotine/constants';
import {Sitemap} from './types/Sitemap';
import {SitemapUrl} from './types/SitemapUrl';
import {sitemap} from './resolvers/sitemap';
import {urlset} from '/guillotine/resolvers/urlset';


export const extensions = (graphQL: GraphQL): Extensions => ({
	types: {
		[GraphQLTypeName.SITEMAP]: Sitemap(graphQL),
		[GraphQLTypeName.SITEMAP_URL]: SitemapUrl(graphQL),
	},
	creationCallbacks: {
		[ObjectTypeName.HeadlessCms]: (params) => {
			params.addFields({
				[SITEMAP_FIELD_NAME]: {
					type: graphQL.reference(GraphQLTypeName.SITEMAP),
				},
			});
		},
	},
	resolvers: {
		[ObjectTypeName.HeadlessCms]: {
			[SITEMAP_FIELD_NAME]: sitemap(graphQL),
		},
		[GraphQLTypeName.SITEMAP]: {
			[URLSET_FIELD_NAME]: urlset(graphQL),
		},
	}
});

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
import {buildObjectTypeSiteMap} from '/guillotine/types/buildObjectTypeSiteMap';
import {buildObjectTypeSiteMapUrl} from '/guillotine/types/buildObjectTypeSiteMapUrl';
import {buildSitemapResolver} from './resolvers/sitemap';
import {urlset} from '/guillotine/resolvers/urlset';


export const extensions = (graphQL: GraphQL): Extensions => ({
	types: {
		[GraphQLTypeName.SITEMAP]: buildObjectTypeSiteMap(graphQL),
		[GraphQLTypeName.SITEMAP_URL]: buildObjectTypeSiteMapUrl(graphQL),
	},
	creationCallbacks: {
		[ObjectTypeName.portal_Site]: (params) => {
			params.addFields({
				[SITEMAP_FIELD_NAME]: {
					type: graphQL.reference(GraphQLTypeName.SITEMAP),
				},
			});
		},
		[GraphQLTypeName.SITEMAP]: (params) => {
			params.addFields({
				[URLSET_FIELD_NAME]: {
					args: {
						count: graphQL.GraphQLInt
					},
					type: graphQL.list(graphQL.reference(GraphQLTypeName.SITEMAP_URL)),
				},
			});
		},
	},
	resolvers: {
		[ObjectTypeName.portal_Site]: {
			[SITEMAP_FIELD_NAME]: buildSitemapResolver(graphQL),
		},
		[GraphQLTypeName.SITEMAP]: {
			[URLSET_FIELD_NAME]: urlset(graphQL),
		}
	}
});

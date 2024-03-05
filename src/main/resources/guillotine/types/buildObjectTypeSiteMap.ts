import type {GraphQL} from '@enonic-types/guillotine';


import {GraphQLTypeName} from '/guillotine/constants';


export const buildObjectTypeSiteMap = (graphQL: GraphQL) => ({
	description: 'Sitemap',
	fields: {
		baseUrl: {
			type: graphQL.GraphQLString,
		},
		urlset: {
			type: graphQL.list(graphQL.reference(GraphQLTypeName.SITEMAP_URL)),
		}
	}
});

import type {GraphQL} from '@enonic-types/guillotine';


import {
	URLSET_FIELD_NAME,
	GraphQLTypeName
} from '/guillotine/constants';


export const Sitemap = (graphQL: GraphQL) => ({
	description: 'Sitemap',
	fields: {
		baseUrl: {
			type: graphQL.GraphQLString,
		},
		[URLSET_FIELD_NAME]: {
			args: {
				count: graphQL.GraphQLInt
			},
			type: graphQL.list(graphQL.reference(GraphQLTypeName.SITEMAP_URL)),
		},
	}
});

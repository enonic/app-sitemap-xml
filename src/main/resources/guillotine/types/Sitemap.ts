import type {GraphQL} from '@enonic-types/guillotine';


import {
	// URLSET_CONNECTION_FIELD_NAME,
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
		// [URLSET_CONNECTION_FIELD_NAME]: {
		// 	args: {
		// 		after: graphQL.GraphQLString,
		// 		first: graphQL.GraphQLInt,
		// 	},
		// 	type: graphQL.reference(GraphQLTypeName.SITEMAP_URLSET_CONNECTION),
		// }
	}
});

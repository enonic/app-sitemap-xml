import type {GraphQL} from '@enonic-types/guillotine';


import {
	FILE_FIELD_NAME,
	FILES_FIELD_NAME,
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
		[FILES_FIELD_NAME]: {
			type: graphQL.nonNull(graphQL.list(graphQL.nonNull(graphQL.reference(GraphQLTypeName.SITEMAP_FILE)))),
		},
		[FILE_FIELD_NAME]: {
			args: {
				number: graphQL.nonNull(graphQL.GraphQLInt)
			},
			type: graphQL.reference(GraphQLTypeName.SITEMAP_FILE),
		},
	}
});

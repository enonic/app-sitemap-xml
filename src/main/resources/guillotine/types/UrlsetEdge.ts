import type {
	Extensions,
	GraphQL,
} from '@enonic-types/guillotine';


import {GraphQLTypeName} from '/guillotine/constants';


type GetRecordValue<T> = T extends Record<string, infer U> ? U : never;
type Type = GetRecordValue<Extensions['types']>


export const UrlsetEdge = (graphQl: GraphQL): Type => ({
	description: 'UrlsetEdge',
	fields: {
		cursor: {
			type: graphQl.nonNull(graphQl.GraphQLString)
		},
		node: {
			type: graphQl.nonNull(graphQl.reference(GraphQLTypeName.SITEMAP_URL))
		},
	}
});

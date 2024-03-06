import type {
	Extensions,
	GraphQL,
} from '@enonic-types/guillotine';


import {ObjectTypeName} from '@enonic-types/guillotine';
import {GraphQLTypeName} from '/guillotine/constants';


type GetRecordValue<T> = T extends Record<string, infer U> ? U : never;
type Type = GetRecordValue<Extensions['types']>


export const UrlsetConnection = (graphQl: GraphQL): Type => ({
	description: 'UrlsetConnection',
	fields: {
		edges: {
			type: graphQl.list(graphQl.reference(GraphQLTypeName.SITEMAP_URLSET_EDGE))
		},
		pageInfo: {
			type: graphQl.reference(ObjectTypeName.PageInfo)
		},
		totalCount: {
			type: graphQl.nonNull(graphQl.GraphQLInt),
		}
	}
});

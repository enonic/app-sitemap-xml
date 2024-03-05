import type {GraphQL} from '@enonic-types/guillotine';


export const buildObjectTypeSiteMapUrl = (graphQL: GraphQL) => ({
	description: 'Sitemap URL item',
			fields: {
				changefreq: {
					type: graphQL.GraphQLString
				},
				lastmod: {
					type: graphQL.DateTime,
				},
				path: {
					type: graphQL.nonNull(graphQL.GraphQLString),
				},
				priority: {
					type: graphQL.GraphQLString
				},
			}
});

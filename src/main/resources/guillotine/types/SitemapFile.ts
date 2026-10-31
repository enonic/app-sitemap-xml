import type {GraphQL} from '@enonic-types/guillotine';


import {
	URLSET_FIELD_NAME,
	GraphQLTypeName
} from '/guillotine/constants';


// One sitemap file: the URLs are split into files the same way as sitemap.xml (at most 50 000 URLs and 50MB each),
// so a client can fetch them one at a time, like Next.js generateSitemaps().
export const SitemapFile = (graphQL: GraphQL) => ({
	description: 'Sitemap file, a part of the sitemap',
	fields: {
		number: {
			type: graphQL.nonNull(graphQL.GraphQLInt),
		},
		lastmod: {
			type: graphQL.DateTime,
		},
		[URLSET_FIELD_NAME]: {
			type: graphQL.nonNull(graphQL.list(graphQL.nonNull(graphQL.reference(GraphQLTypeName.SITEMAP_URL)))),
		},
	}
});

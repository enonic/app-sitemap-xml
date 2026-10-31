import type {
	Extensions,
	GraphQL,
} from '@enonic-types/guillotine';


import {ObjectTypeName} from '@enonic-types/guillotine';
import {
	FILE_FIELD_NAME,
	FILES_FIELD_NAME,
	SITEMAP_FIELD_NAME,
	URLSET_FIELD_NAME,
	GraphQLTypeName,
} from '/guillotine/constants';
import {Sitemap} from './types/Sitemap';
import {SitemapFile} from './types/SitemapFile';
import {SitemapUrl} from './types/SitemapUrl';
import {sitemap} from './resolvers/sitemap';
import {
	file,
	files,
	fileUrlset,
	urlset,
} from '/guillotine/resolvers/urlset';


export const extensions = (graphQL: GraphQL): Extensions => ({
	types: {
		[GraphQLTypeName.SITEMAP]: Sitemap(graphQL),
		[GraphQLTypeName.SITEMAP_URL]: SitemapUrl(graphQL),
		[GraphQLTypeName.SITEMAP_FILE]: SitemapFile(graphQL),
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
			[FILES_FIELD_NAME]: files(graphQL),
			[FILE_FIELD_NAME]: file(graphQL),
		},
		[GraphQLTypeName.SITEMAP_FILE]: {
			[URLSET_FIELD_NAME]: fileUrlset(graphQL),
		},
	}
});

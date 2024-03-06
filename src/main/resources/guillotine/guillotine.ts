import type {
	Extensions,
	GraphQL,
} from '@enonic-types/guillotine';


import {ObjectTypeName} from '@enonic-types/guillotine';
import {
	SITEMAP_FIELD_NAME,
	URLSET_FIELD_NAME,
	// URLSET_CONNECTION_FIELD_NAME,
	GraphQLTypeName,
} from '/guillotine/constants';
// import {UrlsetConnection} from '/guillotine/types/UrlsetConnection';
// import {UrlsetEdge} from '/guillotine/types/UrlsetEdge';
import {Sitemap} from './types/Sitemap';
import {SitemapUrl} from './types/SitemapUrl';
import {sitemap} from './resolvers/sitemap';
import {urlset} from '/guillotine/resolvers/urlset';
// import {urlsetConnection} from '/guillotine/resolvers/urlsetConnection';


export const extensions = (graphQL: GraphQL): Extensions => ({
	types: {
		[GraphQLTypeName.SITEMAP]: Sitemap(graphQL),
		[GraphQLTypeName.SITEMAP_URL]: SitemapUrl(graphQL),
		// [GraphQLTypeName.SITEMAP_URLSET_CONNECTION]: UrlsetConnection(graphQL),
		// [GraphQLTypeName.SITEMAP_URLSET_EDGE]: UrlsetEdge(graphQL),
	},
	creationCallbacks: {
		[ObjectTypeName.portal_Site]: (params) => {
			params.addFields({
				[SITEMAP_FIELD_NAME]: {
					type: graphQL.reference(GraphQLTypeName.SITEMAP),
				},
			});
		},
	},
	resolvers: {
		[ObjectTypeName.portal_Site]: {
			[SITEMAP_FIELD_NAME]: sitemap(graphQL),
		},
		[GraphQLTypeName.SITEMAP]: {
			[URLSET_FIELD_NAME]: urlset(graphQL),
			// [URLSET_CONNECTION_FIELD_NAME]: urlsetConnection(graphQL),
		},
	}
});

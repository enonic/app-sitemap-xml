import type {
	// Aggregations,
	// AggregationsToAggregationResults,
	connect,
	// NodeQueryResult,
	RepoConnection
} from '@enonic-types/lib-node';


import {jest} from '@jest/globals';


export function mockLibXpNode({
	nodes = {}
}: {
	nodes?: Record<string, unknown>
}) {
	jest.mock(
		'/lib/xp/node',
		() => ({
			connect: jest.fn<typeof connect>().mockImplementation((connectParams) => {
				// console.debug('/lib/xp/node connect connectParams:', connectParams);
				return {
					get: jest.fn().mockImplementation((getParams: string) => {
						// console.debug('/lib/xp/node connection.get getParams:', getParams);
						const node = nodes[getParams];
						if (node) {
							return node;
						}
						console.error(`No node found for key: ${getParams}`);
						return null;
					}),
					query: jest.fn().mockReturnValue({
						count: Object.values(nodes).length,
						hits: Object.values(nodes).map(({_path: id}) => ({id})),
						total: Object.values(nodes).length,
					})
					// query: jest.fn().mockImplementation((queryNodeParams) => {
					// 	console.debug('/lib/xp/node connection.query queryNodeParams:', queryNodeParams);
					// 	return {
					// 		hits: [],
					// 		total: 0,
					// 	} //as NodeQueryResult<AggregationsToAggregationResults<AggregationInput>>
					// }),
				} as unknown as RepoConnection;
			})
		}),
		{virtual: true}
	);
}

import type {
	get as getContext,
	run
} from '@enonic-types/lib-context';


import {jest} from '@jest/globals';


export function mockLibXpContext() {
	jest.mock(
		'/lib/xp/context',
		() => ({
			get: jest.fn<typeof getContext>().mockReturnValue({
				attributes: {},
				authInfo: {
					principals: [
						"user:system:anonymous",
						"role:system.everyone"
					],
					// The site is public, so no user is logged in
					// user: {
					// 	login: 'login',
					// 	idProvider: 'idProvider',
					// 	type: 'user',
					// 	key: 'user:idProvder:name',
					// 	displayName: 'userDisplayName',
					// },
				},
				branch: 'master',
				repository: 'repository'
			}),
			run: jest.fn<typeof run>((_context, callback) => callback())
		}),
		{virtual: true}
	);
}

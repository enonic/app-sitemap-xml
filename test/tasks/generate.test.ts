/// <reference types="@enonic-types/global"/>
import {
	describe,
	expect,
	jest,
	test as it,
} from '@jest/globals';


const bean = {
	run: jest.fn((_key: string, _background: boolean, _changedAt: string | null) => undefined),
};
const newBean = jest.fn((_name: string) => bean);

// @ts-ignore TS2339: Property '__' does not exist on type 'typeof globalThis'.
globalThis.__ = {newBean};

describe('generate task', () => {
	it('runs the generation of the key in the bean', async () => {
		const {run} = await import('/tasks/generate/generate');

		run({key: '{"repository":"com.enonic.cms.project"}', regen: true, changedAt: '2024-01-01T00:00:00Z'});
		run({key: '{"repository":"com.enonic.cms.project"}'});

		expect(newBean).toHaveBeenCalledWith('com.enonic.app.sitemapxml.GenerateTaskBean');
		expect(bean.run).toHaveBeenNthCalledWith(1, '{"repository":"com.enonic.cms.project"}', true, '2024-01-01T00:00:00Z');
		expect(bean.run).toHaveBeenNthCalledWith(2, '{"repository":"com.enonic.cms.project"}', false, null);
	});
});

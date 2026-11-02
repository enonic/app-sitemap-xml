interface GenerateTaskBean {
	run(key: string, autogen: boolean, changedAt: string | null): void
}

export function run(params: {key: string, autogen?: boolean, changedAt?: string}): void {
	__.newBean<GenerateTaskBean>('com.enonic.app.sitemapxml.GenerateTaskBean')
		.run(params.key, params.autogen === true, params.changedAt || null);
}

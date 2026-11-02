interface GenerateTaskBean {
	run(key: string, background: boolean, changedAt: string | null): void
}

export function run(params: {key: string, background?: boolean, changedAt?: string}): void {
	__.newBean<GenerateTaskBean>('com.enonic.app.sitemapxml.GenerateTaskBean')
		.run(params.key, params.background === true, params.changedAt || null);
}

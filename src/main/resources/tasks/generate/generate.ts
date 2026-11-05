interface GenerateTaskBean {
	run(key: string, changedAt: string | null): void
}

export function run(params: {key: string, changedAt?: string}): void {
	__.newBean<GenerateTaskBean>('com.enonic.app.sitemapxml.GenerateTaskBean')
		.run(params.key, params.changedAt || null);
}

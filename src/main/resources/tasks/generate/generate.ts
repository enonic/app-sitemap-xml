interface GenerateTaskBean {
	run(key: string, regen: boolean, changedAt: string | null): void
}

export function run(params: {key: string, regen?: boolean, changedAt?: string}): void {
	__.newBean<GenerateTaskBean>('com.enonic.app.sitemapxml.GenerateTaskBean')
		.run(params.key, params.regen === true, params.changedAt || null);
}

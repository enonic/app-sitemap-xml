export declare type Request<
	T extends Record<string, unknown> = {
		body?: string
		contextPath?: string
		cookies?: Record<string, string>
		headers?: Record<string, string>
		params?: Record<string, string>
		pathParams?: Record<string, string>
		rawPath?: string
		remoteAddress?: string
		// webSocket?: unknown
	}
> = {
	branch: 'draft'|'master'
	host: string
	method: 'GET'|'POST'|'HEAD'|'PUT'|'DELETE'|'PATCH'
	mode: 'edit'|'inline'|'live'|'preview'
	path: string
	port: string|number
	scheme: string
	url: string
} & T;

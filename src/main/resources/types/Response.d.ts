// https://developer.enonic.com/docs/xp/stable/framework/http#http-response
export declare interface ComplexCookie {
	value: string // Value (required) The value to store in the cookie. This example will create a cookie looking like this complex: value.
	path?: string // The paths on the site where this cookie should be available from (and all containing paths). Defaults to empty
	domain?: string // Add additional sites that should be able to read the cookie. Defaults to empty (Only the server that creates the cookie can read it.)
	comment?: string // A comment describing the cookie. Default to `null. Deprecated and will be removed in future versions of XP.
	maxAge?: number // Number of seconds before the browser is allowed to delete the cookie. Defaults to -1 (The cookie will live until the browser is shut down.)
	secure?: boolean // Control if the cookie should only be accepted to be created and read over https and similar secure protocols. Defaults to false
	httpOnly?: boolean // Control if the cookie is available for scripts or not. If true, only the serverside code can read the cookie. Defaults to false (Also client-side scripts can read the cookie.)
	sameSite?: string // XP 7.3.0 SameSite flag for the cookie. Can be lax, strict, none or   for "not set". Default is "not set", meaning "browser’s default".
}

export declare interface PageContributions {
	headBegin?: string[]
	headEnd?: string[]
	bodyBegin?: string[]
	bodyEnd?: string[]
}

export declare interface Response {
	applyFilters?: boolean
	body: string|null // Body of the response as string. Null if the response content-type is not of type text.
	bodyStream?: unknown
	contentType?: string
	cookies?: Record<string,string|ComplexCookie>
	headers?: Record<string,string>
	message?: string
	pageContributions?: PageContributions
	postProcess?: boolean
	redirect?: string
	status?: number
	webSocket?: {
        data: Record<string, unknown>
        subProtocols?: string[]
    }
}

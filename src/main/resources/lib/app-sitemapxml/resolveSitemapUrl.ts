import {attachmentUrl, pageUrl} from '/lib/xp/portal';


/**
 * One row from {@link queryForSitemapContent} used when building sitemap URLs.
 */
export interface SitemapContentHit {
	_path: string
	type: string
	modifiedTime?: string
	/** First attachment name when the underlying node has attachments (e.g. media). */
	attachmentName?: string
}


export function isMediaContentType(contentType: string): boolean {
	return contentType.indexOf('media:') === 0;
}


function buildAttachmentUrlParams(
	hit: SitemapContentHit,
	urlType: 'absolute' | 'server'
): {
	path: string
	type: 'absolute' | 'server'
	name?: string
} {
	const params: {
		path: string
		type: 'absolute' | 'server'
		name?: string
	} = {
		path: hit._path,
		type: urlType,
	};
	if (hit.attachmentName) {
		params.name = hit.attachmentName;
	}
	return params;
}


/**
 * Full public URL for `sitemap.xml` / JSON (`<loc>`), matching legacy `overrideDomain` behaviour.
 */
export function resolveSitemapLoc(
	hit: SitemapContentHit,
	overrideDomain: string
): string {
	const useAttachment = isMediaContentType(hit.type);
	if (overrideDomain) {
		return useAttachment
			? overrideDomain + attachmentUrl(buildAttachmentUrlParams(hit, 'server'))
			: overrideDomain + pageUrl({
				path: hit._path,
				type: 'server',
			});
	}
	return useAttachment
		? attachmentUrl(buildAttachmentUrlParams(hit, 'absolute'))
		: pageUrl({
			path: hit._path,
			type: 'absolute',
		});
}


/**
 * Site-relative path for Guillotine `urlset`, aligned with historical `path.replace(site._path, '') || '/'`.
 */
export function resolveSitemapGuillotinePath(
	hit: SitemapContentHit,
	sitePath: string
): string {
	const useAttachment = isMediaContentType(hit.type);
	const serverUrl = useAttachment
		? attachmentUrl(buildAttachmentUrlParams(hit, 'server'))
		: pageUrl({
			path: hit._path,
			type: 'server',
		});

	if (serverUrl.indexOf(sitePath) === 0) {
		const rest = serverUrl.substring(sitePath.length);
		if (!rest) {
			return '/';
		}
		return rest.charAt(0) === '/' ? rest : `/${rest}`;
	}
	return serverUrl.charAt(0) === '/' ? serverUrl : `/${serverUrl}`;
}

/**
 * Tells whether a request accepts a gzip-encoded response body (Content-Encoding: gzip).
 * Header names are matched ignoring case, as clients and HTTP versions differ in how they send them.
 * An explicit "gzip;q=0" (or "*;q=0" without "gzip") refuses it.
 */
export function acceptsGzip(headers: Record<string, string> = {}): boolean {
	let value: string | undefined;
	for (const key of Object.keys(headers)) {
		if (key.toLowerCase() === 'accept-encoding') {
			value = headers[key];
		}
	}
	if (!value) {
		return false;
	}
	let gzip = -1;
	let any = -1;
	for (const part of value.split(',')) {
		const attributes = part.split(';');
		const coding = attributes[0].trim().toLowerCase();
		let quality = 1;
		for (let i = 1; i < attributes.length; i++) {
			const attribute = attributes[i].trim().toLowerCase();
			if (attribute.indexOf('q=') === 0) {
				const q = parseFloat(attribute.substring(2));
				quality = isNaN(q) ? 1 : q;
			}
		}
		if (coding === 'gzip' || coding === 'x-gzip') {
			gzip = Math.max(gzip, quality);
		} else if (coding === '*') {
			any = quality;
		}
	}
	return (gzip >= 0 ? gzip : any) > 0;
}

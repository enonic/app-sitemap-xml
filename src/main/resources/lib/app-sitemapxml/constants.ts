import type {
	tChangeFreq,
	tPriority,
} from '/types';


export const DEFAULT_PRIORITY: tPriority = '0.5';

export const DEFAULT_UPDATE_PERIOD:	tChangeFreq = 'monthly';

export const DEFAULT_MAX_ITEMS = 10000;

// https://www.sitemaps.org/protocol.html#index
// A sitemap file may contain at most 50,000 URLs. Larger sitemaps are split
// into several files listed in a sitemap index.
export const MAX_URLS_PER_FILE = 50000;

// Upper bound on the total number of URLs (across all sitemap files) for the
// sitemap.xml controller.
export const MAX_ITEMS_LIMIT = 1000000;

// Limit used by the Guillotine extension, which returns all urls in one response.
export const GUILLOTINE_MAX_ITEMS_LIMIT = 50000;

export const ES_MAX_ITEMS_LIMIT = 10000;

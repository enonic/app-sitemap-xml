import type {
	tChangeFreq,
	tPriority,
} from '/types';


export const DEFAULT_PRIORITY: tPriority = '0.5';

export const DEFAULT_UPDATE_PERIOD:	tChangeFreq = 'monthly';


// https://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd
// Container for a set of up to 50,000 document elements. This is the root element of the XML file.
export const MAX_ITEMS_LIMIT = 50000;

export const ES_MAX_ITEMS_LIMIT = 10000;

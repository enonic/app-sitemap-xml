// Based on: https://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd


// OPTIONAL: Indicates how frequently the content at a particular URL is likely
// to change. The value "always" should be used to describe documents that
// change each time they are accessed. The value "never" should be used to
// describe archived URLs. Please note that web crawlers may not necessarily
// crawl pages marked "always" more often. Consider this element as a friendly
// suggestion and not a command.
export declare type tChangeFreq = 'always' | 'hourly' | 'daily' | 'weekly' | 'monthly' | 'yearly' | 'never'


// OPTIONAL: The date the document was last modified. The date must conform to
// the W3C DATETIME format (http://www.w3.org/TR/NOTE-datetime).
// Example: 2005-05-10 Lastmod may also contain a timestamp.
// Example: 2005-05-10T17:33:30+08:00
// Enonic: 2024-02-02T09:01:33.169382938Z
export declare type tLastmod = string // Date | DateTime


// REQUIRED: The location URI of a document.
// The URI must conform to RFC 2396 (http://www.ietf.org/rfc/rfc2396.txt).
// <xsd:restriction base="xsd:anyURI">
// 	<xsd:minLength value="12"/>
// 	<xsd:maxLength value="2048"/>
// </xsd:restriction>
export declare type tLoc = string // URI


// OPTIONAL: The priority of a particular URL relative to other pages on the
// same site. The value for this element is a number between 0.0 and 1.0 where
// 0.0 identifies the lowest priority page(s). The default priority of a page is
// 0.5. Priority is used to select between pages on your site. Setting a
// priority of 1.0 for all URLs will not help you, as the relative priority of
// pages on your site is what will be considered.
export declare type tPriority = '0.0' | '0.1' | '0.2' | '0.3' | '0.4' | '0.5' | '0.6' | '0.7' | '0.8' | '0.9' | '1.0'


// Container for the data needed to describe a document to crawl.
export declare interface tUrl {
	changefreq?: tChangeFreq
	lastmod?: tLastmod
	loc: tLoc
	priority?: tPriority
}


// Container for a set of up to 50,000 document elements.
// This is the root element of the XML file.
export declare type urlset = tUrl[]

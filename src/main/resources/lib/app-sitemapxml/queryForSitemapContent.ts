import type {QueryDsl} from '@enonic-types/core';
import type {Site} from '@enonic-types/lib-content';
import type {
	SitemapXmlSiteConfig,
	tChangeFreq,
	tPriority,
} from '/types';


import {forceArray} from '@enonic/js-utils/array/forceArray';
import {toStr} from '@enonic/js-utils/value/toStr';
import {get as getContext} from '/lib/xp/context';
import {connect} from '/lib/xp/node';
import {
	DEBUG,
	TRACE,
} from '/constants';
import {
	DEFAULT_PRIORITY,
	DEFAULT_UPDATE_PERIOD,
	MAX_ITEMS_LIMIT
} from '/lib/app-sitemapxml/constants';


const GLOBALS: {
	alwaysAdd: string
} = {
	alwaysAdd: 'portal:site',
};


export function queryForSitemapContent({
	count = 10,
	site,
	siteConfig,
	start = 0,
}: {
	count?: number
	site: Site<SitemapXmlSiteConfig>
	siteConfig: SitemapXmlSiteConfig|null
	start?: number
}) {
	const {
		ignoreList = [],
		siteMap = [],
	} = siteConfig || {}; // Handle null (aka no config)

	const ignoreListArray = ignoreList ? forceArray(ignoreList) : [];
	const siteMapArray = siteMap ? forceArray(siteMap) : [];

	let siteAdded = false;
	const arrContentTypes: string[] = [];
	const changefreq: Record<string, tChangeFreq> = {};
	const priority: Record<string, tPriority> = {};

	for (let j = 0; j < siteMapArray.length; j++) {
		const cty = siteMapArray[j].contentType || "";

		// To be able to automatically add site content type if not already added by user.
		if (cty === GLOBALS.alwaysAdd) { siteAdded = true; }

		arrContentTypes.push(cty);
		changefreq[cty] = siteMapArray[j].updatePeriod || DEFAULT_UPDATE_PERIOD;
		priority[cty] = siteMapArray[j].priority || DEFAULT_PRIORITY;
	}

	// Default settings for site (frontpage) set to be changed and prioritized often.
	if (!siteAdded) {
		const cty = GLOBALS.alwaysAdd;
		arrContentTypes.push(cty);
		changefreq[cty] = 'hourly';
		priority[cty] = '1.0';
	}

	// Only allow content from current Site to populate the sitemap.
	const folderPath = site._path;
	const contentRoot = '/content' + folderPath + '';

	const {
		branch,
		repository: repoId,
	} = getContext();
	const projectRepoConnection = connect({
		branch,
		repoId
	});

	const must: QueryDsl[] = [{
		in: {
			field: 'type',
			values: arrContentTypes
		},
	},{
		boolean: {
			should: [{
				like: {
					field: '_path',
					value: contentRoot + '/*'
				}
			},{
				term: {
					field: '_path',
					value: contentRoot
				}
			}],
		}
	}];

	const blockRobotsDslArray: QueryDsl[] = [{
		term: {
			field: "x.com-enonic-app-metafields.meta-data.blockRobots",
			value: true
		}
	}];

	const ignoreDslArray = ignoreListArray.map(item => {
		if (!item.path) {
			return undefined;
		}
		const trimmedPath = item.path[item.path.length - 1] === '/'
			? item.path.slice(0, -1)
			: item.path;
		return {
			like: {
				field: '_path',
				value: trimmedPath[0] === '*'
					? trimmedPath
					: `${contentRoot}${
						trimmedPath[0] === '/' ? '' : '/'
					}${trimmedPath}`
			}
		};
	}).filter(x=>x) as QueryDsl[]; // Remove empty items.

	const mustNot: QueryDsl[] = blockRobotsDslArray.concat(ignoreDslArray);

	const nodeQueryParams = {
		count: -1,
		query: {
			boolean: {
				must,
				mustNot,
			}
		},
		sort: 'modifiedTime DESC',
		start
	};
	DEBUG && log.debug('nodeQueryParams: %s', toStr(nodeQueryParams));

	const nodeQueryRes = projectRepoConnection.query(nodeQueryParams);
	TRACE && log.debug('nodeQueryRes: %s', toStr(nodeQueryRes));
	if (nodeQueryRes.hits.length) {
		DEBUG && log.debug('nodeQueryRes.hits[0]: %s', toStr(nodeQueryRes.hits[0]));
	}
	DEBUG && log.debug('nodeQueryRes.total: %s', nodeQueryRes.total);

	const stopBefore = Math.min(nodeQueryRes.hits.length, count, MAX_ITEMS_LIMIT);
	DEBUG && log.debug('stopBefore: %s', stopBefore);

	const contents: {
		_path: string
		modifiedTime?: string
		type: string
	}[] = [];

	for (let i = 0; i < stopBefore; i++) {
		const {id} = nodeQueryRes.hits[i];
		const node = projectRepoConnection.get<{
			_path: string
			modifiedTime?: string
			type: string
		}>(id);
		if (node) {
			const {
				_path,
				modifiedTime,
				type,
			} = node;
			contents.push({
				_path: _path.replace(/^\/content/, ''),
				modifiedTime,
				type,
			});
		}
	} // for
	if (contents.length) {
		DEBUG && log.debug('contents[0]: %s', toStr(contents[0]));
	}

	return {
		changefreq,
		priority,
		result: {
			hits: contents,
			total: nodeQueryRes.total
		}
	};
}

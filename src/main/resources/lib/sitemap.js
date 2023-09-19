const gridLib = require('/lib/xp/grid');
const contextLib = require('/lib/xp/context');
const contentLib = require('/lib/xp/content');
const utilLib = require('/lib/util');

// A sitemap.xml can have a maximum of 50,000 items.
const MAX_ITEMS = 50000;

const globals = {
  updatePeriod: "monthly",
  priority: "0.5",
  alwaysAdd: "portal:site"
};

const debugLog = (msg) => {
  if (app.config.debugLog === 'true') {
    log.info(msg);
  }
};

const runForRepo = (repo) => contextLib.run({
  repository: repo.id,
  branch: 'master',
  principals: ["role:system.admin"],
}, () => {
  debugLog(`Sitemap - Running for repo ${repo.id}`);

  const sites = contentLib.query({
    contentTypes: ['portal:site'],
    count: 20,
    filters: {
      boolean: {
        must: {
          hasValue: {
            field: "data.siteconfig.applicationkey",
            values: "com.enonic.app.sitemapxml"
          }
        }
      }
    }
  });

  sites.hits.forEach(runForSite);

  if (sites.hits.length === 0) debugLog(`Sitemap - Found no sites with app installed in repo ${repo.id}`);
});

const getOptions = (site) => {
  const siteConfig = contentLib.getSiteConfig({
    key: site._id,
    applicationKey: 'com.enonic.app.sitemapxml'
  });

  if (!siteConfig) return false;

  const contentTypes = [];
  const changefreq = {}, priority = {};
  let siteAdded = false;
  const siteMapSettings = siteConfig.siteMap ? utilLib.data.forceArray(siteConfig.siteMap) : null;

  if (siteMapSettings) {
    for (let i = 0; i < siteMapSettings.length; i++) {
      const contentType = siteMapSettings[i].contentType || "";
      if (contentType === globals.alwaysAdd) siteAdded = true; // To be able to automatically add site content type if not already added by user.
      contentTypes.push(contentType);
      changefreq[contentType] = siteMapSettings[i].updatePeriod || globals.updatePeriod;
      priority[contentType] = siteMapSettings[i].priority || globals.priority;
    }
  }

  // Default settings for site (frontpage) set to be changed and prioritized often.
  if (!siteAdded) {
    contentTypes.push(globals.alwaysAdd);
    changefreq[globals.alwaysAdd] = "Hourly";
    priority[globals.alwaysAdd] = "1.0";
  }

  return {
    contentTypes,
    changefreq,
    priority,
  }
};

const runForSite = (site) => {
  debugLog(`Sitemap - Running for site ${site.displayName}`);

  const options = getOptions(site);
  if (!options) return; // App not setup for this site
  
  const contentRoot = `/content${site._path}`;
  const query = `_path LIKE "${contentRoot}/*" OR _path = "${contentRoot}"`;
  
  let total = 9999999;
  let start = 0;
  const batchSize = 500;
  const sitemapJson = {};

  while (start < total && start < MAX_ITEMS) {
    // Query that respects the settings from SEO Metafield plugin, if present, using 6.10 query filters - @nerdegutt.
    const result = contentLib.query({
      query,
      sort: 'modifiedTime DESC',
      contentTypes: options.contentTypes,
      count: batchSize,
      start,
      filters: {
        boolean: {
          mustNot: {
            hasValue: {
              field: "x.com-enonic-app-metafields.meta-data.blockRobots",
              values: "true"
            }
          }
        }
      }
    });

    result.hits.forEach(content => {
      const path = content._path.split('/').slice(2).join('/'); // Remove site from path
      const item = {
        changeFreq: options.changefreq[content.type],
        priority: options.priority[content.type],
        path, // Save path since we can't generate URL in this context
        modifiedTime: content.modifiedTime
      };

      sitemapJson[content._id] = item;
    });

    total = result.total;
    start += batchSize;
  }

  const sharedMap = gridLib.getMap(`sitemap-${site._id}`);

  /**
   * Need to store as string. If you store as JSON it will get converted to Java object and
   * the conversion back into JSON when you 'get' it from the shared map is broken.
   * After a get 'typeof sitemapJson' is 'object', but you can't use Object.keys on it etc.
   */
  sharedMap.set({
    key: 'sitemap',
    value: JSON.stringify(sitemapJson),
    ttlSeconds: 60 * 60 * 24 * 7 // 1 week
  });

  debugLog(`Sitemap - Complete for site ${site.displayName} with ${Object.keys(sitemapJson).length} items`);
  
  if (total > MAX_ITEMS) {
    /**
     * Todo: Remove 50k limit. In the service, generate siteindex xml with links to multiple sitemap xml files.
     * These links should all be same service with a page parameter for which sitemap to return.
     * Not related to current sitemapindex service which handles siteindex for uploaded XML files.
     **/
    log.warning(`Sitemap - Your site ${site.displayName} has too much content for a single sitemap.xml. Skipped ${total - MAX_ITEMS} content. Please consider using a sitemap index.`)
  }

  return sitemapJson;
}

// Run by event listener
const runForNodes = (nodes, repo) => contextLib.run({
  repository: repo,
  branch: 'master',
  principals: ["role:system.admin"],
}, () => {
  nodes.forEach(runForContent);
});

const runForContent = (node) => {
  const site = contentLib.getSite({
    key: node.id
  });

  const options = getOptions(site);
  if (!options) return; // App not setup for this site

  const content = contentLib.get({ key: node.id });
  if (options.contentTypes.indexOf(content.type) === -1) return; // Content type not included in sitemap config
  
  const path = content._path.split('/').slice(2).join('/'); // Remove site from path
  const item = {
    changeFreq: options.changefreq[content.type],
    priority: options.priority[content.type],
    path, // Save path since we can't generate URL in this context
    modifiedTime: content.modifiedTime
  };

  const sharedMap = gridLib.getMap(`sitemap-${site._id}`);
  let sitemapJson = sharedMap.get('sitemap');

  if (!sitemapJson) {
    // Did not find sitemap in shared map, start from scratch. This should hopefully never happen.
    debugLog(`Sitemap - runForContent - Did not find sitemap in shared map for site ${site.displayName}`);
    sitemapJson = {};
  } else {
    sitemapJson = JSON.parse(sitemapJson);
  }

  sitemapJson[content._id] = item;

  sharedMap.set({
    key: 'sitemap',
    value: JSON.stringify(sitemapJson),
    ttlSeconds: 60 * 60 * 24 * 7 // 1 week
  });
  debugLog(`Sitemap - Incremental sync of content '${content._path}' in site ${site.displayName}`);
};

// Run by event listener
const removeNodesFromMap = (nodes, repo) => contextLib.run({
  repository: repo,
  branch: 'draft', // Draft branch is used for deleted nodes
  principals: ["role:system.admin"],
}, () => {
  nodes.forEach(removeContentFromMap);
});

const removeContentFromMap = (node) => {
  const site = contentLib.getSite({
    key: node.id
  });

  const sharedMap = gridLib.getMap(`sitemap-${site._id}`);
  let sitemapJson = sharedMap.get('sitemap');
  if (!sitemapJson) {
    debugLog(`Sitemap - removeContentFromMap - Did not find sitemap in shared map for site ${site.displayName}`);
    return;
  }
  
  sitemapJson = JSON.parse(sitemapJson);
  if (!sitemapJson[node.id]) return;

  delete sitemapJson[node.id];
  sharedMap.set({
    key: 'sitemap',
    value: JSON.stringify(sitemapJson),
    ttlSeconds: 60 * 60 * 24 * 7 // 1 week
  });
  debugLog(`Sitemap - Incremental delete of content '${node.path}' in site ${site.displayName}`);
}

module.exports = {
  runForRepo,
  runForNodes,
  runForSite,
  removeNodesFromMap,
  debugLog
};
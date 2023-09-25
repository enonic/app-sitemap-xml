const contextLib = require('/lib/xp/context');
const clusterLib = require('/lib/xp/cluster');
const repoLib = require('/lib/xp/repo');

const sitemapLib = require('/lib/sitemap');

exports.run = () => {
  const isMaster = clusterLib.isMaster();
  if (!isMaster) return;

  sitemapLib.debugLog('Sitemap - Running full sync');

  const contentRepos = contextLib.run({
    principals: ["role:system.admin"],
  }, () => repoLib.list().filter(repo => repo.id.indexOf('com.enonic.cms') !== -1));

  contentRepos.forEach(sitemapLib.runForRepo)
};

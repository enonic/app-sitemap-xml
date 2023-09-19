try {
  log.info(`Starting Sitemap application`);
  
  const JOB_NAME = 'sitemap-full-sync';
  const cronSchedule = app.config.cronSchedule || '0 4 * * 1,3'; // At 04:00 on Monday and Wednesday
  const doInitialSync = app.config.syncAtAppStartup !== 'false';
  const doScheduledSync = app.config.scheduledSync !== 'false';
  const doSyncOnPublish = app.config.syncOnPublish !== 'false';

  const schedulerLib = require('/lib/xp/scheduler');
  const clusterLib = require('/lib/xp/cluster');
  const eventLib = require('/lib/xp/event');
  const taskLib = require('/lib/xp/task');
  const contextLib = require('/lib/xp/context');

  const sitemapLib = require('/lib/sitemap');

  if (clusterLib.isMaster()) {
    if (doInitialSync) {
    // Initial sync at application startup
      taskLib.submitTask({
        descriptor: 'com.enonic.app.sitemapxml:fullSync',
      });
    }

    schedulerLib.delete({ name: JOB_NAME });
    if (doScheduledSync) {
      // Schedule periodic sync, delete and recreate, config might have changed
      sitemapLib.debugLog(`Sitemap - Scheduling periodic sync with cron schedule ${cronSchedule}`);
      contextLib.run({
        principals: ["role:system.admin"],
      }, () => {
        const newJob = schedulerLib.create({
          name: JOB_NAME,
          descriptor: 'com.enonic.app.sitemapxml:fullSync',
          description: 'Generate Sitemap JSON for all content projects and sites',
          enabled: true,
          schedule: { type: 'CRON', value: cronSchedule, timeZone: 'GMT-2:00' }
        });

        sitemapLib.debugLog(JSON.stringify(newJob, null, 4));
      });
    }

    if (doSyncOnPublish) {
      // Listen for node.pushed events to incrementally update the sitemap
      sitemapLib.debugLog(`Sitemap - Listening for node.pushed events`);
      eventLib.listener({
        type: 'node.pushed',
        callback: (event) => {
          log.info(JSON.stringify(event, null, 4));
          sitemapLib.debugLog(`Sitemap - Received event ${event.type}`);
          const nodes = event.data.nodes.filter((n) => n.repo.indexOf('com.enonic.cms') !== -1 && n.branch == 'master' && n.path.startsWith('/content/'))
      
          // Assume all nodes are in the same repo
          if (nodes.length > 0) {
            const repo = nodes[0].repo;
            sitemapLib.debugLog(`Sitemap - Running incremental sync of ${nodes.length} nodes for repo ${repo}`);
            sitemapLib.runForNodes(nodes, repo);
          }
        }
      });

      // Listen for node.deleted events to incrementally remove from sitemap
      eventLib.listener({
        type: 'node.deleted',
        callback: (event) => {
          log.info(JSON.stringify(event, null, 4));
          sitemapLib.debugLog(`Sitemap - Received event ${event.type}`);
          const nodes = event.data.nodes.filter((n) => n.repo.indexOf('com.enonic.cms') !== -1 && n.branch == 'master' && n.path.startsWith('/content/'))
      
          // Assume all nodes are in the same repo
          if (nodes.length > 0) {
            const repo = nodes[0].repo;
            sitemapLib.debugLog(`Sitemap - Running incremental removal of ${nodes.length} nodes for repo ${repo}`);
            sitemapLib.removeNodesFromMap(nodes, repo);
          }
        } 
      });
    }
  }
} catch (e) {
  log.error(`Failed to start Sitemap application :: ${e.toString()}`)
}

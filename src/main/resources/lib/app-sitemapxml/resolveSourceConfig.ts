import {getSite} from '/lib/xp/content';
import {run as runInContext} from '/lib/xp/context';
import {get as getProject} from '/lib/xp/project';

/*
		* ScopeKey values:
		* Absent or / 				-- Current project
		* Content outside any site 	-- Current project
		* Site path 				-- That site
		* Content within a site 	-- Containing site
		*/
export function resolveSourceConfig(project: string, branch: string, scopeKey?: string) {
	return runInContext({
		repository: `com.enonic.cms.${project}`,
		branch,
	}, () => {
		if (scopeKey != null && scopeKey !== '/') {
			const site = getSite({key: scopeKey});
			if (site) {
				return site.data.siteConfig;
			}
		}

		const projectEntity = getProject({id: project});
		return projectEntity ? projectEntity.siteConfig : {};
	});
}

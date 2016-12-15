var libs = {
    portal: require('/lib/xp/portal'),
    content: require('/lib/xp/content'),
    util : require('/lib/enonic/util'),
    xslt: require('/lib/xp/xslt')
};

var view = resolve('sitemap.xsl');

function handleGet(req) {

    var siteConfig = libs.portal.getSiteConfig();
    var arrContentTypes = [];
    var siteMapSettings = siteConfig.siteMap ? libs.util.data.forceArray(siteConfig.siteMap) : null;
    var changefreq = {};
    if(siteMapSettings) {
        for(var j = 0; j < siteMapSettings.length; j++){
            var  cty = "";
            arrContentTypes.push(siteMapSettings[j].contentType);
            cty = siteMapSettings[j].contentType;
            changefreq[cty]  = siteMapSettings[j].updatePeriod ? siteMapSettings[j].updatePeriod : null ;
        }
    }

    var queryRes = libs.content.query({
        query: '',
        sort : 'modifiedTime DESC',
        contentTypes: arrContentTypes,
        count: 1000,
    });

    for(var i = 0 ; i < queryRes.hits.length; i++ ) {
        if(queryRes.hits[i].type){
            queryRes.hits[i].changeFreq = changefreq[queryRes.hits[i].type] ? changefreq[queryRes.hits[i].type] : "Monthly" ;
        } else {
            queryRes.hits = null;
        }
        //libs.util.log(queryRes.hits[i].changeFreq + " ----> " + queryRes.hits[i].type);
    }

    var model = {
        result: queryRes.hits
    };

    return {
        contentType: 'text/xml',
        body: libs.xslt.render(view, model)
    };
}
exports.get = handleGet;

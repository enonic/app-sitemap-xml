<?xml version="1.0" encoding="utf-8"?>

<xsl:stylesheet
        exclude-result-prefixes="#all"
        version="2.0"
        xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
        xmlns:portal="urn:enonic:xp:portal:1.0"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xmlns:xs="http://www.w3.org/2001/XMLSchema"
        xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
        xsi:schemaLocation="http://www.sitemaps.org/schemas/sitemap/0.9  http://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd"
        xmlns:video="http://www.google.com/schemas/sitemap-video/1.1"
        xmlns:stk="http://www.enonic.com/cms/xslt/stk"
        xmlns:idebanken="http://www.idebanken.org">

    <!-- Apparently this line is ignored? XML declaration must be added manually in template match="/" -->
    <xsl:output encoding="UTF-8" indent="no" method="xml" omit-xml-declaration="no"/>

    <xsl:template match="/">
        <xsl:text disable-output-escaping="yes">&lt;?xml version="1.0" encoding="UTF-8"?&gt;</xsl:text>
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:video="http://www.google.com/schemas/sitemap-video/1.1">
            <xsl:apply-templates select="/root/result/item"/>
        </urlset>
    </xsl:template>

    <xsl:template match="item">
        <url>
            <loc>
                <xsl:value-of select="url"/>
            </loc>
            <lastmod>
                <xsl:value-of select="substring-before(modifiedTime, 'T')"/>
            </lastmod>
            <changefreq>
                <xsl:value-of select="changeFreq"/>
            </changefreq>
            <priority>
                <xsl:value-of select="priority"/>
            </priority>
        </url>
    </xsl:template>

</xsl:stylesheet>

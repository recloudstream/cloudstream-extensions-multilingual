
package com.lagradost
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app


open class VudeoExtractor : ExtractorApi() {
    override val name: String = "Vudeo"
    override val mainUrl: String = "https://vudeo.io/"
    private val srcRegex =
        Regex("""sources\: \[\"(.*)\"""")  // would be possible to use the parse and find src attribute
    override val requiresReferer = false


    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val cleaned_url = url
        with(app.get(cleaned_url)) {  // raised error ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003) is due to the response: "error_nofile"
            srcRegex.find(this.text)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    ExtractorLink(
                        name,
                        name,
                        link,
                        cleaned_url, // voir si site demande le referer à mettre ici
                        Qualities.Unknown.value,
                    )
                )
            }
        }
        return null
    }
}



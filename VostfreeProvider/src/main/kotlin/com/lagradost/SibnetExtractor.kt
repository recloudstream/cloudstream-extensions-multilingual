
package com.lagradost
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup


open class SibnetExtractor : ExtractorApi() {
    override val name: String = "Sibnet"
    override val mainUrl: String = "https://video.sibnet.ru"
    private val srcRegex =
        Regex("""player\.src\(\[\{src: \"(.*?)\"""")  // would be possible to use the parse and find src attribute
    override val requiresReferer = true


    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val cleaned_url = url
        val html = app.get(cleaned_url)
        with(html) {  // raised error ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003) is due to the response: "error_nofile"
            srcRegex.find(this.text)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    ExtractorLink(
                        name,
                        name,
                        mainUrl + link,
                        cleaned_url, // voir si site demande le referer à mettre ici
                        Qualities.Unknown.value,
                    )
                )
            }
        }

        return null
    }
}

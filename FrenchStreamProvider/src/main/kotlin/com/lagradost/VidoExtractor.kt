package com.lagradost

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack

class VidoExtractor : ExtractorApi() {
    override var name = "Vido"
    override var mainUrl = "https://vido.lol"
    private val srcRegex = Regex("""layer\(\{sources\:\["(.*)"\]""")
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val methode = if (url.contains("embed")) {
            app.get(url) // french stream
        } else {
            val code = url.substringAfterLast("/")
            val data = mapOf(
                "op" to "embed",
                "file_code" to code,
                "&auto" to "1"

            )
            app.post("https://vido.lol/dl", referer = url, data = data) // wiflix
        }
        with(methode) {
            getAndUnpack(this.text).let { unpackedText ->
                //val quality = unpackedText.lowercase().substringAfter(" height=").substringBefore(" ").toIntOrNull()
                srcRegex.find(unpackedText)?.groupValues?.get(1)?.let { link ->
                    return listOf(
                        ExtractorLink(
                            name,
                            name,
                            link,
                            url,
                            Qualities.Unknown.value,
                            true,
                        )
                    )
                }
            }
        }
        return null
    }
}
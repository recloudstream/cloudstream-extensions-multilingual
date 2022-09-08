package com.lagradost

import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app

import okio.ByteString.Companion.decodeBase64

open class PstreamExtractor : ExtractorApi() {
    override val name: String = "Pstream"
    override val mainUrl: String = "https://www.pstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val refer = url
        val headers = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
        )
        val document = app.get(url, headers = headers).document

        val scriptsourceUrl =
            document.select("""script[src^="https://www.pstream.net/u/player-script?"]""")
                .attr("src")//** Get the url where the scritp function is **/

        val Scripdocument =
            app.get(scriptsourceUrl, headers = headers).document//** Open the scritp function  **/

        val base64CodeRegex =
            Regex("""e\.parseJSON\(atob\(t\)\.slice\(2\)\)\}\(\"(.*)\=\="\)\,n\=\"""")  //** Search the code64 **/
        val code64 = base64CodeRegex.find(Scripdocument.toString())?.groupValues?.get(1)

        val decoded = code64?.decodeBase64()?.utf8() //** decode the code64 **/

        val regexLink = Regex("""\"(https:\\\/\\\/[^"]*)""") //** Extract the m3u8 link **/
        val m3u8found = regexLink.find(decoded.toString())?.groupValues?.get(1)
        var m3u8 = m3u8found.toString().replace("""\""", "")

        return listOf(
            ExtractorLink(
                name,
                name,
                m3u8,
                refer, // voir si site demande le referer à mettre ici
                Qualities.Unknown.value,
                true,
                headers = headers

            )
        )

    }
}


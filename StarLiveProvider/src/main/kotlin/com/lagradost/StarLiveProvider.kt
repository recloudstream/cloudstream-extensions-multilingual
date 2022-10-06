package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element


class StarLiveProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://starlive.xyz"
    override var name = "StarLive"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Live,
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val sections = document.select("div.panel")
        if (sections.isEmpty()) throw ErrorLoadingException()

        return HomePageResponse(sections.map { sport ->
            val dayMatch = sport.previousElementSiblings().toList().first { it.`is`("h3") }.text()
            val categoryName = sport.selectFirst("h4")?.text()?:"Other"
            val showsList = sport.select("tr").takeWhile { it.text().contains("Player").not() }.filter { it.hasAttr("class")}.drop(1)
            val shows = showsList.groupBy { it.text().substringBeforeLast(" ")}.map { matchs ->
                val posterUrl = fixUrl(sport.selectFirst("h4")?.attr("style")?.substringAfter("(")?.substringBefore(")")?:"")
                val href = mutableListOf<String>()
                val hasDate = matchs.key.contains(":")
                val matchName =  if (hasDate){matchs.key.substringAfter(" ")} else{matchs.key}

                matchs.value.map { match ->
                    val linkUrl = fixUrl(match.selectFirst("a")?.attr("href") ?: "")
                    val lang = match.attr("class")
                    href.add( "{\"link\": \"$linkUrl\", \"lang\":\"$lang\", \"name\": \"$matchName\"}")
                }

                val date = if (hasDate){dayMatch + " - " + matchs.key.substringBefore(" ")} else{dayMatch}

                LiveSearchResponse(
                    matchName,
                    "{\"linkData\": $href , \"matchData\":{\"time\": \"$date\", \"poster\":\"$posterUrl\"}}",
                    this@StarLiveProvider.name,
                    TvType.Live,
                    posterUrl,
                )
            }
            HomePageList(
                categoryName,
                shows
            )

        })

    }

    private data class LinkParser(
        @JsonProperty("link") val link: String,
        @JsonProperty("lang") val language: String,
        @JsonProperty("name") val name: String
    )
    private data class MatchDataParser(
        @JsonProperty("time") val time: String,
        @JsonProperty("poster") val poster: String
    )
    private data class MatchParser(
        @JsonProperty("linkData") val linkData: List<LinkParser>,
        @JsonProperty("matchData") val MatchData: MatchDataParser
    )

    override suspend fun load(url: String): LoadResponse {
        val matchdata = tryParseJson<MatchParser>(url)
        val poster =  matchdata?.MatchData?.poster
        val Matchstart = matchdata?.MatchData?.time
        return LiveStreamLoadResponse(
            dataUrl = url,
            url = matchdata?.linkData?.firstOrNull()?.link?:mainUrl,
            name = matchdata?.linkData?.firstOrNull()?.name?:mainUrl,
            posterUrl = poster,
            plot = Matchstart,
            apiName = this@StarLiveProvider.name
        )


    }




    private suspend fun extractVideoLinks(
        data: LinkParser,
        callback: (ExtractorLink) -> Unit
    ) {
        val linktoStream = "https:"+app.get(data.link).document.selectFirst("iframe")!!.attr("src")

        val referrerLink = if(linktoStream.contains("starlive")){
            app.get(linktoStream, referer = data.link).document.selectFirst("iframe")?.attr("src")?:""
        }
        else{
            linktoStream
        }
        val packed = when(linktoStream.contains("starlive")){
            true -> app.get(referrerLink, referer = linktoStream).document.select("script")[6].childNodes()[0].toString()
            false -> app.get(linktoStream, referer = data.link).document.select("script").select("script")[6].childNodes()[0].toString()
        }
        val streamurl = getAndUnpack(packed).substringAfter("var src=\"").substringBefore("\"")
        callback(
            ExtractorLink(
                source = this.name,
                name = data.name + " - " + data.language,
                url = streamurl,
                quality = 0,
                referer = referrerLink,
                isM3u8 = true
            )
        )

    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        tryParseJson<MatchParser>(data)?.linkData?.map {link->
            extractVideoLinks(link, callback)
        }

        return true
    }
}
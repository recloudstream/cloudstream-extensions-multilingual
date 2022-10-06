package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class StarLiveProvider : MainAPI() {
    override var mainUrl = "https://starlive.xyz"
    override var name = "StarLive"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

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


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val sections = document.select("div.panel")
        if (sections.isEmpty()) throw ErrorLoadingException()

        return HomePageResponse(sections.mapNotNull { sport ->
            val dayMatch = sport.previousElementSiblings().toList().first { it.`is`("h3") }.text()
            val categoryName = sport.selectFirst("h4")?.text() ?: "Other"

            val showsList = sport.select("tr").takeWhile { it.text().contains("Player").not() }
                .filter { it.hasAttr("class") }.drop(1)

            val shows = showsList.groupBy { it.text().substringBeforeLast(" ") }.map { matchs ->
                val posterUrl = fixUrl(
                    sport.selectFirst("h4")?.attr("style")
                        ?.substringAfter("(")?.substringBefore(")") ?: ""
                )
                val hasDate = matchs.key.contains(":")
                val matchName = if (hasDate) { matchs.key.substringAfter(" ")}
                                else { matchs.key }

                val href = matchs.value.map { match ->
                    val linkUrl = fixUrl(match.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                    val lang = match.attr("class")
                    LinkParser(linkUrl, lang, matchName)
                }

                val date = if (hasDate) {
                    dayMatch + " - " + matchs.key.substringBefore(" ")
                } else {
                    dayMatch
                }

                LiveSearchResponse(
                    matchName,
                    MatchParser(href, MatchDataParser(date, posterUrl)).toJson(),
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

    override suspend fun load(url: String): LoadResponse {
        val matchdata = tryParseJson<MatchParser>(url)
        val poster = matchdata?.MatchData?.poster
        val matchstart = matchdata?.MatchData?.time
        return LiveStreamLoadResponse(
            dataUrl = url,
            url = matchdata?.linkData?.firstOrNull()?.link ?: mainUrl,
            name = matchdata?.linkData?.firstOrNull()?.name ?: "No name",
            posterUrl = poster,
            plot = matchstart,
            apiName = this@StarLiveProvider.name
        )
    }

    private suspend fun extractVideoLinks(
        data: LinkParser,
        callback: (ExtractorLink) -> Unit
    ) {
        val linktoStream = fixUrl(app.get(data.link).document.selectFirst("iframe")!!.attr("src"))

        val referrerLink = if (linktoStream.contains("starlive")) {
            app.get(linktoStream, referer = data.link).document.selectFirst("iframe")?.attr("src")
                ?: return
        } else {
            linktoStream
        }
        val packed = when (linktoStream.contains("starlive")) {
            true -> app.get(
                referrerLink,
                referer = linktoStream
            ).document.select("script").toString()
            false -> app.get(linktoStream, referer = data.link).document.select("script")
                .select("script").toString()
        }
        val streamUrl = getAndUnpack(packed).substringAfter("var src=\"").substringBefore("\"")
        callback(
            ExtractorLink(
                source = this.name,
                name = data.name + " - " + data.language,
                url = streamUrl,
                quality = Qualities.Unknown.value,
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
        tryParseJson<MatchParser>(data)?.linkData?.map { link ->
            extractVideoLinks(link, callback)
        }

        return true
    }
}
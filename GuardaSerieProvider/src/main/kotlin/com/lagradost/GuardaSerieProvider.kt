package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor


class GuardaSerieProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://guardaserie.skin"
    override var name = "GuardaSerie"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
    )
    override val mainPage = mainPageOf(
        Pair("$mainUrl/serietv-popolari/page/", "Serie Tv Popolari"),
        Pair("$mainUrl/serietv-streaming/page/", "Ultime Serie Tv"),
        Pair("$mainUrl/top-imdb/page/", "Top IMDB")
    )


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document
        val home = soup.select("div.mlnew").drop(1).map { series ->
            val title = series.selectFirst("div.mlnh-2")!!.text()
            val link = series.selectFirst("div.mlnh-2 > h2 > a")!!.attr("href")
            val posterUrl = fixUrl(series.selectFirst("img")!!.attr("src"))

            newTvSeriesSearchResponse(
                title,
                link,
                TvType.TvSeries
            ) {
                this.posterUrl = posterUrl
            }

        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.post(
            mainUrl, data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query
            )
        ).document
        return doc.select("div.mlnew").drop(1).map { series ->
            val title = series.selectFirst("div.mlnh-2")!!.text()
            val link = series.selectFirst("div.mlnh-2 > h2 > a")!!.attr("href")
            val posterUrl = fixUrl(series.selectFirst("img")!!.attr("src"))
            newMovieSearchResponse(
                title,
                link,
                TvType.Movie
            ) {
                this.posterUrl = posterUrl
            }

        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")!!.text().removeSuffix(" streaming")
        val description = document.selectFirst("div.tv_info_right")?.textNodes()?.joinToString("")
        val rating = document.selectFirst("span.post-ratings")?.text()
        var year = document.select("div.tv_info_list > ul").find { it.text().contains("Anno") }?.text()?.substringBefore("-")?.filter { it.isDigit() }?.toIntOrNull()
        val poster = fixUrl(document.selectFirst("#cover")!!.attr("src")).replace("/141x200-0-85/", "/60x85-0-85/")

        val episodeList = document.select("div.tab-content > div").mapIndexed { season, data ->
            data.select("li").mapIndexed { epNum, epData ->
                val epName = epData.selectFirst("a")?.attr("data-title")
                val data = epData.select("div.mirrors > a").map { it.attr("data-link") }
                    .joinToString ( "," )
                Episode(
                    data = data,
                    name = epName,
                    season = season + 1,
                    episode = epNum + 1,
                )
            }
        }.flatten()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodeList
        ) {
            addRating(rating)
            this.plot = description
            this.year = year
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split(",")
        links.map { url ->
            loadExtractor(fixUrl(url), fixUrl(url), subtitleCallback, callback)
        }
        return true
    }
}
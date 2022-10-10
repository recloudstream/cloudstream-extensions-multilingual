package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class CineBlog01Provider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://www.cineblog01.legal"
    override var name = "CineBlog01"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
    )
    override val mainPage = mainPageOf(
        Pair("$mainUrl/page/", "Film Popolari"),
        Pair("$mainUrl/film-sub-ita/page/", "Film Sub-ita")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document

        val home = soup.select("div.filmbox").map { series ->
            val title = series.selectFirst("img")!!.attr("alt")
            val link = series.selectFirst("a")!!.attr("href")
            val posterUrl = fixUrl(series.selectFirst("img")!!.attr("src"))
            val quality = Regex("\\[([^\\]]*)]").find(series.selectFirst("h1")!!.text())?.groupValues?.get(1)
            val year = Regex("\\(([^)]*)\\)").find(series.selectFirst("h1")!!.text())?.groupValues?.get(1)?.toIntOrNull()
            
            newMovieSearchResponse(
                title,
                link,
                TvType.TvSeries
            ) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
                this.year = year
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.post(
            "$mainUrl/index.php?do=search", data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query
            )
        ).document
        return doc.select("div.filmbox").map { series ->
            val title = series.selectFirst("img")!!.attr("alt")
            val link = series.selectFirst("a")!!.attr("href")
            val posterUrl = fixUrl(series.selectFirst("img")!!.attr("src"))
            var quality = Regex("\\[([^\\]]*)]").find(series.selectFirst("h1")!!.text())?.groupValues?.get(1)
            var year = Regex("\\(([^)]*)\\)").find(series.selectFirst("h1")!!.text())?.groupValues?.get(1)?.toIntOrNull()

            newMovieSearchResponse(
                title,
                link,
                TvType.TvSeries
            ) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
                this.year = year
            }

        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.imgrow > img")!!.attr("alt")
        val description = document.selectFirst("div.fstory")?.text()?.removeSuffix(" +Info »")?.substringAfter("′ - ")
        var year = document.selectFirst("div.filmboxfull")?.getElementsByAttributeValueContaining("href" , "/anno/")?.text()?.toIntOrNull()
        val poster = fixUrl(document.selectFirst("div.imgrow > img")!!.attr("src"))
        val dataUrl = document.select("ul.mirrors-list__list > li").map {
            it.select("a").attr("href")
        }.drop(1).joinToString (",")
        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            dataUrl = dataUrl
        ) {

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
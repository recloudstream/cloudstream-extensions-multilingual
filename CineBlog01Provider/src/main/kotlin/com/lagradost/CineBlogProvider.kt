package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import org.jsoup.nodes.Element

class CineBlog01Provider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://www.cineblog01.moe"
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
        val home = soup.select("div.filmbox").mapNotNull { series ->
            series.toSearchResult()
        }
        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title =
            this.selectFirst("img")?.attr("alt") ?: throw ErrorLoadingException("No Title found")
        val link =
            this.selectFirst("a")?.attr("href") ?: throw ErrorLoadingException("No Link found")
        val posterUrl = fixUrl(
            this.selectFirst("img")?.attr("src") ?: throw ErrorLoadingException("No Poster found")
        )
        val quality = Regex("\\[([^\\]]*)]").find(
            this.selectFirst("h1")?.text() ?: ""
        )?.groupValues?.getOrNull(1) ?: ""
        val year = Regex("\\(([^)]*)\\)").find(
            this.selectFirst("h1")?.text() ?: ""
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newMovieSearchResponse(
            title,
            link,
            TvType.TvSeries
        ) {
            this.year = year
            addPoster(posterUrl)
            addQuality(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val body = FormBody.Builder()
            .addEncoded("do", "search")
            .addEncoded("subaction", "search")
            .addEncoded("story", query)
            .addEncoded("sortby", "news_read")
            .build()
        val doc = app.post(
            "$mainUrl/index.php",
            requestBody = body
        ).document

        return doc.select("div.filmbox").mapNotNull { series ->
            series.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.imgrow > img")!!.attr("alt")
        val description = document.selectFirst("div.fstory")?.text()?.removeSuffix(" +Info »")
            ?.substringAfter("′ - ")
        val year = document.selectFirst("div.filmboxfull")
            ?.getElementsByAttributeValueContaining("href", "/anno/")?.text()?.toIntOrNull()
        val poster = fixUrl(document.selectFirst("div.imgrow > img")!!.attr("src"))
        val dataUrl = document.select("ul.mirrors-list__list > li").map {
            it.select("a").attr("href")
        }.drop(1).joinToString(",")
        val trailerUrl =
            document.select("iframe").firstOrNull { it.attr("src").contains("youtube") }
                ?.attr("src")
                ?.let { fixUrl(it) }
        val tags =
            document.selectFirst("#dle-content h4")?.text()?.substringBefore("- DURATA")?.trim()
                ?.split(" / ")
        val duration = Regex("DURATA (.*)′").find(
            document.selectFirst("#dle-content h4")?.text() ?: ""
        )?.groupValues?.last()
        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            dataUrl = dataUrl
        ) {
            this.plot = description
            this.year = year
            this.posterUrl = poster
            this.tags = tags
            addTrailer(trailerUrl)
            addDuration(duration)
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
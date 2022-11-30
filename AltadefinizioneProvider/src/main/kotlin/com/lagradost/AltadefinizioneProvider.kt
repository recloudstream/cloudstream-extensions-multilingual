package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import org.jsoup.nodes.Element


class AltadefinizioneProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://altadefinizione.navy"
    override var name = "Altadefinizione"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/cerca/anno/2022/page/", "Ultimi Film"),
        Pair("$mainUrl/cerca/openload-quality/HD/page/", "Film in HD"),
        Pair("$mainUrl/cinema/page/", "Ora al cinema")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document
        val home = soup.select("div.box").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("img")?.attr("alt") ?: return null
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val image = mainUrl + this.selectFirst("img")?.attr("src")
        val quality = getQualityFromString(this.selectFirst("span")?.text())
        return newMovieSearchResponse(title, link, TvType.Movie) {
            this.posterUrl = image
            this.quality = quality
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

        return doc.select("div.box").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(" h1 > a")?.text()?.replace("streaming", "")
            ?: throw ErrorLoadingException("No Title found")
        val description = document.select("#sfull").textNodes().first { it.text().trim().isNotEmpty() }.text().trim()
        val rating = document.select("span.rateIMDB").text().substringAfter(" ")
        val year = document.selectFirst("#details")?.select("li")
            ?.firstOrNull { it.select("label").text().contains("Anno") }
            ?.text()?.substringAfter(" ")?.toIntOrNull()
        val poster = fixUrl(document.selectFirst("div.thumbphoto > img")?.attr("src")?: throw ErrorLoadingException("No Poster found") )
        val recomm = document.select("ul.related-list > li").mapNotNull {
            it.toSearchResult()
        }
        val actors: List<Actor> =
            document.select("#staring > a").map {
                Actor(it.text())
            }
        val tags: List<String> = document.select("#details > li:nth-child(1) > a").map { it.text() }
        val trailerUrl = document.selectFirst("#showtrailer > div > div > iframe")?.attr("src")
        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.year = year
            this.plot = description
            this.recommendations = recomm
            this.tags = tags
            addActors(actors)
            addPoster(poster)
            addRating(rating)
            addTrailer(trailerUrl)
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        if (doc.select("div.guardahd-player").isNullOrEmpty()) {
            val videoUrl =
                doc.select("input").last { it.hasAttr("data-mirror") }.attr("value")
            loadExtractor(videoUrl, data, subtitleCallback, callback)
            doc.select("#mirrors > li > a").forEach {
                loadExtractor(fixUrl(it.attr("data-target")), data, subtitleCallback, callback)
            }
        } else {
            val pagelinks = doc.select("div.guardahd-player").select("iframe").attr("src")
            val docLinks = app.get(pagelinks).document
            docLinks.select("body > div > ul > li").forEach {
                loadExtractor(fixUrl(it.attr("data-link")), data, subtitleCallback, callback)
            }
        }
        return true
    }
}
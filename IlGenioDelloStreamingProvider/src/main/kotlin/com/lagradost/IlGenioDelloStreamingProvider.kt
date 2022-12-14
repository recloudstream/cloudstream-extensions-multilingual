package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ShortLink.unshorten
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class IlGenioDelloStreamingProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://ilgeniodellostreaming.hair"
    override var name = "IlGenioDelloStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override var sequentialMainPage = true
    override val supportedTypes =
        setOf(
            TvType.Movie,
            TvType.TvSeries,
        )
    override val mainPage =
        mainPageOf(
            Pair("$mainUrl/popular-movies/page/", "Film Popolari"),
            Pair("$mainUrl/the-most-voted/page/", "I più votati"),
        )
    private val interceptor = CloudflareKiller()

    private fun fixTitle(element: Element?): String {
        return element?.text()
            ?.trim()
            ?.substringBefore("Streaming")
            ?.replace("[HD]", "")
            ?.replace("\\(\\d{4}\\)".toRegex(), "")
            ?: "No Title found"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page

        val soup = app.get(url, referer = mainUrl).document
        val home = soup.select("div.items > article.item").mapNotNull { it.toMainPageResult() }
        val hasNext = home.isNotEmpty()
        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = hasNext)
    }

    private fun Element.toMainPageResult(): SearchResponse {
        val title =
            fixTitle(this.selectFirst("div.data>h3"))
        val isMovie =
            (this.selectFirst("div.data>h3")?.text() ?: "").contains("\\(\\d{4}\\)".toRegex())
        val link =
            this.selectFirst("div.poster>a")?.attr("href")
                ?: throw ErrorLoadingException("No Link found")

        val quality = this.selectFirst("div.poster>span.quality")?.text()
        val posterUrl = this.selectFirst("div.poster>a>img")?.attr("src")

        return if (isMovie) {
            newMovieSearchResponse(title, link, TvType.Movie) {
                addPoster(posterUrl)
                quality?.let { addQuality(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                addPoster(posterUrl)
                quality?.let { addQuality(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val queryFormatted = query.replace(" ", "+")
        val url = "$mainUrl/?s=$queryFormatted"
        val doc = app.get(url, referer = mainUrl, interceptor = interceptor).document
        return doc.select("div.search-page>div.result-item").map { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title =
            fixTitle(this.selectFirst("div.title>a"))
        val isMovie =
            (this.selectFirst("div.title>a")?.text() ?: "").contains("\\(\\d{4}\\)".toRegex())
        val link =
            this.selectFirst("div.title>a")?.attr("href")
                ?: throw ErrorLoadingException("No Link found")

        val quality =
            this.selectFirst("div.title>a")?.text()?.substringAfter("[")?.substringBefore("]")
        val posterUrl = this.selectFirst("a>img")?.attr("src")

        return if (isMovie) {
            newMovieSearchResponse(title, link, TvType.Movie) {
                addPoster(posterUrl)
                if (quality != null) {
                    addQuality(quality)
                }
            }
        } else {
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                addPoster(posterUrl)
                if (quality != null) {
                    addQuality(quality)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document
        val type =
            if (document.select("div.seasons-wraper").isNotEmpty()) TvType.TvSeries
            else TvType.Movie

        val title =
            fixTitle(document.selectFirst("div.data > h1"))
        val description =
            document.selectFirst("div#info")
                ?.text()
                ?.substringAfter(".")
                ?.substringBefore("Leggi anche")
        val year =
            document.selectFirst("b.variante>strong>a")
                ?.text()
                ?.substringBefore("-")
                ?.substringAfter(",")
                ?.filter { it.isDigit() }
        val poster = document.selectFirst("div.poster>img")?.attr("src")
        val rating = document.selectFirst("span.valor>strong")?.text()?.toRatingInt()
        val trailer =
            document.selectFirst("img.youtube__img")
                ?.attr("src")
                ?.substringAfter("vi/")
                ?.substringBefore("/")
                ?.let { "https://www.youtube.com/watch?v=$it" }
        val recomm = document.select("article.w_item_b").map { it.toRecommendResult() }
        if (type == TvType.TvSeries) {
            val episodeList =
                document.select("div.accordion>div.accordion-item")
                    .map { element ->
                        val season =
                            element.selectFirst("li.s_title>span.season-title")
                                ?.text()
                                ?.toIntOrNull()
                                ?: 0
                        element.select("div.episode-wrap").map { episode ->
                            episode.toEpisode(season)
                        }
                    }
                    .flatten()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.year = year?.toIntOrNull()
                this.plot = description
                this.recommendations = recomm
                addPoster(poster)
                addRating(rating)
                addTrailer(trailer)
            }
        } else {
            val actors: List<ActorData> =
                document.select("div.cast_wraper>ul>li").map { actordata ->
                    val actorName = actordata.selectFirst("strong")?.text() ?: ""
                    val actorImage: String =
                        actordata.selectFirst("figure>img")?.attr("src") ?: ""
                    ActorData(actor = Actor(actorName, image = actorImage))
                }
            val data = document.select(".embed-player").map { it.attr("data-id") }.toJson()
            return newMovieLoadResponse(title, data, TvType.Movie, data) {
                this.year = year?.toIntOrNull()
                this.plot = description
                this.actors = actors
                this.recommendations = recomm
                addPoster(poster)
                addRating(rating)
                addTrailer(trailer)
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse {
        val title =
            fixTitle(this.selectFirst("div.data>h3"))
        val isMovie =
            (this.selectFirst("div.data>h3")?.text() ?: "").contains("\\(\\d{4}\\)".toRegex())
        val link =
            this.selectFirst("a")?.attr("href") ?: throw ErrorLoadingException("No Link found")

        val quality =
            this.selectFirst("div.data>h3")?.text()?.substringAfter("[")?.substringBefore("]")
        val posterUrl = this.selectFirst("div.image>img")?.attr("src")

        return if (isMovie) {
            newMovieSearchResponse(title, link, TvType.Movie) {
                addPoster(posterUrl)
                if (quality != null) {
                    addQuality(quality)
                }
            }
        } else {
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                addPoster(posterUrl)
                if (quality != null) {
                    addQuality(quality)
                }
            }
        }
    }

    private fun Element.toEpisode(season: Int): Episode {
        val data =
            this.select("div.fix-table>table>tbody>tr>td>a[target=_blank]") // buckler.link
                .map { it.attr("href") }
                .toJson()
        val epNum =
            this.selectFirst("li.season-no")
                ?.text()
                ?.substringAfter("x")
                ?.substringBefore(" ")
                ?.filter { it.isDigit() }
                .orEmpty().ifBlank { "0" }

        val epTitle =
            this.selectFirst("li.other_link>a")?.text().orEmpty().ifBlank {
                "Episodio $epNum"
            }
        val posterUrl = this.selectFirst("img")?.attr("src")
        return Episode(data, epTitle, season, epNum?.toInt(), posterUrl = posterUrl)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        parseJson<List<String>>(data).map { videoUrl ->
            loadExtractor(unshorten(videoUrl), data, subtitleCallback, callback)
        }
        return true
    }
}

package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
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

class CasaCinemaProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://casacinema.lol/"
    override var name = "CasaCinema"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasChromecastSupport = true
    override var lang = "it"
    override val hasMainPage = true
    private val interceptor = CloudflareKiller()

    override val mainPage =
        mainPageOf(
            "$mainUrl/category/serie-tv/page/" to "Ultime Serie Tv",
            "$mainUrl/category/film/page/" to "Ultimi Film",
        )

    private fun fixTitle(element: Element?): String {
        return element?.text()
            ?.trim()
            ?.substringBefore("Streaming")
            ?.replace("[HD]", "")
            ?.replace("\\(\\d{4}\\)".toRegex(), "")
            ?: "No Title found"
    }

    private fun Element?.isMovie(): Boolean {
        return (this
            ?.text() ?: "")
            .contains("\\(\\d{4}\\)".toRegex())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url = request.data + page

        val soup = app.get(url, referer = mainUrl).document
        val home = soup.select("ul.posts>li").mapNotNull { it.toSearchResult() }
        val hasNext = soup.select("div.navigation>ul>li>a").last()?.text() == "Pagina successiva »"
        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = hasNext)
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val queryFormatted = query.replace(" ", "+")
        val url = "$mainUrl/?s=$queryFormatted"
        val doc = app.get(url, referer = mainUrl, interceptor = interceptor).document
        return doc.select("ul.posts>li").map { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = fixTitle(this.selectFirst(".title"))
        val isMovie = this.selectFirst(".title").isMovie()
        val link =
            this.selectFirst("a")?.attr("href") ?: throw ErrorLoadingException("No Link found")

        val quality = this.selectFirst("div.hd")?.text()
        val posterUrl = this.selectFirst("a")?.attr("data-thumbnail")

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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document
        val type =
            if (document.select("div.seasons-wraper").isNotEmpty()) TvType.TvSeries
            else TvType.Movie
        val title = fixTitle(document.selectFirst("div.row > h1"))
        val description = document.select("div.element").last()?.text()
        val year = document.selectFirst("div.element>a.tag")
            ?.text()
            ?.substringBefore("-")
            ?.substringAfter(",")
            ?.filter { it.isDigit() }
        val poster = document.selectFirst("img.thumbnail")?.attr("src")
        val rating = document.selectFirst("div.rating>div.value")?.text()?.trim()?.toRatingInt()
        val recomm = document.select("div.crp_related>ul>li").map { it.toRecommendResult() }
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
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse {
        val title =
            fixTitle(this.selectFirst("span.crp_title"))
        val isMovie = this.selectFirst("span.crp_title").isMovie()
        val link =
            this.selectFirst("a")?.attr("href") ?: throw ErrorLoadingException("No Link found")

        val quality =
            this.selectFirst("span.crp_title")?.text()?.substringAfter("[")?.substringBefore("]")
        val posterUrl = this.selectFirst("img")?.attr("src")

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
            this.select("div.fix-table>table>tbody>tr>td>a[target=_blank]")
                .map { it.attr("href") }
                .toJson() // isecure.link
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
        val posterUrl = this.selectFirst("figure>img")?.attr("src")
        return Episode(data, epTitle, season, epNum.toInt(), posterUrl = posterUrl)
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

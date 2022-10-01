package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.select.Elements

class PinoyMoviesHub : MainAPI() {
    //private val TAG = "Dev"
    override var name = "Pinoy Movies Hub"
    override var mainUrl = "https://pinoymovieshub.ph"
    override var lang = "tl"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override val hasDownloadSupport = true
    override val hasMainPage = true
    override val hasQuickSearch = false

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val all = ArrayList<HomePageList>()
        val doc = app.get(mainUrl).document
        val rows = mutableListOf(
            Pair("Suggestion", "div.items.featured"),
            Pair("Pinoy Movies and TV", "div.items.full"),
            //Pair("Pinoy Teleserye and TV Series", "tvload"),
            Pair("Action", "div#genre_action"),
            Pair("Comedy", "div#genre_comedy"),
            Pair("Romance", "div#genre_romance"),
            Pair("Horror", "div#genre_horror"),
            Pair("Drama", "div#genre_drama"),
        )
        if (settingsForProvider.enableAdult) {
            rows.add(Pair("Rated-R 18+", "genre_rated-r"))
        }
        //Log.i(TAG, "Parsing page..")
        val maindoc = doc.selectFirst("div.module")
            ?.select("div.content.full_width_layout.full")

        rows.forEach { pair ->
            // Fetch row title
            val title = pair.first
            // Fetch list of items and map
            //Log.i(TAG, "Title => $title")
            maindoc?.select(pair.second)?.let { inner ->
                //Log.i(TAG, "inner => $inner")
                val results = inner.select("article").getResults(this.name)
                if (results.isNotEmpty()) {
                    all.add(
                        HomePageList(
                            name = title,
                            list = results,
                            isHorizontalImages = false
                        )
                    )
                }
            }
        }
        return HomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/?s=${query}"
        return app.get(searchUrl).document
            .selectFirst("div#archive-content")
            ?.select("article")
            .getResults(this.name)
    }

    override suspend fun load(url: String): MovieLoadResponse {
        //TODO: Load polishing
        val doc = app.get(url).document
        val body = doc.getElementsByTag("body").firstOrNull()
        val sheader = body?.selectFirst("div.sheader")

        //Log.i(TAG, "Result => (url) ${url}")
        val poster = sheader?.selectFirst("div.poster > img")
            ?.attr("src")

        val title = sheader
            ?.selectFirst("div.data > h1")
            ?.text() ?: ""
        val descript = body?.selectFirst("div#info div.wp-content")?.text()
        val year = body?.selectFirst("span.date")?.text()?.trim()?.takeLast(4)?.toIntOrNull()

        //TODO: Parse episodes
        val episodes = body?.select("div.page-content-listing.single-page")
            ?.first()?.select("li")
        val episodeList = episodes?.mapNotNull {
            val innerA = it?.selectFirst("a") ?: return@mapNotNull null
            val eplink = innerA.attr("href") ?: return@mapNotNull null
            val epCount = innerA.text().trim().filter { a -> a.isDigit() }.toIntOrNull()
            val imageEl = innerA.selectFirst("img")
            val epPoster = imageEl?.attr("src") ?: imageEl?.attr("data-src")
            Episode(
                name = innerA.text(),
                data = eplink,
                posterUrl = epPoster,
                episode = epCount,
            )
        } ?: listOf()

        val dataUrl = doc.selectFirst("link[rel='shortlink']")
            ?.attr("href")
            ?.substringAfter("?p=") ?: ""
        //Log.i(TAG, "Result => (dataUrl) ${dataUrl}")

        //Log.i(TAG, "Result => (id) ${id}")
        return MovieLoadResponse(
            name = title,
            url = url,
            dataUrl = dataUrl,
            apiName = this.name,
            type = TvType.Movie,
            posterUrl = poster,
            year = year,
            plot = descript,
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        try {
            //Log.i(TAG, "Loading ajax request..")
            val requestLink = "${mainUrl}/wp-admin/admin-ajax.php"
            val action = "doo_player_ajax"
            val nume = "1"
            val type = "movie"
            val doc = app.post(
                url = requestLink,
                referer = mainUrl,
                headers = mapOf(
                    Pair("User-Agent", USER_AGENT),
                    Pair("Sec-Fetch-Mode", "cors")
                ),
                data = mapOf(
                    Pair("action", action),
                    Pair("post", data),
                    Pair("nume", nume),
                    Pair("type", type)
                )
            )
            //Log.i(TAG, "Response (${doc.code}) => ${doc.text}")
            AppUtils.tryParseJson<Response?>(doc.text)?.let {
                val streamLink = it.embed_url ?: ""
                //Log.i(TAG, "Response (streamLink) => ${streamLink}")
                if (streamLink.isNotBlank()) {
                    loadExtractor(
                        url = streamLink,
                        referer = mainUrl,
                        callback = callback,
                        subtitleCallback = subtitleCallback
                    )
                }
            }
        } catch (e: Exception) {
            //Log.i(TAG, "Error => $e")
            logError(e)
            return false
        }
        return true
    }

    private fun Elements?.getResults(apiName: String): List<SearchResponse> {
        return this?.mapNotNull {
            val divPoster = it.selectFirst("div.poster")
            val divData = it.selectFirst("div.data")

            val firstA = divData?.selectFirst("a")
            val link = fixUrlNull(firstA?.attr("href")) ?: return@mapNotNull null
            val qualString = divPoster?.select("span.quality")?.text()?.trim() ?: ""
            val qual = getQualityFromString(qualString)
            val tvtype = if (qualString.equals("TV")) { TvType.TvSeries } else { TvType.Movie }

            val name = divData?.selectFirst("a")?.text() ?: ""
            val year = divData?.selectFirst("span")?.text()
                ?.trim()?.takeLast(4)?.toIntOrNull()

            val imageDiv = divPoster?.selectFirst("img")
            var image = imageDiv?.attr("src")
            if (image.isNullOrBlank()) {
                image = imageDiv?.attr("data-src")
            }

            //Log.i(apiName, "Added => $name / $link")
            if (tvtype == TvType.TvSeries) {
                TvSeriesSearchResponse(
                    name = name,
                    url = link,
                    apiName = apiName,
                    type = tvtype,
                    posterUrl = image,
                    year = year,
                    quality = qual,
                )
            } else {
                MovieSearchResponse(
                    name = name,
                    url = link,
                    apiName = apiName,
                    type = tvtype,
                    posterUrl = image,
                    year = year,
                    quality = qual,
                )
            }
        } ?: listOf()
    }

    private data class Response(
        @JsonProperty("embed_url") val embed_url: String?
    )
}
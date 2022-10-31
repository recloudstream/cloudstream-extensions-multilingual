package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson


class EurostreamingProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://eurostreaming.boats"
    override var name = "Eurostreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/serie-tv-archive/page/", "Ultime serie Tv"),
        Pair("$mainUrl/animazione/page/", "Ultime serie Animazione"),

        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page

        val soup = app.get(url).document
        val home = soup.select("div.post-thumb").map {
            val title = it.selectFirst("img")!!.attr("alt")
            val link = it.selectFirst("a")!!.attr("href")
            val image = fixUrl(it.selectFirst("img")!!.attr("src"))

            MovieSearchResponse(
                title,
                link,
                this.name,
                TvType.Movie,
                image
            )
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.post(
            "$mainUrl/index.php", data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query,
                "sortby" to "news_read"
            )
        ).document
        return doc.select("div.post-thumb").map {
            val title = it.selectFirst("img")!!.attr("alt")
            val link = it.selectFirst("a")!!.attr("href")
            val image = mainUrl + it.selectFirst("img")!!.attr("src")

            MovieSearchResponse(
                title,
                link,
                this.name,
                TvType.Movie,
                image
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val page = app.get(url)
        val document = page.document
        val title = document.selectFirst("h2")!!.text().replace("^([1-9+]]$","")
        val style = document.selectFirst("div.entry-cover")!!.attr("style")
        val poster = fixUrl(Regex("(/upload.+\\))").find(style)!!.value.dropLast(1))
        val episodeList = ArrayList<Episode>()
        document.select("div.tab-pane.fade").map { element ->
            val season = element.attr("id").filter { it.isDigit() }.toInt()
            element.select("li").filter { it-> it.selectFirst("a")?.hasAttr("data-title")?:false }.map{episode ->
                val data = episode.select("div.mirrors > a").map { it.attr("data-link") }.toJson()
                val epnameData = episode.selectFirst("a")
                val epTitle = epnameData!!.attr("data-title")
                val epNum = epnameData.text().toInt()
                episodeList.add(
                    Episode(
                        data,
                        epTitle,
                        season,
                        epNum

                    )
                )
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            posterUrl = poster
        }

    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        parseJson<List<String>>(data).map { videoUrl ->
            loadExtractor(videoUrl, data, subtitleCallback, callback)
        }
        return true
    }
}

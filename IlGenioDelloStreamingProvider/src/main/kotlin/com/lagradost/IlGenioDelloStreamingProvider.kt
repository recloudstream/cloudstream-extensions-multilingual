package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ShortLink
import com.lagradost.cloudstream3.utils.loadExtractor



class IlGenioDelloStreamingProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://ilgeniodellostreaming.quest"
    override var name = "IlGenioDelloStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val mainPage = mainPageOf(
        Pair("$mainUrl/category/film/page/", "Film Popolari"),
        Pair("$mainUrl/category/serie-tv/page/", "Serie Tv Popolari"),
        Pair("$mainUrl/the-most-voted/page/", "I più votati"),
        Pair("$mainUrl/prime-visioni/page/", "Ultime uscite"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document
        val home = soup.select("div.items > article.item").map {
            val title = it.selectFirst("div.data > h3 > a")!!.text().substringBeforeLast("(").substringBeforeLast("[")
            val link = it.selectFirst("div.poster > a")!!.attr("href")
            val quality = getQualityFromString(it.selectFirst("span.quality")?.text())
            TvSeriesSearchResponse(
                title,
                link,
                this.name,
                TvType.Movie,
                it.selectFirst("img")!!.attr("src"),
                null,
                null,
                quality = quality
            )
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val queryformatted = query.replace(" ", "+")
        val url = "$mainUrl?s=$queryformatted"
        val doc = app.get(url,referer= mainUrl ).document
        return doc.select("div.result-item").map {
            val href = it.selectFirst("div.image > div > a")!!.attr("href")
            val poster = it.selectFirst("div.image > div > a > img")!!.attr("src")
            val name = it.selectFirst("div.details > div.title > a")!!.text().substringBeforeLast("(").substringBeforeLast("[")
            MovieSearchResponse(
                name,
                href,
                this.name,
                TvType.Movie,
                poster
            )

        }
    }

    override suspend fun load(url: String): LoadResponse {
        val page = app.get(url)
        val document = page.document
        val type = if (document.select("div.seasons-wraper").isNotEmpty()){TvType.TvSeries} else{TvType.Movie}
        val title = document.selectFirst("div.data > h1")!!.text().substringBefore("(").substringBefore("[")
        val description = document.selectFirst("div#info")?.selectFirst("p")?.html()
        val rating = document.select("span.valor").last()?.text()?.split(" ")?.get(0)
        var year = document.selectFirst(" div.data > div.extra > span.date")!!.text().substringAfter(",")
            .filter { it.isDigit() }
        if (year.length > 4) {
            year = year.dropLast(4)
        }

        val poster = document.selectFirst("div.poster > img")!!.attr("src")

        val recomm = document.select("article.w_item_b").map {
            val href = it.selectFirst("a")!!.attr("href")
            val posterUrl = it.selectFirst("img")!!.attr("src")
            val name = it.selectFirst("div.data > h3")!!.text().substringBeforeLast("(").substringBeforeLast("[")
            MovieSearchResponse(
                name,
                href,
                this.name,
                TvType.Movie,
                posterUrl
            )

        }


        if (type == TvType.TvSeries) {

            val episodeList = ArrayList<Episode>()
            document.selectFirst("div.seasons-wraper")
                ?.select("div.accordion-item ")?.groupBy {it.selectFirst("span.season-title")!!.text()  }?.map { seasons ->
                    seasons.value.map {season -> season.select("div.episode-wrap")}.flatten()
                        .groupBy { it.selectFirst("li.season-no")?.text()?.substringBeforeLast(" ") }
                        .map { episodeItaSub ->
                            val episodes = episodeItaSub.value
                            val posterUrl = episodes.firstNotNullOf { it.selectFirst("img")?.attr("src")}
                            val epName = episodes.firstNotNullOf{it.selectFirst("li.other_link")?.text()?:""}

                            episodes.map{ episode ->
                                val seasonNo =  episode.selectFirst("li.season-no")
                                val subtag = seasonNo?.text()?.takeIf {it.contains("Sub")}?.substringAfter(" ") ?: ""
                                val urls = episode.getElementsByAttributeValue("target", "_blank").map { it.attr("href").trim() }
                                    .filter { it.isNotEmpty()}.toJson()
                                episodeList.add(Episode(
                                    data = urls,
                                    posterUrl = posterUrl,
                                    season = seasons.key.toIntOrNull(),
                                    name = "$epName ${subtag.uppercase()}",
                                    episode = seasonNo?.text()?.substringAfter("x")?.filter { it.isDigit() }?.toIntOrNull()

                                ))
                            }



                        }
                }


            val seasonnames = document.selectFirst("div#info")?.select("p")?.map {it.children() }
                ?.filter { it.size<3 && it.isNotEmpty()}?.map{it.text()}

            return newTvSeriesLoadResponse(
                title,
                url,
                type,
                episodeList
            ){
                addRating(rating)
                this.plot = description
                this.year = year.toIntOrNull()
                this.posterUrl = poster
                this.recommendations = recomm
                this.seasonNames = seasonnames!!.mapIndexed { index, s -> SeasonData(index, s) }

            }


        } else {
            val actors: List<ActorData> =
                document.select("div.cast_wraper > ul > li").map { actorData ->
                    val actorName = actorData.children()[1].text()
                    val actorImage : String? = actorData.selectFirst("img")?.attr("data-src")
                    val roleActor = actorData.children()[2].text()
                    ActorData(actor = Actor(actorName, image = actorImage), roleString = roleActor )
                }
            return newMovieLoadResponse(
                title,
                url,
                type,
                (document.select("div.embed-player") + document.select("a.link_a")).map { (it.attr("href") + it.attr("data-id")).trim() }.distinct().toJson(),
            ) {
                posterUrl = fixUrlNull(poster)
                this.year = year.toIntOrNull()
                this.plot = description
                addRating(rating)
                this.recommendations = recomm
                this.duration = null
                this.actors = actors
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = tryParseJson<List<String>>(data)
        links?.map { link ->
            val url = ShortLink.unshorten(link).replace("/v/", "/e/").replace("/f/", "/e/")
            loadExtractor(url, data, subtitleCallback, callback)
        }
        return true
    }
}
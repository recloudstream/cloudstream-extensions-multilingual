
package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
// import com.lagradost.cloudstream3.extractors.Vudeo
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup


class MesFilmsProvider : MainAPI() {
    override var mainUrl = "https://mesfilms.lol"
    override var name = "Mes Films"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.AnimeMovie)

    override suspend fun search(query: String): List<SearchResponse> {

        val link = "$mainUrl/?s=$query"
        val html = app.get(link).text
        val document = Jsoup.parse(html)
        return document.select("div.search-page > div.result-item > article").filter { div ->

            val type = div?.selectFirst("> div.image > div.thumbnail > a > span")?.text()
                ?.replace("\t", "")?.replace("\n", "")

            type != "Épisode" // filter out 'episodes' todo (inefficient)
        }.apmap { div ->
            val posterContainer = div.selectFirst("> div.image > div.thumbnail > a")
            val type = posterContainer?.selectFirst("> span")?.text()?.replace("\t", "")?.replace("\n", "")
            val mediaPoster = posterContainer?.selectFirst("> img")?.attr("src")

            val href = posterContainer?.attr("href") ?: throw ErrorLoadingException("invalid link") // raise if null

            val details = div.select("> div.details > div.meta")
            //val rating = details.select("> span.rating").text()
            val year = details.select("> span.year").text()

            val title = div.selectFirst("> div.details > div.title > a")?.text().toString()

            when (type) {
                "Film" -> (
                    newMovieSearchResponse(
                        title,
                        href,
                        TvType.Movie,
                        false
                    ) {
                        this.posterUrl = mediaPoster
                        // this.rating = rating // todo, already in sunstream :)
                    }
                )
                "TV" -> (
                        newTvSeriesSearchResponse(
                            title,
                            href,
                            TvType.TvSeries,
                            false
                        ) {
                            this.posterUrl = mediaPoster
                            // this.rating = rating
                        }

                        )
                else -> {
                    throw ErrorLoadingException("invalid media type")
                }
            }
        }
    }

    private data class EmbedUrlClass(
        @JsonProperty("embed_url") val url: String?,
    )


    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        val meta = document.selectFirst("div.sheader")
        val poster = meta?.select("div.poster > img")?.attr("data-src")
        println(poster)
        val title = meta?.select("div.data > h1")?.text() ?: throw ErrorLoadingException("Invalid title")
        println(title)
        val data = meta.select("div.data")
        val extra = data.select("div.extra")

        val description = extra.select("span.tagline").first()?.text()

        val ratingValue = data.select("div.dt_rating_data > div.starstruck-rating > span.dt_rating_vgs").first()?.text()
        println(ratingValue)
        val rating = if (ratingValue == "0.0" || ratingValue.isNullOrEmpty()) {
            null // if empty or null, hide
            } else {
                ratingValue
            }
        val date = extra.select("span.date").first()?.text()?.takeLast(4)

        val tags = data.select("div.sgeneros > a").apmap {it.text()}

        val postId = document.select("#report-video-button-field > input[name=postID]").first()?.attr("value")

        val mediaType = if(url.contains("/film/")) {
            "movie"
            } else {
            "TV"
            }

        val trailerUrl = if (postId != null){
            val payloadRequest = mapOf("action" to "doo_player_ajax", "post" to postId, "nume" to "trailer", "type" to mediaType)
            val getTrailer =
                app.post("https://mesfilms.pw/wp-admin/admin-ajax.php", headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"), data = payloadRequest).text
            parseJson<EmbedUrlClass>(getTrailer).url
        } else {
            null
        }


        if (mediaType == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                addRating(rating)
                this.year = date?.toIntOrNull()
                this.tags = tags
                this.plot = description
                addTrailer(trailerUrl)
            }
        } else  // a tv serie
        {
            throw ErrorLoadingException("Nothing besides movies are implemented for this provider")
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(data).text
        val document = Jsoup.parse(html)

            document.select("ul#playeroptionsul > li:not(#player-option-trailer)").apmap { li ->
                val quality = li.selectFirst("span.title")?.text()
                val server = li.selectFirst("> span.server")?.text()
                val languageInfo =
                    li.selectFirst("span.flag > img")?.attr("data-src")?.substringAfterLast("/")
                        ?.replace(".png", "")
                val postId = li.attr("data-post")

                val indexOfPlayer = li.attr("data-nume")

                val payloadRequest = mapOf("action" to "doo_player_ajax", "post" to postId, "nume" to indexOfPlayer, "type" to "movie")
                val getPlayerEmbed =
                    app.post("https://mesfilms.pw/wp-admin/admin-ajax.php", headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"), data = payloadRequest).text
                val playerUrl = parseJson<EmbedUrlClass>(getPlayerEmbed).url
                val additionalInfo = listOf(quality, languageInfo)
                println("server: $server")
                println("playerUrl: $playerUrl")
                println("quality: $quality")

                if (playerUrl != null)
                loadExtractor(httpsify(playerUrl), playerUrl, subtitleCallback, callback, additionalInfo)
            }


        return true
    }


    override suspend fun getMainPage(): HomePageResponse? {
        val html = app.get("$mainUrl/tendance/?get=movies").text
        val document = Jsoup.parse(html)
        val movies = document.select("div.items > article.movies")
        val categoryTitle = document.select("div.content > header > h1").text() ?: "tendance"
        val returnList = movies.mapNotNull { article ->
            val poster = article.select("div.poster")
            val posterUrl = poster.select("> img").attr("data-src")
            val quality = getQualityFromString(poster.select("> div.mepo > span.quality").text())
            val rating = poster.select("> div.rating").text()
            //val link = poster.select("> a")?.attr("href")

            val data = article.select("div.data")
            val title = data.select("> h3 > a").text()
            val link = data.select("> h3 > a").attr("href")
            newMovieSearchResponse(
                title,
                link,
                TvType.Movie,
                false,
            ) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
            }
        if (returnList.isEmpty()) return null
        return HomePageResponse(listOf(HomePageList(categoryTitle, returnList)))
    }
}

package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import org.mozilla.javascript.ConsString
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

class GuardaSerieProvider : MainAPI() {
    override var mainUrl = "https://guardaserie.golf"
    override var name = "GuardaSerie"
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )
    override val mainPage = mainPageOf(
        Pair("$mainUrl/stagioni/page/", "Ultime Serie Tv"),
        Pair("$mainUrl/viste/page/", "Film e Serie più viste"),
        Pair("$mainUrl/votati/page/", "Ora al cinema")
    )
    companion object {
        private lateinit var token : String
    }

    data class QuickSearchParser (
        @JsonProperty ("title") val title : String? = null,
        @JsonProperty ("url") val url : String? = null,
        @JsonProperty ("img") val img : String? = null
    )


    override suspend fun search(query: String): List<SearchResponse> {
        val queryFormatted = query.replace(" ", "+")
        val url = "$mainUrl?s=$queryFormatted"
        val doc = app.get(url,referer= mainUrl ).document
        return doc.select("div.result-item").map {
            val href = it.selectFirst("div.image > div > a")!!.attr("href")
            val poster = it.selectFirst("div.image > div > a > img")!!.attr("src")
            val name = it.selectFirst("div.details > div.title > a")!!.text().substringBefore("(")
            MovieSearchResponse(
                name,
                href,
                this.name,
                TvType.Movie,
                poster
            )

        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val response = app.get("$mainUrl/wp-json/dooplay/search/?keyword=$query&nonce=$token").text
        val p =  tryParseJson<HashMap<String,QuickSearchParser>>(response)?.values?.map {
            MovieSearchResponse(
                name = it.title!!,
                url = it.url!!,
                posterUrl = it.img!!,
                type = TvType.Movie,
                apiName = this.name
            )
        }

        return p
    }
    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document

        token = Regex("nonce\":\"[^\"]*").find(app.get(mainUrl).toString())?.value?.drop(8)?:""
        val home = soup.select("article").map {
            val title = it.selectFirst("img")!!.attr("alt")
            val link = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst("img")!!.attr("src")
            val quality = getQualityFromString(it.selectFirst("span.quality")?.text())

            MovieSearchResponse(
                title,
                link,
                this.name,
                TvType.Movie,
                image,
                null,
                null,
                quality,
            )
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val name = document.selectFirst("h1")!!.text()
        val poster = document.selectFirst("div.poster")?.selectFirst("img")!!.attr("src")
        val rating = document.selectFirst("span.dt_rating_vgs")!!.text().toIntOrNull()
        val tags = document.select("div.sgeneros > a").map { it.text() }
        val actors = document.select("div.person").map {
            val actorName = it.selectFirst("div.name > a")!!.text()
            val actorPoster = it.selectFirst("img")?.attr("src")
            val role = it.selectFirst("div.caracter")?.text()
            ActorData(Actor(actorName, actorPoster), roleString = role )
        }
        if(url.contains("/film/")){
            val description = document.selectFirst("div.wp-content > p")!!.text()
            val year = document.selectFirst("span.date")!!.text().substringAfterLast(" ").toIntOrNull()
            val recomm = document.selectFirst("div.sbox.srelacionados")?.select("article")?.map{
                MovieSearchResponse(
                    name = it.selectFirst("img")!!.attr("alt"),
                    url = it.selectFirst("a")!!.attr("href"),
                    this.name,
                    TvType.Movie,
                    posterUrl = it.selectFirst("img")!!.attr("src"),
                    null
                )
            }
            val duration = document.selectFirst("div.runtime")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            return newMovieLoadResponse(
                name = name,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = description
                this.rating = rating
                this.recommendations = recomm
                this.duration = duration
                this.actors = actors
                this.tags = tags
            }
        }
        else{
            val episodes = document.select("#seasons > div").reversed().mapIndexed{season, data->
                data.select("li").mapIndexed { epNum , ep ->
                    val href = ep.selectFirst("a")!!.attr("href")
                    val epTitle = ep.selectFirst("a")?.text()
                    val posterUrl = ep.selectFirst("img")?.attr("src")
                    Episode(
                        href,
                        epTitle,
                        season + 1,
                        epNum + 1,
                        posterUrl,
                    )
                }

            }
            val plot = document.selectFirst("#info > div.wp-content > p")?.text()
            return newTvSeriesLoadResponse(
                name = name,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes.flatten(),
            ){
                this.posterUrl = poster
                this.rating = rating
                this.tags = tags
                this.actors = actors
                this.plot = plot
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val type = if( data.contains("film") ){"movie"} else {"tv"}
        val idpost = doc.select("#player-option-1").attr("data-post")
        val postInfo = app.post("$mainUrl/wp-admin/admin-ajax.php", headers = mapOf(
            "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
        ), data = mapOf(
            "action" to "doo_player_ajax",
            "post" to idpost,
            "nume" to "1",
            "type" to type,
        ))

        val url= Regex("""src='((.|\\n)*?)'""").find(postInfo.text)?.groups?.get(1)?.value.toString()
        val streamUrl = app.get(url, headers = mapOf("referer" to mainUrl)).url
        return loadExtractor(streamUrl, data, subtitleCallback, callback)
    }
}
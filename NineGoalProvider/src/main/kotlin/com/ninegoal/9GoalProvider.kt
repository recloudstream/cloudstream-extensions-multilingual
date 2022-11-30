package com.ninegoal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL
import java.net.UnknownHostException
import java.util.*

data class Data (
    @JsonProperty("id"           ) var id           : String?     = null,
    @JsonProperty("name"         ) var name         : String?     = null,
    @JsonProperty("slug"         ) var slug         : String?     = null,
    @JsonProperty("home"         ) var home         : Home?       = Home(),
    @JsonProperty("away"         ) var away         : Away?       = Away(),
    @JsonProperty("scores"       ) var scores       : Scores?     = Scores(),
    @JsonProperty("is_live"      ) var isLive       : Boolean?    = null
)
data class Home (
    @JsonProperty("model_id" ) var modelId : String? = null,
    @JsonProperty("name"     ) var name    : String? = null,
    @JsonProperty("slug"     ) var slug    : String? = null,
    @JsonProperty("logo"     ) var logo    : String? = null
)
data class Away (
    @JsonProperty("model_id" ) var modelId : String? = null,
    @JsonProperty("name"     ) var name    : String? = null,
    @JsonProperty("slug"     ) var slug    : String? = null,
    @JsonProperty("logo"     ) var logo    : String? = null
)
data class Scores (
    @JsonProperty("home" ) var home : Int? = null,
    @JsonProperty("away" ) var away : Int? = null
)
data class matchesJSON (
    @JsonProperty("data" ) var data : ArrayList<Data> = arrayListOf()
)
data class oneMatch (
    @JsonProperty("data" ) var data : Data? = Data()
)


data class PlayUrls (
    @JsonProperty("name" ) var name : String? = null,
    @JsonProperty("cdn"  ) var cdn  : String? = null,
    @JsonProperty("slug" ) var slug : String? = null,
    @JsonProperty("url"  ) var url  : String? = null,
    @JsonProperty("role" ) var role : String? = null
)
data class sourceData (
    @JsonProperty("id"          ) var id         : String?             = null,
    @JsonProperty("name"        ) var name       : String?             = null,
    @JsonProperty("slug"        ) var slug       : String?             = null,
    @JsonProperty("has_lineup"  ) var hasLineup  : Boolean?            = null,
    @JsonProperty("has_tracker" ) var hasTracker : Boolean?            = null,
    @JsonProperty("play_urls"   ) var playUrls   : ArrayList<PlayUrls> = arrayListOf()
)
data class sourcesJSON (
    @JsonProperty("data" ) var data : sourceData? = sourceData()
)

class NineGoal : MainAPI() {
    override var mainUrl = "https://9goaltv.to"
    override var name = "9Goal"
    override var lang = "en"
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val apiUrl = doc.select("head > script")
            .firstOrNull { it.html().contains("window.api_base_url") }.let {
            Regex("""window\.api_base_url = "(.*)";""").find(it?.html() ?: "")?.groupValues?.lastOrNull()
        } ?: "https://justameanlessdomain.com"
        val matchesData = parseJson<matchesJSON>(app.get("$apiUrl/v1/match/featured").text)
        val liveHomePageList = matchesData.data.filter { it.isLive == true }.map {
            LiveSearchResponse(
                it.name ?: "",
                apiUrl + "/v1/match/" + it.id,
                this@NineGoal.name,
                TvType.Live,
                "https://img.zr5.repl.co/vs?title=${it.name}&home=${it.home?.logo}&away=${it.away?.logo}&live=true",
            )
        }
        val featuredHomePageList = matchesData.data.filter { it.isLive == false }.map {
            LiveSearchResponse(
                it.name ?: "",
                apiUrl + "/v1/match/" + it.id,
                this@NineGoal.name,
                TvType.Live,
                "https://img.zr5.repl.co/vs?title=${it.name}&home=${it.home?.logo}&away=${it.away?.logo}",
            )
        }
        return HomePageResponse(
            arrayListOf(
                HomePageList("Live", liveHomePageList, isHorizontalImages = true),
                HomePageList("Featured", featuredHomePageList, isHorizontalImages = true)
            )
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val json = parseJson<oneMatch>(app.get(url).text).data
        return LiveStreamLoadResponse(
            json?.name ?: "",
            url,
            this.name,
            "$url/stream",
            "https://img.zr5.repl.co/vs?title=${json?.name}&home=${json?.home?.logo}&away=${json?.away?.logo}&live=${json?.isLive}"
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sourcesData = parseJson<sourcesJSON>(app.get(data).text).data
        sourcesData?.playUrls?.apmap {
            val quality = it.name?.substringAfter("(")?.substringBefore(")").let { qualityText ->
                when (qualityText) {
                    "Full HD" -> 1080
                    "HD" -> 720
                    "SD" -> 480
                    else -> Qualities.Unknown.value
                }
            }
            val language = it.name?.replace("""\(.*""".toRegex(), "") ?: ""
            val url = URL(it.url)
            val requestStatus = try {
                app.head(
                    fixUrl(
                        it.url ?: ""
                    )
                ).isSuccessful
            } catch (e: UnknownHostException) {
                false
            }
            if (!requestStatus) {
                    mapOf(
                        "(1)" to "https://playing.smoothlikebutterstream.com",
                        "(2)" to "https://playing.tunnelcdnsw.net",
                        "(3)" to "https://playing.goforfreedomwme.net",
                        "(4)" to "https://turnthe.gameon.tel",
                        "(5)" to "https://playing.whydontyoustreamwme.com"
                    ).apmap { (name, value) ->
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                "$language $name",
                                it.url?.replace(url.host, value) ?: "",
                                "$mainUrl/",
                                quality,
                                isM3u8 = true,
                            )
                        )
                    }
                } else {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            "$language ${sourcesData.name}",
                            it.url ?: "",
                            "$mainUrl/",
                            quality,
                            isM3u8 = true,
                        )
                    )
                }
        }
        return true
    }
}

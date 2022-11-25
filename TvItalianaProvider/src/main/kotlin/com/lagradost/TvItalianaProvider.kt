package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.UUID

class TvItalianaProvider : MainAPI() {
    override var lang = "it"
    override var name = "TvItaliana"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Live,
    )

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse {
        val iptvUrl = "https://raw.githubusercontent.com/Tundrak/IPTV-Italia/main/iptvitaplus.m3u"
        val data = IptvPlaylistParser().parseM3U(app.get(iptvUrl).text)
        val res =  data.items.groupBy{it.attributes["group-title"]}.map { group ->
                val title = group.key ?: ""
                val show = group.value.map { channel ->
                    val streamurl = channel.url.toString()
                    val channelname = channel.title.toString()
                    val posterurl = channel.attributes["tvg-logo"].toString()
                    val nation = channel.attributes["group-title"].toString()
                    LiveSearchResponse(
                        channelname,
                        LoadData(streamurl, channelname, posterurl, nation, false).toJson(),
                        this@TvItalianaProvider.name,
                        TvType.Live,
                        posterurl,
                        lang = "ita"
                    )
                }
                HomePageList(
                    title,
                    show,
                    isHorizontalImages = true
                )
            }.toMutableList()

        val skyStreams = listOf(7,2,1).map{ n ->
           app.get("https://apid.sky.it/vdp/v1/getLivestream?id=$n").parsedSafe<LivestreamResponse>()}

        val shows = skyStreams.map {
            val posterUrl = when (it?.title){
                "MTV8" -> "https://upload.wikimedia.org/wikipedia/commons/b/ba/MTV8_logo.jpg"
                else -> "https://upload.wikimedia.org/wikipedia/commons/thumb/b/bf/Sky_italia_2018.png/640px-Sky_italia_2018.png"
            }
            LiveSearchResponse(
                it?.title!!,
                LoadData(it.streamingUrl!!, it.title!!, posterUrl, "", false).toJson(),
                this@TvItalianaProvider.name,
                TvType.Live,
                posterUrl,
                lang = "ita"
            )
        }
        res.add(
            HomePageList(
                "sky italia",
                shows,
                isHorizontalImages = true
            )
        )

        val domain = "https://" + app.get("https://prod-realmservice.mercury.dnitv.com/realm-config/www.discoveryplus.com%2Fit%2Fepg").parsedSafe<DomainDiscovery>()?.domain
        val deviceId = UUID.randomUUID().toString().replace("-","")
        val cookies = app.get("$domain/token?deviceId=$deviceId&realm=dplay&shortlived=true").cookies
        val streamDatas = app.get("$domain/cms/routes/home?include=default&decorators=playbackAllowed", cookies = cookies).parsedSafe<DataDiscovery>()?.included
        val posterValues = streamDatas?.filter { it.type == "image" }
            ?.map { it.id to it.attributes?.src }
        val discoveryinfo = streamDatas?.filter { it.type == "channel" && it.attributes?.hasLiveStream == true && it.attributes.packages?.contains("Free") ?: false  }
            ?.map { streamInfo ->
                val posterUrl = posterValues?.find { it.first == streamInfo.relationships?.images?.data?.first()?.id }?.second!!
                LiveSearchResponse(
                    streamInfo.attributes?.name!!,
                    LoadData(streamInfo.id, streamInfo.attributes.name, posterUrl, streamInfo.attributes.longDescription!!, true).toJson(),
                    this@TvItalianaProvider.name,
                    TvType.Live,
                    posterUrl,
                    lang = "ita"
                )
            }
        res.add(
            HomePageList(
                "Discovery",
                discoveryinfo!!,
                isHorizontalImages = true
            )
        )

        return HomePageResponse(res)


    }

    override suspend fun search(query: String): List<SearchResponse> {
        val data = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)

        return data.items.filter { it.attributes["tvg-id"]?.contains(query) ?: false }.map { channel ->
            val streamurl = channel.url.toString()
            val channelname = channel.attributes["tvg-id"].toString()
            val posterurl = channel.attributes["tvg-logo"].toString()
            val nation = channel.attributes["group-title"].toString()
            LiveSearchResponse(
                channelname,
                LoadData(streamurl, channelname, posterurl, nation,false).toJson(),
                this@TvItalianaProvider.name,
                TvType.Live,
                posterurl,
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LoadData>(url)

        return LiveStreamLoadResponse(
            data.title,
            data.url,
            this.name,
            url,
            data.poster,
            plot = data.plot
        )
    }
    data class LoadData(
        val url: String,
        val title: String,
        val poster: String,
        val plot: String,
        val discoveryBoolean: Boolean

    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {


        val loadData = parseJson<LoadData>(data)

        if (!loadData.discoveryBoolean) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    loadData.title,
                    loadData.url,
                    "",
                    Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
        else{
            val domain = "https://" + app.get("https://prod-realmservice.mercury.dnitv.com/realm-config/www.discoveryplus.com%2Fit%2Fepg").parsedSafe<DomainDiscovery>()?.domain
            val deviceId = UUID.randomUUID().toString()
            val cookies = app.get("$domain/token?deviceId=$deviceId&realm=dplay&shortlived=true").cookies

            val post = PostData(loadData.url, DeviceInfo(ad = false, dmr = true)).toJson()
            val data = app.post("$domain/playback/v3/channelPlaybackInfo", requestBody = post.toRequestBody(
                RequestBodyTypes.JSON.toMediaTypeOrNull()), cookies = cookies).text.substringAfter("\"url\" : \"").substringBefore("\"")
            callback.invoke(
                ExtractorLink(
                    this.name,
                    loadData.title,
                    data,
                    "",
                    Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
        return true
    }
    data class PostData(
        @JsonProperty("channelId") val id: String,
        @JsonProperty("deviceInfo") val deviceInfo : DeviceInfo
    )
    data class DeviceInfo(
        @JsonProperty("drmSupported") val dmr : Boolean,
        @JsonProperty("adBlocker") val ad: Boolean,
    )
    data class DomainDiscovery(
        @JsonProperty("domain") val domain: String,
    )
    data class DataDiscovery(
        val included: List<Included>? = null
    )

    data class Included(
        val attributes: IncludedAttributes? = null,
        val id: String,
        val relationships : IncludedRelationships? = null,
        val type: String
    )

    data class IncludedRelationships(
         val images: ImagesData? = null
    )

    data class ImagesData (
        val data: List<DAT>? = null
    )
    data class DAT (
        val id: String? = null,
    )


    data class IncludedAttributes(
        val name: String?,
        val hasLiveStream : Boolean?,
        val packages: List<String>?,
        val longDescription: String?,
        val src: String?
    )

}


data class Playlist(
    val items: List<PlaylistItem> = emptyList(),
)
data class LivestreamResponse(
    @JsonProperty("channel") val channel: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("streaming_url") val streamingUrl: String? = null,

)
data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null,
)


class IptvPlaylistParser {


    /**
     * Parse M3U8 string into [Playlist]
     *
     * @param content M3U8 content string.
     * @throws PlaylistParserException if an error occurs.
     */
    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    /**
     * Parse M3U8 content [InputStream] into [Playlist]
     *
     * @param input Stream of input data.
     * @throws PlaylistParserException if an error occurs.
     */
    @Throws(PlaylistParserException::class)
    fun parseM3U(input: InputStream): Playlist {
        val reader = input.bufferedReader()

        if (!reader.readLine().isExtendedM3u()) {
            throw PlaylistParserException.InvalidHeader()
        }

        val playlistItems: MutableList<PlaylistItem> = mutableListOf()
        var currentIndex = 0

        var line: String? = reader.readLine()

        while (line != null) {
            if (line.isNotEmpty()) {
                if (line.startsWith(EXT_INF)) {
                    val title = line.getTitle()
                    val attributes = line.getAttributes()
                    playlistItems.add(PlaylistItem(title, attributes))
                } else if (line.startsWith(EXT_VLC_OPT)) {
                    val item = playlistItems[currentIndex]
                    val userAgent = line.getTagValue("http-user-agent")
                    val referrer = line.getTagValue("http-referrer")
                    val headers = if (referrer != null) {
                        item.headers + mapOf("referrer" to referrer)
                    } else item.headers
                    playlistItems[currentIndex] =
                        item.copy(userAgent = userAgent, headers = headers)
                } else {
                    if (!line.startsWith("#")) {
                        val item = playlistItems[currentIndex]
                        val url = line.getUrl()
                        val userAgent = line.getUrlParameter("user-agent")
                        val referrer = line.getUrlParameter("referer")
                        val urlHeaders = if (referrer != null) {
                            item.headers + mapOf("referrer" to referrer)
                        } else item.headers
                        playlistItems[currentIndex] =
                            item.copy(
                                url = url,
                                headers = item.headers + urlHeaders,
                                userAgent = userAgent
                            )
                        currentIndex++
                    }
                }
            }

            line = reader.readLine()
        }
        return Playlist(playlistItems)
    }

    /**
     * Replace "" (quotes) from given string.
     */
    private fun String.replaceQuotesAndTrim(): String {
        return replace("\"", "").trim()
    }

    /**
     * Check if given content is valid M3U8 playlist.
     */
    private fun String.isExtendedM3u(): Boolean = startsWith(EXT_M3U)

    /**
     * Get title of media.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTINF:-1 tvg-id="1234" group-title="Kids" tvg-logo="url/to/logo", Title
     * ```
     * Result: Title
     */
    private fun String.getTitle(): String? {
        return split(",").lastOrNull()?.replaceQuotesAndTrim()
    }

    /**
     * Get media url.
     *
     * Example:-
     *
     * Input:
     * ```
     * https://example.com/sample.m3u8|user-agent="Custom"
     * ```
     * Result: https://example.com/sample.m3u8
     */
    private fun String.getUrl(): String? {
        return split("|").firstOrNull()?.replaceQuotesAndTrim()
    }

    /**
     * Get url parameters.
     *
     * Example:-
     *
     * Input:
     * ```
     * http://192.54.104.122:8080/d/abcdef/video.mp4|User-Agent=Mozilla&Referer=CustomReferrer
     * ```
     * Result will be equivalent to kotlin map:
     * ```Kotlin
     * mapOf(
     *   "User-Agent" to "Mozilla",
     *   "Referer" to "CustomReferrer"
     * )
     * ```
     */
    private fun String.getUrlParameters(): Map<String, String> {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val headersString = replace(urlRegex, "").replaceQuotesAndTrim()
        return headersString.split("&").mapNotNull {
            val pair = it.split("=")
            if (pair.size == 2) pair.first() to pair.last() else null
        }.toMap()
    }

    /**
     * Get url parameter with key.
     *
     * Example:-
     *
     * Input:
     * ```
     * http://192.54.104.122:8080/d/abcdef/video.mp4|User-Agent=Mozilla&Referer=CustomReferrer
     * ```
     * If given key is `user-agent`, then
     *
     * Result: Mozilla
     */
    private fun String.getUrlParameter(key: String): String? {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val keyRegex = Regex("$key=(\\w[^&]*)", RegexOption.IGNORE_CASE)
        val paramsString = replace(urlRegex, "").replaceQuotesAndTrim()
        return keyRegex.find(paramsString)?.groups?.get(1)?.value
    }

    /**
     * Get attributes from `#EXTINF` tag as Map<String, String>.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTINF:-1 tvg-id="1234" group-title="Kids" tvg-logo="url/to/logo", Title
     * ```
     * Result will be equivalent to kotlin map:
     * ```Kotlin
     * mapOf(
     *   "tvg-id" to "1234",
     *   "group-title" to "Kids",
     *   "tvg-logo" to "url/to/logo"
     *)
     * ```
     */
    private fun String.getAttributes(): Map<String, String> {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val attributesString = replace(extInfRegex, "").replaceQuotesAndTrim().split(",").first()
        return attributesString.split(Regex("\\s")).mapNotNull {
            val pair = it.split("=")
            if (pair.size == 2) pair.first() to pair.last()
                .replaceQuotesAndTrim() else null
        }.toMap()
    }

    /**
     * Get value from a tag.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTVLCOPT:http-referrer=http://example.com/
     * ```
     * Result: http://example.com/
     */
    private fun String.getTagValue(key: String): String? {
        val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)
        return keyRegex.find(this)?.groups?.get(1)?.value?.replaceQuotesAndTrim()
    }

    companion object {
        const val EXT_M3U = "#EXTM3U"
        const val EXT_INF = "#EXTINF"
        const val EXT_VLC_OPT = "#EXTVLCOPT"
    }

}

/**
 * Exception thrown when an error occurs while parsing playlist.
 */
sealed class PlaylistParserException(message: String) : Exception(message) {

    /**
     * Exception thrown if given file content is not valid.
     */
    class InvalidHeader :
        PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")

}
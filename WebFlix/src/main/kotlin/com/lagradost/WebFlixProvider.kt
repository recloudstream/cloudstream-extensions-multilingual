package com.lagradost

import android.util.Base64
import android.util.Log
import com.lagradost.WebFlixProvider.Companion.toHomePageList
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder


class WebFlixProvider(override var lang: String, override var mainUrl: String, override var name: String, override val supportedTypes: Set<TvType>) : MainAPI() {
    val magicPath = base64Decode("NEY1QTlDM0Q5QTg2RkE1NEVBQ0VEREQ2MzUxODUvZDUwNmFiZmQtOWZlMi00YjcxLWI5NzktZmVmZjIxYmNhZDEzLw==")
    override val hasMainPage = true
    override val hasChromecastSupport = true

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse? {
        val res = tryParseJson<HomeResponse>(app.get("$mainUrl/api/first/$magicPath").text) ?: return null
        return HomePageResponse(
            res.getHomePageLists(this),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val res = tryParseJson<ApiSearchResponse>(app.get("$mainUrl/api/search/${query.encodeUri()}/$magicPath").text) ?: return null
        return res.posters.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<Entry>(app.get(url).text) ?: return null
        return data.toLoadResponse(this)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = tryParseJson<List<Source>>(data) ?: return false
        sources.forEach {
            it.load(subtitleCallback, callback)
        }
        return true
    }

    private data class ApiSearchResponse(
        val posters: List<Entry>
    )

    private data class HomeResponse(
        val genres: List<HomeReponseGenre> = emptyList(),
        val channels: List<Entry> = emptyList(),
        // val slides: List<Entry> = emptyList()
    ) {
        fun getHomePageLists(provider: WebFlixProvider): List<HomePageList> {
            val lists = mutableListOf<HomePageList>()
            if (channels.isNotEmpty()) {
                channels.forEach {
                    if (it.type == null) it.type = "channel"
                }
                lists.add(channels.toHomePageList("Channels", provider))
            }
            //if (slides.isNotEmpty()) lists.add(slides.toHomePageList("Slides", provider))
            lists.addAll(genres.map { it.toHomePageList(provider) })
            return lists
        }
    }
    private data class HomeReponseGenre(
        val title: String,
        val posters: List<Entry>
    ) {
        fun toHomePageList(provider: WebFlixProvider) = posters.toHomePageList(title, provider)
    }

    data class Source(
        val title: String?,
        val url: String?,
        val quality: String?,
    ) {
        suspend fun load(subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            if (url == null) return;
            when (url.split(".").last()) {
                "mp4", "m3u8", "mov" -> callback.invoke(
                    ExtractorLink(
                        quality ?: "",
                        title ?: "",
                        url,
                        "",
                        Qualities.Unknown.value,
                        isM3u8 = (url.endsWith("m3u8"))
                    ))
                else -> loadExtractor(url, subtitleCallback, callback)
            }
        }
    }

    private data class ApiEpisode(
        val id: Int,
        val title: String?,
        val description: String?,
        val sources: List<Source> = emptyList()
    ) {
        fun toEpisode(season: Int, episode: Int) = Episode(
            sources.toJson(),
            title,
            season,
            episode,
            null,
            null,
            description
        )
    }

    private data class ApiSeason(
        val title: String?,
        val episodes: List<ApiEpisode> = emptyList()
    ) {
        fun getEpisodes(season: Int): List<Episode> = episodes.mapIndexed { idx, episode -> episode.toEpisode(season, idx + 1) }
    }

    data class Entry(
        val id: Int,
        val title: String,
        val label: String?,
        val sublabel: String?,
        val image: String?,
        val description: String?,
        var type: String?,
        val year: String?,
        val imdb: Double?,
        val sources: List<Source> = emptyList()
    ) {
        fun getTvType() = when (type) {
            "serie" -> TvType.TvSeries
            "movie" -> TvType.Movie
            "channel", "4" -> TvType.Live
            else -> {
                Log.d("WebFlix", "other: $type")
                TvType.Others
            }
        }

        fun toSearchResponse(provider: WebFlixProvider): SearchResponse {
            val entry = this
            return when(getTvType()) {
                TvType.Movie -> provider.newMovieSearchResponse(
                    title,
                    "${provider.mainUrl}/api/movie/by/$id/${provider.magicPath}",
                    getTvType(),
                ) {
                    posterUrl = image
                    year = entry.year?.toIntOrNull()
                }
                TvType.TvSeries -> provider.newTvSeriesSearchResponse(
                    title,
                    "${provider.mainUrl}/api/movie/by/$id/${provider.magicPath}",
                    TvType.TvSeries
                ) {
                    posterUrl = image
                    //year = entry.year?.toIntOrNull()
                }
                TvType.Live -> provider.newMovieSearchResponse(
                    title,
                    "${provider.mainUrl}/api/channel/by/$id/${provider.magicPath}",
                    getTvType(),
                ) {
                    posterUrl = image
                    year = entry.year?.toIntOrNull()
                }
                else -> provider.newMovieSearchResponse(
                    title,
                    "${provider.mainUrl}/api/$type/by/$id/${provider.magicPath}",
                    getTvType(),
                ) {
                    posterUrl = image
                    year = entry.year?.toIntOrNull()
                }
            }
        }

        suspend fun toLoadResponse(provider: WebFlixProvider): LoadResponse? {
            val entry = this
            return when(getTvType()) {
                TvType.TvSeries -> {
                    val res = tryParseJson<List<ApiSeason>>(app.get("${provider.mainUrl}/api/season/by/serie/${id}/${provider.magicPath}").text) ?: return null
                    provider.newTvSeriesLoadResponse(
                        title,
                        "",
                        TvType.TvSeries,
                        res.mapIndexed { idx, season -> season.getEpisodes(idx + 1) }
                            .flatten()
                    ) {
                        this.posterUrl = entry.image
                        this.year = entry.year?.toIntOrNull()
                        this.plot = description
                        this.rating = if (entry.imdb != null) (entry.imdb*10).toInt() else null
                    }
                }
                else -> provider.newMovieLoadResponse(
                    title,
                    "",
                    getTvType(),
                    sources.toJson()
                ) {
                    this.posterUrl = entry.image
                    this.year = entry.year?.toIntOrNull()
                    this.plot = description
                    this.rating = if (entry.imdb != null) (entry.imdb*10).toInt() else null
                }
            }
        }
    }

    companion object {
        fun String.encodeUri() = URLEncoder.encode(this, "utf8")
        fun List<Entry>.toHomePageList(name: String, provider: WebFlixProvider) =  HomePageList(name, this.map { it.toSearchResponse(provider) })
    }
}
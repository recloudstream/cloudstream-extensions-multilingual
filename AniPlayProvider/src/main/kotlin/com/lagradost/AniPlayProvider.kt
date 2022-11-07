package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class AniPlayProvider : MainAPI() {
    override var mainUrl = "https://aniplay.it"
    override var name = "AniPlay"
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = true
    private val dubIdentifier = " (ITA)"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getStatus(t: String?): ShowStatus? {
            return when (t?.lowercase()) {
                "completato" -> ShowStatus.Completed
                "in corso" -> ShowStatus.Ongoing
                else -> null // "annunciato"
            }
        }
        fun getType(t: String?): TvType {
            return when (t?.lowercase()) {
                "ona" -> TvType.OVA
                "movie" -> TvType.AnimeMovie
                else -> TvType.Anime //"serie", "special"
            }
        }
    }

    private fun isDub(title: String): Boolean{
        return title.contains(dubIdentifier)
    }

    data class ApiPoster(
        @JsonProperty("imageFull") val posterUrl: String
    )

    data class ApiMainPageAnime(
        @JsonProperty("animeId") val id: Int,
        @JsonProperty("id") val id2: Int,
        @JsonProperty("episodeNumber") val episode: String?,
        @JsonProperty("animeTitle") val title: String?,
        @JsonProperty("title") val title2: String?,
        @JsonProperty("animeType") val type: String?,
        @JsonProperty("type") val type2: String?,
        @JsonProperty("fullHd") val fullHD: Boolean,
        @JsonProperty("animeVerticalImages") val posters: List<ApiPoster>?,
        @JsonProperty("verticalImages") val posters2: List<ApiPoster>?
    )

    data class ApiSearchResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String,
        @JsonProperty("status") val status: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("verticalImages") val posters: List<ApiPoster>
    )

    data class ApiGenres(
        @JsonProperty("description") val name: String
    )
    data class ApiWebsite(
        @JsonProperty("listWebsiteId") val websiteId: Int,
        @JsonProperty("url") val url: String
    )

    data class ApiEpisode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("episodeNumber") val number: String,
    )

    private fun ApiEpisode.toEpisode() : Episode? {
        val number = this.number.toIntOrNull() ?: return null
        return Episode(
            data = "$mainUrl/api/episode/${this.id}",
            episode = number,
            name = this.title
        )
    }

    data class ApiSeason(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String
    )

    private suspend fun ApiSeason.toEpisodeList(url: String) : List<Episode> {
        return parseJson<List<ApiEpisode>>(app.get("$url/season/${this.id}").text).mapNotNull { it.toEpisode() }
    }

    data class ApiAnime(
        @JsonProperty("title") val title: String,
        @JsonProperty("alternativeTitle") val japTitle: String?,
        @JsonProperty("episodeDuration") val duration: Int,
        @JsonProperty("storyline") val plot: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("status") val status: String,
        @JsonProperty("genres") val genres: List<ApiGenres>,
        @JsonProperty("verticalImages") val posters: List<ApiPoster>,
        @JsonProperty("horizontalImages") val horizontalPosters: List<ApiPoster>,
        @JsonProperty("listWebsites") val websites: List<ApiWebsite>,
        @JsonProperty("episodes") val episodes: List<ApiEpisode>,
        @JsonProperty("seasons") val seasons: List<ApiSeason>?
    )

    data class ApiEpisodeUrl(
        @JsonProperty("videoUrl") val url: String
    )
    override val mainPage = mainPageOf(
        Pair("$mainUrl/api/home/latest-episodes?page=", "Ultime uscite"),
        Pair("$mainUrl/api/anime/advanced-search?size=36&sort=views,desc&sort=id&page=", "I più popolari"),
        )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val response = parseJson<List<ApiMainPageAnime>>(app.get(request.data + page).text)

        val results = response.mapNotNull{
            val title = it.title?:it.title2?: return@mapNotNull null
            val isDub = isDub(title)
            val id = if (it.id == 0) it.id2 else it.id
            newAnimeSearchResponse(
                name = if (isDub) title.replace(dubIdentifier, "") else title,
                url = "$mainUrl/api/anime/$id",
                type = getType(it.type?:it.type2),
            ){
                addDubStatus(isDub, it.episode?.toIntOrNull())
                this.posterUrl = (it.posters?:it.posters2!!).first().posterUrl
                this.quality = if (it.fullHD) SearchQuality.HD else null
            }
        }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val response = parseJson<List<ApiSearchResult>>(app.get("$mainUrl/api/anime/search?query=$query").text)

        return response.map {
            val isDub = isDub(it.title)

            newAnimeSearchResponse(
                name = if (isDub) it.title.replace(dubIdentifier, "") else it.title,
                url = "$mainUrl/api/anime/${it.id}",
                type = getType(it.type),
            ){
                addDubStatus(isDub)
                this.posterUrl = it.posters.first().posterUrl
            }
        }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val response = parseJson<List<ApiSearchResult>>(app.get("$mainUrl/api/anime/advanced-search?page=0&size=36&query=$query").text)

        return response.map {
            val isDub = isDub(it.title)

            newAnimeSearchResponse(
                name = if (isDub) it.title.replace(dubIdentifier, "") else it.title,
                url = "$mainUrl/api/anime/${it.id}",
                type = getType(it.type),
            ){
                addDubStatus(isDub)
                this.posterUrl = it.posters.first().posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val response = parseJson<ApiAnime>(app.get(url).text)

        val tags: List<String> = response.genres.map { it.name }

        val malId: Int? = response.websites.find { it.websiteId == 1 }?.url?.removePrefix("https://myanimelist.net/anime/")?.split("/")?.first()?.toIntOrNull()
        val aniListId: Int? = response.websites.find { it.websiteId == 4 }?.url?.removePrefix("https://anilist.co/anime/")?.split("/")?.first()?.toIntOrNull()

        val episodes = if (response.seasons.isNullOrEmpty()) response.episodes.mapNotNull { it.toEpisode() } else response.seasons.map{ it.toEpisodeList(url) }.flatten()
        val isDub = isDub(response.title)

        return newAnimeLoadResponse(response.title, url, getType(response.type)) {
            this.name = if (isDub) response.title.replace(dubIdentifier, "") else response.title
            this.japName = response.japTitle
            this.plot = response.plot
            this.tags = tags
            this.showStatus = getStatus(response.status)
            addPoster(response.horizontalPosters.firstOrNull()?.posterUrl)
            addEpisodes(if (isDub) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            addMalId(malId)
            addAniListId(aniListId)
            addDuration(response.duration.toString())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episode = parseJson<ApiEpisodeUrl>(app.get(data).text)

        callback.invoke(
            ExtractorLink(
                name,
                name,
                episode.url,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = episode.url.contains(".m3u8"),
            )
        )
        return true
    }
}
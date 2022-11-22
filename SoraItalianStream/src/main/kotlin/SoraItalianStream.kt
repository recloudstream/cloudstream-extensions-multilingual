package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.SoraItalianExtractor.invoGuardare
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.SoraItalianExtractor.invoAltadefinizione
import com.lagradost.SoraItalianExtractor.invoAniPlay
import com.lagradost.SoraItalianExtractor.invoAnimeSaturn
import com.lagradost.SoraItalianExtractor.invoAnimeWorld
import com.lagradost.SoraItalianExtractor.invoCb01
import com.lagradost.SoraItalianExtractor.invoFilmpertutti
import com.lagradost.SoraItalianExtractor.invoGuardaserie
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.math.roundToInt

open class SoraItalianStream : TmdbProvider() {
    override var name = "SoraStreamItaliano"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    /** AUTHOR : Adippe & Hexated & Sora */
    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "71f37e6dff3b879fa4656f19547c418c" // PLEASE DON'T STEAL
        const val guardaserieUrl = "https://guardaserie.app"
        const val filmpertuttiUrl = "https://www.filmpertutti.skin"
        const val altadefinizioneUrl = "https://altadefinizione01.autos"
        const val cb01Url = "https://cb01.delivery"
        const val animeworldUrl = "https://www.animeworld.tv"
        const val aniplayUrl = "https://aniplay.it"
        const val animesaturnUrl = "https://www.animesaturn.in"
        const val tmdb2mal = "https://tmdb2mal.slidemovies.org"
        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getActorRole(t: String?): ActorRole {
            return when (t) {
                "Acting" -> ActorRole.Main
                else -> ActorRole.Background
            }
        }


        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        fun base64DecodeAPI(api: String): String {
            return api.chunked(4).map { base64Decode(it) }.reversed().joinToString("")
        }

    }


    override val mainPage = mainPageOf(
        "$tmdbAPI/movie/popular?api_key=$apiKey&region=&language=it-IT&page=" to "Film Popolari",
        "$tmdbAPI/tv/popular?api_key=$apiKey&region=&language=it-IT&page=" to "Serie TV Popolari",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&page=" to "Anime",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&region=&language=it-IT&page=" to "Film più votati",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&region=&language=it-IT&page=" to "Serie TV più votate",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=213&page=" to "Netflix",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=1024&page=" to "Amazon",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=2739&page=" to "Disney+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=453&page=" to "Hulu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=2552&page=" to "Apple TV+"
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get(request.data + page)
            .parsedSafe<Results>()?.results
            ?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        val mainResponse = app.get(
            "$tmdbAPI/search/multi?api_key=$apiKey&language=it-IT&query=$query&page=1&include_adult=false"
        ).parsedSafe<Results>()?.results?.mapNotNull { media ->
            media.toSearchResponse()
        }
        if (mainResponse?.isNotEmpty() == true) searchResponse.addAll(mainResponse)

//        val animeResponse =
//            app.get("$mainServerAPI/search/anime/$query?_data=routes/search/anime/\$animeKeyword")
//                .parsedSafe<SearchAnime>()?.searchResults?.results?.mapNotNull { anime -> anime.toSearchResponse() }
//        if (animeResponse?.isNotEmpty() == true) searchResponse.addAll(animeResponse)

        return searchResponse
    }


    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)

        val type = getType(data.type)

        val typename = when(type){
            TvType.TvSeries-> "tv"
            TvType.Movie -> "movie"
            else -> ""
        }

        val res =
            app.get("$tmdbAPI/$typename/${data.id}?api_key=$apiKey&language=it-IT&append_to_response=external_ids,credits,recommendations,videos")
                .parsedSafe<MovieDetails>() ?: throw ErrorLoadingException("Invalid Json Response")


        val title = res.name ?: res.title ?: return null
        val orgTitle = res.originalName ?: res.originalTitle ?: return null

        val year = (res.tvDate ?: res.movieDate)?.split("-")?.first()?.toIntOrNull()

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName ?: return@mapNotNull null,
                    getImageUrl(cast.profilePath)
                ),
                getActorRole(cast.knownForDepartment)
            )
        } ?: return null
        val recommendations =
            res.recommandations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer =
            res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }
                ?.randomOrNull()

        if (res.genres?.map { it.id }?.contains(16) == true && type == TvType.TvSeries) {

            val episodes = mutableListOf<Episode>()
            res.seasons?.filter { it.seasonNumber != 0L }?.apmap { season ->
                val seasonData = app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=it-IT")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes
                val seasonID = if (season.seasonNumber!! > 1 && seasonData?.isNotEmpty() == true && seasonData?.first()?.episodeNumber != 1){ 1 } else { season.seasonNumber }.toInt()
                    seasonData?.map { eps ->
                        episodes.add(Episode(
                            LinkData(
                                data.id,
                                res.externalIds?.imdbId,
                                data.type,
                                seasonID,
                                eps.episodeNumber,
                                title = title,
                                year = year,
                                orgTitle = orgTitle,
                                isAnime = true
                            ).toJson(),
                            name = eps.name,
                            season = eps.seasonNumber,
                            episode = eps.episodeNumber,
                            posterUrl = getImageUrl(eps.stillPath),
                            rating = eps.voteAverage?.times(10)?.roundToInt(),
                            description = eps.overview
                        ).apply {
                            this.addDate(eps.airDate)
                        })
                    }
            }
            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = getOriImageUrl(res.backdropPath)
                this.year = year
                this.plot = res.overview
                this.tags = res.genres?.mapNotNull { it.name }
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
            }

        }
        return if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            res.seasons?.filter { it.seasonNumber != 0L }?.apmap { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=it-IT")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        episodes.add(Episode(
                            LinkData(
                                data.id,
                                res.externalIds?.imdbId,
                                data.type,
                                eps.seasonNumber,
                                eps.episodeNumber,
                                title = title,
                                year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                                orgTitle = orgTitle,
                                isAnime = false
                            ).toJson(),
                            name = eps.name,
                            season = eps.seasonNumber,
                            episode = eps.episodeNumber,
                            posterUrl = getImageUrl(eps.stillPath),
                            rating = eps.voteAverage?.times(10)?.roundToInt(),
                            description = eps.overview
                        ).apply {
                            this.addDate(eps.airDate)
                        })
                    }
            }
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = getOriImageUrl(res.backdropPath)
                this.year = year
                this.plot = res.overview
                this.tags = res.genres?.mapNotNull { it.name }
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
            }
        }
        else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    data.id,
                    res.externalIds?.imdbId,
                    data.type,
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = res.genres?.map { it.id }?.contains(16)?:false
                ).toJson(),
            ) {
                this.posterUrl = getOriImageUrl(res.backdropPath)
                this.year = year
                this.plot = res.overview
                this.tags = res.genres?.mapNotNull { it.name }
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = parseJson<LinkData>(data)
        val malID = app.get("$tmdb2mal/?id=${res.id}&s=${res.season}").text.trim()
        argamap(
            {
                if (res.isAnime ) invoAnimeWorld(malID, res.title, res.episode, subtitleCallback, callback)
            },
            {
                if (res.isAnime) invoAniPlay(malID, res.title, res.episode, res.year, subtitleCallback, callback)
            },
            {
                if (res.isAnime) invoAnimeSaturn(malID, res.title, res.episode, subtitleCallback, callback)
            },
            {
                invoGuardare(res.imdbId, subtitleCallback, callback)
            },
            {
                if (malID == "") invoGuardaserie(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                if (!res.isAnime) invoFilmpertutti(res.imdbId,res.title, res.type, res.season, res.episode, res.year, subtitleCallback, callback)
            },
            {
                if (!res.isAnime) invoAltadefinizione(res.imdbId, subtitleCallback, callback)
            },
            {
                if (!res.isAnime) invoCb01(res.title, res.year, subtitleCallback, callback)
            }
        )

        return true
    }

    private data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val aniId: String? = null,
        val animeId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val orgTitle: String? = null,
        val isAnime : Boolean
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class Subtitles(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("language") val language: String? = null,
    )

    data class Sources(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("isM3U8") val isM3U8: Boolean = true,
    )

    data class LoadLinks(
        @JsonProperty("sources") val sources: ArrayList<Sources>? = arrayListOf(),
        @JsonProperty("subtitles") val subtitles: ArrayList<Subtitles>? = arrayListOf(),
    )

    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
    )

    data class Seasons(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )


    data class MovieDetails (
        val adult: Boolean? = null,
        @JsonProperty("first_air_date") val tvDate: String? = null,
        @JsonProperty("release_date") val movieDate: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        val genres: List<Genre>? = null,
        val id: Long? = null,
        val name: String? = null,
        val title: String? = null,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Long? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        val overview: String? = null,
        val popularity: Double? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        val seasons: List<Season>? = null,
        val status: String? = null,
        val tagline: String? = null,
        val type: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("vote_count") val voteCount: Long? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommandations: Recommendations? = null,
        @JsonProperty("videos") val videos: Videos? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null
    )

    data class Recommendations (
        @JsonProperty("results") val results: List<Media>? = null,
    )

    data class Credits (
        val cast: List<Cast>? = null,
    )

    data class ExternalIds (
        @JsonProperty("imdb_id") val imdbId: String? = null
    )

    data class Videos (
        val results: List<Trailers>? = null,
    )

    data class Trailers(
        @JsonProperty("key") val key: String? = null,
    )

    data class Genre (
        val id: Long? = null,
        val name: String? = null
    )

    data class Season (
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("episode_count") val episodeCount: Long? = null,
        val id: Long? = null,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("season_number") val seasonNumber: Long? = null
    )

    data class AnimeWorldJson(
        @JsonProperty("grabber") val grabber: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("target") val target: String,
    )
    data class AniPlayApiSearchResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("listWebsites") val websites: List<AniPlayWebsites>
    )
    data class AniPlayWebsites(
         @JsonProperty("url") val url: String? = null,
         @JsonProperty("listWebsiteId") val websitesId: Int? = null
    )
    data class AniplayApiAnime(
        @JsonProperty("episodes") val episodes: List<AniplayApiEpisode>,
        @JsonProperty("seasons") val seasons: List<AniplayApiSeason>?,
        @JsonProperty("title") val title: String?
    )
    data class AniplayApiEpisode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("episodeNumber") val number: String,
    )
    data class AniplayApiSeason(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("episodeStart") val episodeStart: Int
    )
    data class AniPlayApiEpisodeUrl(
        @JsonProperty("videoUrl") val url: String
    )
}
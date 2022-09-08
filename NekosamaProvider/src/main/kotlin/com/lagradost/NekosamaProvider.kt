package com.lagradost


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

import me.xdrop.fuzzywuzzy.FuzzySearch

class NekoSamaProvider : MainAPI() {
    override var mainUrl = "https://neko-sama.fr"
    override var name = "Neko-sama"
    override val hasQuickSearch = false // recherche rapide (optionel, pas vraimet utile)
    override val hasMainPage = true // page d'accueil (optionel mais encoragé)
    override var lang = "fr" // fournisseur est en francais
    override val supportedTypes =
        setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) // animes, animesfilms
    // liste des types: https://recloudstream.github.io/dokka/app/com.lagradost.cloudstream3/-tv-type/index.html

    /**
    Cherche le site pour un titre spécifique

    La recherche retourne une SearchResponse, qui peut être des classes suivants: AnimeSearchResponse, MovieSearchResponse, TorrentSearchResponse, TvSeriesSearchResponse
    Chaque classes nécessite des données différentes, mais a en commun le nom, le poster et l'url
     **/


    private fun List<EpisodeData>.sortByQuery(query: String?): List<EpisodeData> {
        return if (query == null) {
            // Return list to base state if no query
            this.sortedBy { it.title }
        } else {
            this.sortedBy {
                -FuzzySearch.ratio(
                    it.title?.take(query.length + 6) ?: it.title,
                    query
                )
            }//compare only with the (query.length + n ) first char
        }
    }

    private fun List<SearchResponse>.sortByname(query: String?): List<SearchResponse> {
        return if (query == null) {
            // Return list to base state if no query
            this.sortedBy { it.name }
        } else {
            this.sortedBy { -FuzzySearch.ratio(it.name, query) }
        }
    }

    data class Genre(
        @JsonProperty("0") val action: String,
        @JsonProperty("1") val adventure: String,
        @JsonProperty("2") val drama: String?,
        @JsonProperty("3") val fantasy: String?,
        @JsonProperty("4") val military: String?,
        @JsonProperty("5") val shounen: String,
    )

    data class EpisodeData(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("title_english") val title_english: String?,
        @JsonProperty("title_romanji") val title_romanji: String?,
        @JsonProperty("title_french") val title_french: String?,
        @JsonProperty("others") val others: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("popularity") val popularity: Int?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("genre") val genre: Genre?,
        @JsonProperty("url_image") val url_image: String?,
        @JsonProperty("score") val score: String?,
        @JsonProperty("start_date_year") val start_date_year: String?,
        @JsonProperty("nb_eps") val nb_eps: String?,

        )

    override suspend fun search(query: String): List<SearchResponse> {
        val link2 = Pair("$mainUrl/animes-search-vf.json", "(VF) ")
        val link = Pair("$mainUrl/animes-search-vostfr.json", "(Vostfr) ")
        val links = ArrayList<Pair<String, String>>()
        links.add(link2)
        links.add(link)
        val nbrresults = 50 // take only n results
        var ListResults = ArrayList<SearchResponse>()
        if (links != null) {
            links.forEach {
                val url = it.first
                val version = it.second
                val reponse = app.get(url).text
                val ParsedData = tryParseJson<ArrayList<EpisodeData>>(reponse)

                ParsedData?.sortByQuery(query)?.take(nbrresults)?.apmap { it ->
                    val type = it.type
                    val mediaPoster = it.url_image
                    val href = mainUrl + it.url
                    val title = version + it.title.toString()

                    when (type) {
                        "m0v1e", "special" -> (
                                ListResults.add(newMovieSearchResponse( // réponse du film qui sera ajoutée à la liste apmap qui sera ensuite return
                                    title,
                                    href,
                                    TvType.AnimeMovie,
                                    false
                                ) {
                                    this.posterUrl = mediaPoster
                                    // this.rating = rating
                                }
                                ))
                        null, "tv", "ova" -> (
                                ListResults.add(newAnimeSearchResponse(
                                    title,
                                    href,
                                    TvType.Anime,
                                    false
                                ) {
                                    this.posterUrl = mediaPoster
                                }


                                ))
                        else -> {
                            throw ErrorLoadingException("invalid media type") // le type n'est pas reconnu ==> affiche une erreur
                        }
                    }
                } ?: throw ErrorLoadingException("ParsedData failed")
            }
            return ListResults.sortByname(query).take(nbrresults)
        }
        return ListResults
    }

    /**
     * charge la page d'informations, il ya toutes les donées, les épisodes, le résumé etc ...
     * Il faut retourner soit: AnimeLoadResponse, MovieLoadResponse, TorrentLoadResponse, TvSeriesLoadResponse.
     */
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document //
        // url est le lien retourné par la fonction search (la variable href) ou la fonction getMainPage

        val episodes = ArrayList<Episode>()
        var mediaType = TvType.AnimeMovie
        val script =
            document.select("div#main > script:first-of-type")

        val srcAllInfoEpisode =
            Regex("""min\"\,\"([^\}]*)\}""")
        val results = srcAllInfoEpisode.findAll(script.toString())
        //srcAllInfoEpisode.find(script.toString())?.groupValues?.get(1)?
        //////////////////////////////////////
        var title = ""  //document.select("div.offset-md-4 >:not(small)").text()
        var dataUrl = ""
        /////////////////////////////////////
        results.forEach { InfoEpisode ->
            val episodeScript = InfoEpisode.groupValues[1]
            val srcScriptEpisode =
                Regex("""episode\"\:\"Ep\. ([0-9]*)\"""")
            val EpisodeNum = srcScriptEpisode.find(episodeScript)?.groupValues?.get(1)?.toInt()
            val srcScriptTitle = Regex("""title\"\:\"([^\"]*)\"\,\"url\"\:\"\\\/anime""")
            var titleE = srcScriptTitle.find(episodeScript)?.groupValues?.get(1)
            if (titleE != null) title = titleE
            val srcScriptlink =
                Regex("""\"url\"\:\"([^\"]*)\"""") // remove\
            val link = srcScriptlink.find(episodeScript)?.groupValues?.get(1)
            var link_video = ""
            if (link != null) link_video = mainUrl + link.replace("\\", "")

            val srcScriptposter =
                Regex("""\"url_image\"\:\"([^\"]*)\"""") // remove\
            val poster = srcScriptposter.find(episodeScript)?.groupValues?.get(1)
            var link_poster = ""
            if (poster != null) link_poster = poster.replace("\\", "")
            dataUrl = link_video


            episodes.add(
                Episode(
                    link_video,
                    episode = EpisodeNum,
                    name = title,
                    posterUrl = link_poster

                )
            )

        }
        val type =
            document.selectFirst("div#anime-info-list")?.text()
        if (type != null) {
            if (type.contains("tv") || type.contains("ova")) mediaType = TvType.Anime
        }
        val description = document.selectFirst("div.synopsis > p")?.text()
        val poster = document.select("div.cover > img").attr("src")
        if (mediaType == TvType.AnimeMovie) {
            return newMovieLoadResponse(
                title,
                url,
                mediaType,
                dataUrl
            ) { // retourne les informations du film
                this.posterUrl = poster
                this.plot = description
            }
        } else  // an anime
        {
            return newAnimeLoadResponse(
                title,
                url,
                mediaType,
            ) {
                this.posterUrl = poster
                this.plot = description
                addEpisodes(
                    DubStatus.Dubbed,
                    episodes
                )

            }
        }
    }


    /** récupere les liens .mp4 ou m3u8 directement à partir du paramètre data généré avec la fonction load()**/
    override suspend fun loadLinks(
        data: String, // fournit par load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = data
        val document = app.get(url).document
        val script = document.select("""[type^="text"]""")[1]
        val srcAllvideolinks =
            Regex("""\'(https:\/\/[^']*)""")

        val results = srcAllvideolinks.findAll(script.toString())

        results.forEach { InfoEpisode ->

            var playerUrl = InfoEpisode.groupValues[1]

            if (playerUrl != "")
                loadExtractor(
                    httpsify(playerUrl),
                    playerUrl,
                    subtitleCallback
                ) { link -> // charge un extracteur d'extraire le lien direct .mp4
                    callback.invoke(
                        ExtractorLink( // ici je modifie le callback pour ajouter des informations, normalement ce n'est pas nécessaire
                            link.source,
                            link.name + "",
                            link.url,
                            link.referer,
                            getQualityFromName("HD"),
                            link.isM3u8,
                            link.headers,
                            link.extractorData
                        )
                    )
                }
        }

        return true
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val poster = select("div.cover > a > div.ma-lazy-wrapper")
        var posterUrl = poster.select("img:last-child").attr("src")
        if (posterUrl == "#") posterUrl = poster.select("img:last-child").attr("data-src")
        //val subdub = select("div.quality").text()
        val type = select("div.info > p.year").text()
        val title = select("div.info > a.title > div.limit").text()
        val link = mainUrl + select("div.cover > a").attr("href")
        if (type.contains("Film")) {
            return newMovieSearchResponse(
                title,
                link,
                TvType.AnimeMovie,
                false,
            ) {
                this.posterUrl = posterUrl
            }

        } else  // an Anime
        {
            return newAnimeSearchResponse(
                title,
                link,
                TvType.Anime,
                false,
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    override val mainPage = mainPageOf(
        Pair("$mainUrl/anime-vf/", "Animes et Films en version français"),
        Pair("$mainUrl/anime/", "Animes et Films sous-titrés en français"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url: String
        if (page == 1) {
            url = request.data
        } else {
            url = request.data + page
        }
        val document = app.get(url).document
        val movies = document.select("div#regular-list-animes > div.anime")

        val home =
            movies.mapNotNull { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return newHomePageResponse(request.name, home)
    }


}
package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element


class VostfreeProvider : MainAPI() {
    // VostFreeProvider() est ajouté à la liste allProviders dans MainAPI.kt
    override var mainUrl = "https://vostfree.cx"
    override var name = "vostfree"
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
    override suspend fun search(query: String): List<SearchResponse> {
        val link =
            "$mainUrl/index.php?story=$query&do=search&subaction=search" // L'url pour chercher un anime de dragon sera donc: 'https://vostfree.cx/index.php?story=dragon&do=search&subaction=search'
        val document =
            app.get(link).document // app.get() permet de télécharger la page html avec une requete HTTP (get)
        return document.select("div.search-result") // on séléctione tous les éléments 'enfant' du type articles
            .apmap { div -> // apmap crée une liste des éléments (ici newMovieSearchResponse et newAnimeSearchResponse)
                val type =
                    div?.selectFirst("div.genre")?.text()?.replace("\t", "")
                        ?.replace("\n", "")  // replace enlève tous les '\t' et '\n' du titre
                val mediaPoster = mainUrl + div?.selectFirst("span.image > img")
                    ?.attr("src") // récupère le texte de l'attribut src de l'élément
                val href = div?.selectFirst("div.info > div.title > a")?.attr("href")
                    ?: throw ErrorLoadingException("invalid link") // renvoie une erreur si il n'y a pas de lien vers le média
                val title = div.selectFirst("> div.info > div.title > a")?.text().toString()

                when (type) {
                    "FILM" -> (
                            newMovieSearchResponse( // réponse du film qui sera ajoutée à la liste apmap qui sera ensuite return
                                title,
                                href,
                                TvType.AnimeMovie,
                                false
                            ) {
                                this.posterUrl = mediaPoster
                                // this.rating = rating
                            }
                            )
                    null -> (
                            newAnimeSearchResponse(
                                title,
                                href,
                                TvType.Anime,
                                false
                            ) {
                                this.posterUrl = mediaPoster
                                // this.rating = rating
                            }


                            )
                    else -> {
                        throw ErrorLoadingException("invalid media type") // le type n'est pas reconnu ==> affiche une erreur
                    }
                }
            }
    }

    /**
     * charge la page d'informations, il ya toutes les donées, les épisodes, le résumé etc ...
     * Il faut retourner soit: AnimeLoadResponse, MovieLoadResponse, TorrentLoadResponse, TvSeriesLoadResponse.
     */
    data class EpisodeData(
        @JsonProperty("url") val url: String,
        @JsonProperty("episodeNumber") val episodeNumber: String,
    )

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document // récupere le texte sur la page (requète http)
        // url est le lien retourné par la fonction search (la variable href) ou la fonction getMainPage
        var mediaType = TvType.Anime
        val episodes = ArrayList<Episode>()

        val meta =
            document.selectFirst("div#dle-content > div.watch-top > div.image-bg > div.image-bg-content > div.slide-block ")
        var poster: String? = ""
        var title = ""

        var description: String? = ""// first() selectione le premier élément de la liste
        val isSaison = document.select("div.new_player_series_count > a")
        var saison00 = -1
        var i = 0
        var noSeason = true
        var enterInIseason = false
        isSaison.mapNotNull {
            var url1 = it.attr("href")
            while (noSeason) {
                it.select("[alt=Saison 0$i]")
                    .mapNotNull { // enter in the block when it match the season
                        noSeason = false
                    }
                it.select("[alt=Saison $i]")
                    .mapNotNull { // enter in the block when it match the season
                        noSeason = false
                    }
                i++
            }
            i = i - 1 // take the good number
            saison00 = i
            i = 0 // reinit i et noSeason for the next page
            noSeason = true
            var document1 = app.get(url1).document // récupere le texte sur la page (requète http)
            // url est le lien retourné par la fonction search (la variable href) ou la fonction getMainPage
            var meta1 =
                document1.selectFirst("div#dle-content > div.watch-top > div.image-bg > div.image-bg-content > div.slide-block ")
            poster = mainUrl + meta1?.select(" div.slide-poster > img")
                ?.attr("src") // récupere le texte de l'attribut 'data-src'
            title = meta1?.select("div.slide-middle > h1")?.text()
                ?: throw ErrorLoadingException("Invalid title")
            title = title.replace("Saison", "").replace("saison", "").replace("SAISON", "")
                .replace("Season", "").replace("season", "").replace("SEASON", "")
            description = meta1.select("div.slide-middle > div.slide-desc").first()
                ?.text() // first() selectione le premier élément de la liste

            document.select(" select.new_player_selector > option").mapNotNull {

                if (it.text() != "Film") {
                    val link =
                        EpisodeData(
                            url1,
                            it.text().replace("Episode ", ""),
                        ).toJson()
                    episodes.add(
                        Episode(
                            link,
                            episode = it.text().replace("Episode ", "").toInt(),
                            season = saison00,
                            name = "Saison ${saison00.toString()}" + it.text(),
                            //description= description,
                            posterUrl = poster
                        )
                    )
                } else {

                    mediaType = TvType.AnimeMovie
                }
            }
            enterInIseason = true
        }
        poster = mainUrl + meta?.select(" div.slide-poster > img")
            ?.attr("src") // récupere le texte de l'attribut 'data-src'
        title = meta?.select("div.slide-middle > h1")?.text()
            ?: throw ErrorLoadingException("Invalid title")


        description = meta.select("div.slide-middle > div.slide-desc").first()
            ?.text() // first() selectione le premier élément de la liste
        //var saison0 = document.select("div.new_player_series_count > a")?.text()?.replace("Saison 0","")?.replace("Saison ","")?.toInt()
        var season: Int?
        if (enterInIseason) {
            val seasontext = meta.select("ul.slide-top > li:last-child > b:last-child").text()
            title = title.replace("Saison", "").replace("saison", "").replace("SAISON", "")
                .replace("Season", "").replace("season", "").replace("SEASON", "")
            var index = seasontext?.indexOf('0')
            var no = seasontext
            while (index == 0) {
                no = seasontext?.drop(1).toString()
                index = no?.indexOf('0')
            }
            season = no.toInt()
        } else {
            season = null
        }
        document.select(" select.new_player_selector > option").mapNotNull {

            if (it.text() != "Film") {
                val link =
                    EpisodeData(
                        url,
                        it.text().replace("Episode ", ""),
                    ).toJson()
                episodes.add(
                    Episode(
                        link,
                        episode = it.text().replace("Episode ", "").toInt(),
                        season = season,
                        name = "Saison ${season.toString()}" + it.text(),
                        //description= description,
                        posterUrl = poster

                    )
                )
            } else {

                mediaType = TvType.AnimeMovie
            }
        }


        if (mediaType == TvType.AnimeMovie) {
            return newMovieLoadResponse(
                title,
                url,
                mediaType,
                url
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
                    if (title.contains("VF")) DubStatus.Dubbed else DubStatus.Subbed,
                    episodes
                )

            }
        }
    }


    // récupere les liens .mp4 ou m3u8 directement à partir du paramètre data généré avec la fonction load()
    override suspend fun loadLinks(
        data: String, // fournit par load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsedInfo =
            tryParseJson<EpisodeData>(data)//?:throw ErrorLoadingException("Invalid url")
        val url = if (parsedInfo?.url != null) {
            parsedInfo.url
        } else {
            data
        }
        val noMovie = "1"
        val noEpisode = if (parsedInfo?.episodeNumber != null) {
            parsedInfo.episodeNumber
        } else {
            noMovie
        } // if is not a movie then take the episode number else for movie it is 1

        val document = app.get(url).document
        document.select("div.new_player_bottom")
            .apmap { player_bottom -> // séléctione tous les players

                // supprimer les zéro de 0015 pour obtenir l'episode 15
                var index = noEpisode?.indexOf('0')
                var no = noEpisode
                while (index == 0) {
                    no = noEpisode?.drop(1).toString()
                    index = no?.indexOf('0')
                }

                var cssQuery = " div#buttons_$no" // no numéro épisode
                val buttonsNepisode = player_bottom?.select(cssQuery)
                    ?: throw ErrorLoadingException("Non player")  //séléctione tous les players pour l'episode NoEpisode
                buttonsNepisode.select("> div").forEach {
                    val player = it.attr("id")?.toString()
                        ?: throw ErrorLoadingException("Player No found") //prend tous les players resultat : "player_2140" et "player_6521"
                    val playerName = it.select("div#$player")
                        .text() // prend le nom du player ex : "Uqload" et "Sibnet"
                    var codePlayload =
                        document.selectFirst("div#content_$player")?.text()
                            .toString() // result : "325544" ou "https:..." 
                    var playerUrl = when (playerName) {
                        "VIP", "Upvid", "Dstream", "Streamsb", "Vudeo", "NinjaS" -> codePlayload // case https
                        "Uqload" -> "https://uqload.com/embed-$codePlayload.html"
                        "Mytv" -> "https://www.myvi.tv/embed/$codePlayload"
                        "Sibnet" -> "https://video.sibnet.ru/shell.php?videoid=$codePlayload"
                        "Stream" -> "https://myvi.ru/player/embed/html/$codePlayload"
                        else -> ""
                    }

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
                    // }

                }

            }
        return true
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val poster = select("span.image")
        val posterUrl = mainUrl + poster.select("> img").attr("src")
        val subdub = select("div.quality").text()
        val genre = select("div.genre").text()
        val title = select("div.info > div.title").text()
        val link = select("div.play > a").attr("href")
        if (genre == "FILM") {
            return newMovieSearchResponse(
                title,
                link,
                TvType.AnimeMovie,
                false,
            ) {
                this.posterUrl = posterUrl
                //this.quality = quality
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
                if (subdub == "VF") DubStatus.Dubbed else DubStatus.Subbed
            }
        }
    }

    override val mainPage = mainPageOf(
        Pair("$mainUrl/animes-vf/page/", "Animes en version français"),
        Pair("$mainUrl/animes-vostfr/page/", "Animes sous-titrés en français"),
        Pair("$mainUrl/films-vf-vostfr/page/", "Films en Fr et Vostfr")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val movies = document.select("div#content > div#dle-content > div.movie-poster")

        val home =
            movies.mapNotNull { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return newHomePageResponse(request.name, home)
    }


}
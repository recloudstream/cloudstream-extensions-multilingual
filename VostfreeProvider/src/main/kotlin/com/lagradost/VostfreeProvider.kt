package com.lagradost


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.util.*
import kotlin.collections.ArrayList


class VostfreeProvider : MainAPI() {
    // VostFreeProvider() est ajouté à la liste allProviders dans MainAPI.kt
    override var mainUrl = "https://vostfree.cx"
    override var name = "Vostfree"
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
            "$mainUrl/index.php?do=search&subaction=search&story=$query&submit=Submit+Query" // L'url pour chercher un anime de dragon sera donc: 'https://vostfree.cx/index.php?story=dragon&do=search&subaction=search'
        var mediaType = TvType.Anime
        val document =
            app.post(link).document // app.get() permet de télécharger la page html avec une requete HTTP (get)
        return document.select("div.search-result") // on séléctione tous les éléments 'enfant' du type articles
            .mapNotNull { div -> // map crée une liste des éléments (ici newMovieSearchResponse et newAnimeSearchResponse)
                val type =
                    div?.selectFirst("div.genre")
                        ?.text()  // replace enlève tous les '\t' et '\n' du titre
                val mediaPoster =
                    div?.selectFirst("span.image > img")?.attr("src")
                        ?.let { fixUrl(it) } // récupère le texte de l'attribut src de l'élément
                val href = div?.selectFirst("div.info > div.title > a")?.attr("href")
                    ?: throw ErrorLoadingException("invalid link") // renvoie une erreur si il n'y a pas de lien vers le média
                val title = div.selectFirst("> div.info > div.title > a")?.text().toString()
                val version = div.selectFirst("> div.info > ul > li")?.text().toString()
                if (type == "OAV") mediaType = TvType.OVA
                when (type) {
                    "FILM" -> (
                            newMovieSearchResponse( // réponse du film qui sera ajoutée à la liste map qui sera ensuite return
                                title,
                                href,
                                TvType.AnimeMovie,
                                false
                            ) {
                                this.posterUrl = mediaPoster
                                // this.rating = rating
                            }
                            )
                    null, "OAV" -> (
                            newAnimeSearchResponse(
                                title,
                                href,
                                mediaType,
                                false
                            ) {
                                this.posterUrl = mediaPoster
                                this.dubStatus =
                                    if (version.contains("VF")) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(
                                        DubStatus.Subbed
                                    )
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
        val urlSaison = ArrayList<String>()
        val meta =
            document.selectFirst("div#dle-content > div.watch-top > div.image-bg > div.image-bg-content > div.slide-block ")
        val description = meta?.select("div.slide-middle > div.slide-desc")?.first()
            ?.text() // first() selectione le premier élément de la liste
        var title = meta?.select("div.slide-middle > h1")?.text()
            ?: "Invalid title"
        title = title.replace("Saison", "").replace("saison", "").replace("SAISON", "")
            .replace("Season", "").replace("season", "").replace("SEASON", "")
        val poster = fixUrl(
            meta?.select(" div.slide-poster > img")
                ?.attr("src")!!
        )// récupere le texte de l'attribut 'data-src'
        var year = document.select("div.slide-info > p > b > a")?.text()?.toInt()

        urlSaison.add(url)


        var seasonNumber: Int? = null
        val otherSaisonFound = document.select("div.new_player_series_count > a")
        otherSaisonFound.forEach {
            urlSaison.add(it.attr("href"))
        }

        urlSaison.apmap { urlseason ->
            val document =
                app.get(urlseason).document // récupere le texte sur la page (requète http)

            val meta =
                document.selectFirst("div#dle-content > div.watch-top > div.image-bg > div.image-bg-content > div.slide-block ")
            val poster_saison = mainUrl + meta?.select(" div.slide-poster > img")
                ?.attr("src")
            val seasontext = meta?.select("ul.slide-top > li:last-child > b:last-child")?.text()
            var indication: String? = null

            if (!seasontext.isNullOrBlank() && !seasontext.contains("""([a-zA-Z])""".toRegex())) {
                seasonNumber = seasontext.toInt()

                if (seasonNumber!! < 1) { // seem a an OVA has 0 as season number
                    seasonNumber = 1000
                    indication = "Vous regardez un OVA"
                }
            }

            document.select(" select.new_player_selector > option").forEach {
                val typeOftheAnime = it.text()

                if (typeOftheAnime != "Film") {
                    mediaType = TvType.Anime
                    val link =
                        EpisodeData(
                            urlseason,
                            typeOftheAnime.replace("Episode ", ""),
                        ).toJson()
                    episodes.add(
                        Episode(
                            link,
                            episode = typeOftheAnime.replace("Episode ", "").toInt(),
                            season = seasonNumber,
                            name = typeOftheAnime,
                            description = indication,
                            posterUrl = poster_saison
                        )
                    )
                } else {

                    mediaType = TvType.AnimeMovie
                }
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
                this.year = year
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
                this.year = year
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
        val parsedInfo = tryParseJson<EpisodeData>(data)
        val url = parsedInfo?.url ?: data

        val noMovie = "1"
        val numeroEpisode = parsedInfo?.episodeNumber
            ?: noMovie  // if is not a movie then take the episode number else for movie it is 1

        val document = app.get(url).document
        document.select("div.new_player_bottom")
            .forEach { player_bottom -> // séléctione tous les players

                // supprimer les zéro de 0015 pour obtenir l'episode 15
                var index = numeroEpisode.indexOf('0')
                var numero = numeroEpisode
                while (index == 0) {
                    numero = numeroEpisode.drop(1)
                    index = numero.indexOf('0')
                }

                val cssQuery = " div#buttons_$numero" // numero  épisode
                val buttonsNepisode = player_bottom?.select(cssQuery)
                    ?: throw ErrorLoadingException("Non player")  //séléctione tous les players pour l'episode NoEpisode
                buttonsNepisode.select("> div").apmap {
                    val player = it.attr("id")
                        .toString()  //prend tous les players resultat : "player_2140" et "player_6521"
                    val playerName = it.select("div#$player")
                        .text() // prend le nom du player ex : "Uqload" et "Sibnet"
                    val codePlayload =
                        document.selectFirst("div#content_$player")?.text()
                            .toString() // result : "325544" ou "https:..."
                    var playerUrl = when (playerName) {
                        "VIP", "Upvid", "Dstream", "Streamsb", "Vudeo", "NinjaS", "Upstream" -> codePlayload // case https
                        "Uqload" -> "https://uqload.com/embed-$codePlayload.html"
                        "Mytv" -> "https://www.myvi.tv/embed/$codePlayload"
                        "Sibnet" -> "https://video.sibnet.ru/shell.php?videoid=$codePlayload"
                        "Stream" -> "https://myvi.ru/player/embed/html/$codePlayload"
                        else -> return@apmap
                    }


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

    private fun Element.toSearchResponse(): SearchResponse {
        val poster = select("span.image")
        val posterUrl = fixUrl(poster.select("> img").attr("src"))
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
                this.dubStatus =
                    if (subdub == "VF") EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            }
        }
    }

    private fun Element.toSearchResponse1(): SearchResponse {
        val poster = select("span.image")
        val posterUrl = fixUrl(poster.select("> img").attr("src"))
        val subdub = select("div.quality").text()
        //val genre = select("div.info > ul.additional > li").text()
        val title = select("div.info > div.title").text()
        val link = select(" div.info > div.title > a").attr("href")

        return newAnimeSearchResponse(
            title,
            link,
            TvType.Anime,
            false,
        ) {
            this.posterUrl = posterUrl
            this.dubStatus =
                if (subdub == "VF") EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
        }

    }

    override val mainPage = mainPageOf(
        Pair("$mainUrl/last-episode.html/page/", "Nouveaux épisodes en Vostfr"),
        Pair(
            "$mainUrl/animes-vostfr-recement-ajoutees.html/page/",
            "Animes Vostfr récemment ajoutés"
        ),
        Pair("$mainUrl/last-episode-vf.html/page/", "Nouveaux épisodes en français"),
        Pair("$mainUrl/last-anime-vf.html/page/", "Animes VF récemment ajoutés"),
        Pair("$mainUrl/animes-vf/page/", "Animes en version français"),
        Pair("$mainUrl/animes-vostfr/page/", "Animes sous-titrés en français"),
        Pair("$mainUrl/films-vf-vostfr/page/", "Films en Fr et Vostfr")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categoryName = request.name
        var cssSelector = ""
        if (categoryName.contains("récemment")) {
            cssSelector = "div#content > div.movie-poster"
        } else {
            cssSelector = "div#content > div#dle-content > div.movie-poster"
        }
        val url = request.data + page
        val document = app.get(url).document

        val home =
            when (!categoryName.isNullOrBlank()) {
                request.name.contains("Nouveaux") -> document.select("div#content > div.last-episode")
                    .mapNotNull { article -> article.toSearchResponse1() }
                else ->
                    document.select(cssSelector)
                        .mapNotNull { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                            article.toSearchResponse()
                        }
            }
        return newHomePageResponse(request.name, home)
    }


}
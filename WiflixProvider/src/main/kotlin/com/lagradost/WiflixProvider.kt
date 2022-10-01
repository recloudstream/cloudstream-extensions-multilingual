package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import kotlin.collections.ArrayList

class WiflixProvider : MainAPI() {


    override var mainUrl = "https://wiflix.zone"
    override var name = "Wiflix"
    override val hasQuickSearch = false // recherche rapide (optionel, pas vraimet utile)
    override val hasMainPage = true // page d'accueil (optionel mais encoragé)
    override var lang = "fr" // fournisseur est en francais
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries) // series, films
    // liste des types: https://recloudstream.github.io/dokka/app/com.lagradost.cloudstream3/-tv-type/index.html

    /**
    Cherche le site pour un titre spécifique

    La recherche retourne une SearchResponse, qui peut être des classes suivants: AnimeSearchResponse, MovieSearchResponse, TorrentSearchResponse, TvSeriesSearchResponse
    Chaque classes nécessite des données différentes, mais a en commun le nom, le poster et l'url
     **/
    override suspend fun search(query: String): List<SearchResponse> {
        val link =
            "$mainUrl/index.php?do=search&subaction=search&search_start=0&full_search=1&result_from=1&story=$query&titleonly=3&searchuser=&replyless=0&replylimit=0&searchdate=0&beforeafter=after&sortby=date&resorder=desc&showposts=0&catlist%5B%5D=0"  // search'
        val document =
            app.post(link).document // app.get() permet de télécharger la page html avec une requete HTTP (get)
        val results = document.select("div#dle-content > div.clearfix")

        val allresultshome =
            results.mapNotNull { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return allresultshome
    }

    /**
     * charge la page d'informations, il ya toutes les donées, les épisodes, le résumé etc ...
     * Il faut retourner soit: AnimeLoadResponse, MovieLoadResponse, TorrentLoadResponse, TvSeriesLoadResponse.
     */
    data class EpisodeData(
        @JsonProperty("url") val url: String,
        @JsonProperty("episodeNumber") val episodeNumber: String,
    )

    private fun Elements.takeEpisode(url: String, duborSub: String?): ArrayList<Episode> {

        val episodes = ArrayList<Episode>()
        this.select("ul.eplist > li").forEach {

            val strEpisode = it.text()
            val strEpisodeN = strEpisode.replace("Episode ", "")
            val link =
                EpisodeData(
                    url,
                    strEpisodeN,
                ).toJson()


            episodes.add(
                Episode(
                    link,
                    name = duborSub,
                    episode = strEpisodeN.toInt(),
                )
            )
        }

        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document //
        // url est le lien retourné par la fonction search (la variable href) ou la fonction getMainPage

        var episodes = ArrayList<Episode>()
        var mediaType: TvType
        val episodeFrfound =
            document.select("div.blocfr")

        val episodeVostfrfound =
            document.select("div.blocvostfr")
        val title =
            document.select("h1[itemprop]").text()
        val posterUrl =
            document.select("img#posterimg").attr("src")
        val yearRegex = Regex("""ate de sortie\: (\d*)""")
        val year = yearRegex.find(document.text())?.groupValues?.get(1)


        val tags = document.select("[itemprop=genre] > a")
            .map { it.text() } // séléctione tous les tags et les ajoutes à une liste

        if (episodeFrfound.text().contains("Episode")) {
            mediaType = TvType.TvSeries
            val duborSub = "Episode en VF"
            episodes = episodeFrfound.takeEpisode(url, duborSub)
        } else if (episodeVostfrfound.text().contains("Episode")) {
            mediaType = TvType.TvSeries
            val duborSub = "Episode sous-titré"
            episodes = episodeVostfrfound.takeEpisode(url, duborSub)
        } else {

            mediaType = TvType.Movie
        }
        ///////////////////////////////////////////
        ///////////////////////////////////////////
        var type_rec: TvType
        val recommendations =
            document.select("div.clearfixme > div > div")?.mapNotNull { element ->
                val recTitle =
                    element.select("a").text() ?: return@mapNotNull null
                val image = element.select("a >img")?.attr("src")
                val recUrl = element.select("a").attr("href")
                type_rec = TvType.TvSeries
                if (recUrl.contains("film")) type_rec = TvType.Movie

                if (type_rec == TvType.TvSeries) {
                    TvSeriesSearchResponse(
                        recTitle,
                        recUrl,
                        this.name,
                        TvType.TvSeries,
                        image?.let { fixUrl(it) },

                        )
                } else
                    MovieSearchResponse(
                        recTitle,
                        recUrl,
                        this.name,
                        TvType.Movie,
                        image?.let { fixUrl(it) },

                        )

            }

        var comingSoon = url.contains("films-prochainement")


        if (mediaType == TvType.Movie) {
            val description = document.selectFirst("div.screenshots-full")?.text()
                ?.replace("(.* .ynopsis)".toRegex(), "")
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url

            ) {
                this.posterUrl = fixUrl(posterUrl)
                this.plot = description
                this.recommendations = recommendations
                this.year = year?.toIntOrNull()
                this.comingSoon = comingSoon
                this.tags = tags
            }
        } else {
            val description = document.selectFirst("span[itemprop=description]")?.text()
            return newTvSeriesLoadResponse(
                title,
                url,
                mediaType,
                episodes
            ) {
                this.posterUrl = fixUrl(posterUrl)
                this.plot = description
                this.recommendations = recommendations
                this.year = year?.toIntOrNull()
                this.comingSoon = comingSoon
                this.tags = tags

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
            tryParseJson<EpisodeData>(data)
        val url = parsedInfo?.url ?: data

        val numeroEpisode = parsedInfo?.episodeNumber ?: null

        val document = app.get(url).document
        val episodeFrfound =
            document.select("div.blocfr")
        val episodeVostfrfound =
            document.select("div.blocvostfr")

        val cssCodeForPlayer = if (episodeFrfound.text().contains("Episode")) {
            "div.ep${numeroEpisode}vf > a"
        } else if (episodeVostfrfound.text().contains("Episode")) {
            "div.ep${numeroEpisode}vs > a"
        } else {
            "div.linkstab > a"
        }


        document.select("$cssCodeForPlayer").apmap { player -> // séléctione tous les players
            var playerUrl = "https"+player.attr("href").replace("(.*)https".toRegex(), "")
            if (!playerUrl.isNullOrBlank())
                if (playerUrl.contains("dood")) {
                    playerUrl = playerUrl.replace("doodstream.com", "dood.wf")
                }
            loadExtractor(
                httpsify(playerUrl),
                playerUrl,
                subtitleCallback
            ) { link ->
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

        val posterUrl = fixUrl(select("div.img-box > img").attr("src"))
        val qualityExtracted = select("div.nbloc1-2 >span").text()
        val type = select("div.nbloc3").text()
        val title = select("a.nowrap").text()
        val link = select("a.nowrap").attr("href")
        var quality = when (!qualityExtracted.isNullOrBlank()) {
            qualityExtracted.contains("HDLight") -> getQualityFromString("HD")
            qualityExtracted.contains("Bdrip") -> getQualityFromString("BlueRay")
            qualityExtracted.contains("DVD") -> getQualityFromString("DVD")
            qualityExtracted.contains("CAM") -> getQualityFromString("Cam")

            else -> null
        }
        if (type.contains("Film")) {
            return MovieSearchResponse(
                name = title,
                url = link,
                apiName = title,
                type = TvType.Movie,
                posterUrl = posterUrl,
                quality = quality

            )


        } else  // an Serie
        {

            return TvSeriesSearchResponse(
                name = title,
                url = link,
                apiName = title,
                type = TvType.TvSeries,
                posterUrl = posterUrl,
                quality = quality,
                //
            )

        }
    }

    override val mainPage = mainPageOf(
        Pair("$mainUrl/films-prochainement/page/", "Film Prochainement en Streaming"),
        Pair("$mainUrl/film-en-streaming/page/", "Top Films cette année"),
        Pair("$mainUrl/serie-en-streaming/page/", "Top Séries cette année"),
        Pair("$mainUrl/saison-complete/page/", "Les saisons complètes"),
        Pair("$mainUrl/film-ancien/page/", "Film zahalé (ancien)")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val movies = document.select("div#dle-content > div.clearfix")

        val home =
            movies.mapNotNull { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return newHomePageResponse(request.name, home)
    }

}

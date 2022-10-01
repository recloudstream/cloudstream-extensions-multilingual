package com.lagradost


import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import org.jsoup.nodes.Element


class FrenchStreamProvider : MainAPI() {
    override var mainUrl = "https://french-stream.cx" //re ou ac ou city
    override var name = "FrenchStream"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?do=search&subaction=search&story=$query" // search'
        val document =
            app.post(link).document // app.get() permet de télécharger la page html avec une requete HTTP (get)
        val results = document.select("div#dle-content > > div.short")

        val allresultshome =
            results.apmap { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return allresultshome
    }

    override suspend fun load(url: String): LoadResponse {
        val soup = app.get(url).document

        val title = soup.selectFirst("h1#s-title")!!.text().toString()
        val isMovie = !title.contains("saison", ignoreCase = true)
        val description =
            soup.selectFirst("div.fdesc")!!.text().toString()
                .split("streaming", ignoreCase = true)[1].replace(":", "")
        var poster = soup.selectFirst("div.fposter > img")?.attr("src")
        val listEpisode = soup.select("div.elink")
        val tags = soup.select("ul.flist-col > li").getOrNull(1)
        //val rating = soup.select("span[id^=vote-num-id]")?.getOrNull(1)?.text()?.toInt()

        if (isMovie) {
            val yearRegex = Regex("""ate de sortie\: (\d*)""")
            val year = yearRegex.find(soup.text())?.groupValues?.get(1)
            val tagsList = tags?.select("a")
                ?.mapNotNull {   // all the tags like action, thriller ...; unused variable
                    it?.text()
                }
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year?.toIntOrNull()
                this.tags = tagsList
                this.plot = description
                //this.rating = rating
                addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))
            }
        } else  // a tv serie
        {

            val episodeList = if ("<a" !in (listEpisode[0]).toString()) {  // check if VF is empty
                listEpisode[1]  // no vf, return vostfr
            } else {
                listEpisode[0] // no vostfr, return vf
            }

            val episodes = episodeList.select("a").map { a ->
                val epNum = a.text().split("Episode")[1].trim().toIntOrNull()
                val epTitle = if (a.text().contains("Episode")) {
                    val type = if ("honey" in a.attr("id")) {
                        "VF"
                    } else {
                        "Vostfr"
                    }
                    "Episode " + type
                } else {
                    a.text()
                }
                if (poster == null) {
                    poster = a.selectFirst("div.fposter > img")?.attr("src")
                }
                Episode(
                    fixUrl(url).plus("-episodenumber:$epNum"),
                    epTitle,
                    null,
                    epNum,
                    null,  // episode Thumbnail
                    null // episode date
                )
            }

            // val tagsList = tags?.text()?.replace("Genre :","")
            val yearRegex = Regex("""Titre .* \/ (\d*)""")
            val year = yearRegex.find(soup.text())?.groupValues?.get(1)
            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes,
            ) {
                this.posterUrl = poster
                this.plot = description
                this.year = year?.toInt()
                //this.rating = rating
                //this.showStatus = ShowStatus.Ongoing
                //this.tags = tagsList
                addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))
            }
        }
    }

    fun translate(
        // the website has weird naming of series for episode 2 and 1 and original version content
        episodeNumber: String,
        is_vf_available: Boolean,
    ): String {
        return if (episodeNumber == "1") {
            if (is_vf_available) {  // 1 translate differently if vf is available or not
                "FGHIJK"
            } else {
                "episode033"
            }
        } else {
            "episode" + (episodeNumber.toInt() + 32).toString()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val servers =
            if (data.contains("-episodenumber:"))// It's a serie:
            {
                val split =
                    data.split("-episodenumber:")  // the data contains the url and the wanted episode number (a temporary dirty fix that will last forever)
                val url = split[0]
                val wantedEpisode =
                    if (split[1] == "2") { // the episode number 2 has id of ABCDE, don't ask any question
                        "ABCDE"
                    } else {
                        "episode" + split[1]
                    }


                val soup = app.get(fixUrl(url)).document
                val div =
                    if (wantedEpisode == "episode1") {
                        "> div.tabs-sel "  // this element is added when the wanted episode is one (the place changes in the document)
                    } else {
                        ""
                    }
                val serversvf =// French version servers
                    soup.select("div#$wantedEpisode > div.selink > ul.btnss $div> li")
                        .mapNotNull { li ->  // list of all french version servers
                            val serverUrl = fixUrl(li.selectFirst("a")!!.attr("href"))
//                            val litext = li.text()
                            if (serverUrl.isNotBlank()) {
                                if (li.text().replace("&nbsp;", "").replace(" ", "").isNotBlank()) {
                                    Pair(li.text().replace(" ", ""), "vf" + fixUrl(serverUrl))
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }

                val translated = translate(split[1], serversvf.isNotEmpty())
                val serversvo =  // Original version servers
                    soup.select("div#$translated > div.selink > ul.btnss $div> li")
                        .mapNotNull { li ->
                            val serverUrl = fixUrlNull(li.selectFirst("a")?.attr("href"))
                            if (!serverUrl.isNullOrEmpty()) {
                                if (li.text().replace("&nbsp;", "").isNotBlank()) {
                                    Pair(li.text().replace(" ", ""), "vo" + fixUrl(serverUrl))
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                serversvf + serversvo
            } else {  // it's a movie
                val movieServers =
                    app.get(fixUrl(data)).document.select("nav#primary_nav_wrap > ul > li > ul > li > a")
                        .mapNotNull { a ->
                            val serverurl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                            val parent = a.parents()[2]
                            val element = parent.selectFirst("a")!!.text().plus(" ")
                            if (a.text().replace("&nbsp;", "").isNotBlank()) {
                                Pair(element.plus(a.text()), fixUrl(serverurl))
                            } else {
                                null
                            }
                        }
                movieServers
            }

        servers.apmap {
            for (extractor in extractorApis) {
                var playerName = it.first

                if (playerName.contains("Stream.B")) {
                    playerName = it.first.replace("Stream.B", "StreamSB")
                }
                if (it.second.contains("streamlare")) {
                    playerName = "Streamlare"
                }
                if (playerName.contains(extractor.name, ignoreCase = true)) {
                    val header = app.get(
                        "https" + it.second.split("https").get(1),
                        allowRedirects = false
                    ).headers
                    val urlplayer = it.second
                    var playerUrl = when (!urlplayer.isNullOrEmpty()) {
                        urlplayer.contains("opsktp.com") -> header.get("location")
                            .toString() // case where there is redirection to opsktp

                        else -> it.second
                    }
                    extractor.getSafeUrl(playerUrl, playerUrl, subtitleCallback, callback)
                    break
                }
            }
        }

        return true
    }


    private fun Element.toSearchResponse(): SearchResponse {

        val posterUrl = fixUrl(select("a.short-poster > img").attr("src"))
        val qualityExtracted = select("span.film-ripz > a").text()
        val type = select("span.mli-eps").text()
        val title = select("div.short-title").text()
        val link = select("a.short-poster").attr("href").replace("wvw.", "") //wvw is an issue
        var quality = when (!qualityExtracted.isNullOrBlank()) {
            qualityExtracted.contains("HDLight") -> getQualityFromString("HD")
            qualityExtracted.contains("Bdrip") -> getQualityFromString("BlueRay")
            qualityExtracted.contains("DVD") -> getQualityFromString("DVD")
            qualityExtracted.contains("CAM") -> getQualityFromString("Cam")

            else -> null
        }

        if (type.contains("Eps", false)) {
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
        Pair("$mainUrl/xfsearch/version-film/page/", "Derniers films"),
        Pair("$mainUrl/xfsearch/version-serie/page/", "Derniers séries"),
        Pair("$mainUrl/film/arts-martiaux/page/", "Films za m'ringué (Arts martiaux)"),
        Pair("$mainUrl/film/action/page/", "Films Actions"),
        Pair("$mainUrl/film/romance/page/", "Films za malomo (Romance)"),
        Pair("$mainUrl/serie/aventure-serie/page/", "Série aventure"),
        Pair("$mainUrl/film/documentaire/page/", "Documentaire")

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val movies = document.select("div#dle-content > div.short")

        val home =
            movies.map { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return newHomePageResponse(request.name, home)
    }

}

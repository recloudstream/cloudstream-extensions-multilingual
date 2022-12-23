package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ShortLink.unshorten
import com.lagradost.nicehttp.Requests

object SoraItalianExtractor : SoraItalianStream() {

    suspend fun invoGuardare(
        id: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(
            "https://guardahd.stream/movie/$id",
            referer = "/"
        ).document
        res.select("ul._player-mirrors > li").forEach { source ->
            loadExtractor(
                fixUrl(source.attr("data-link")),
                "https://guardahd.stream",
                subtitleCallback,
                callback
            )
        }
    }

    suspend fun invoGuardaserie(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = app.post(
            guardaserieUrl, data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to id!!
            )
        ).document.selectFirst("h2>a")?.attr("href") ?: return

        val document = app.get(url).document
        document.select("div.tab-content > div").forEachIndexed { seasonData, data ->
            data.select("li").forEachIndexed { epNum, epData ->
                if (season == seasonData + 1 && episode == epNum + 1) {
                    epData.select("div.mirrors > a.mr").forEach {
                        loadExtractor(
                            fixUrl(it.attr("data-link")),
                            guardaserieUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }
    }

    suspend fun invoFilmpertutti(
        id: String?,
        title: String?,
        type: String?,
        season: Int?,
        episode: Int?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = when (type) {
            "movie" -> "$filmpertuttiUrl/search/$title%20$year/feed/rss2"
            else -> "$filmpertuttiUrl/search/$title/feed/rss2"
        }
        val res = app.get(url).text
        val links = Regex("<link>(.*)</link>").findAll(res).map { it.groupValues.last() }.toList()
            .filter { it != filmpertuttiUrl }
        links.apmap {
            val doc = app.get(it).document
            if (id == doc.selectFirst("div.rating > p > a") //check if corresponds to the imdb id
                    ?.attr("href")
                    ?.substringAfterLast("/")
            ) {
                if (type == "tv") {

                    val seasonData = doc.select("div.accordion-item").filter { a ->
                        a.selectFirst("#season > ul > li.s_title > span")!!
                            .text()
                            .isNotEmpty()
                    }.find {
                        season == it.selectFirst("#season > ul > li.s_title > span")!!
                            .text()
                            .toInt()
                    }

                    val episodeData = seasonData?.select("div.episode-wrap")?.find {
                        episode == it.selectFirst("li.season-no")!!.text()
                            .substringAfter("x")
                            .substringBefore(" ")
                            .filter { it.isDigit() }.toIntOrNull()
                    }

                    episodeData?.select("#links > div > div > table > tbody:nth-child(2) > tr")
                        ?.forEach {
                            val unshortenLink = unshorten(it.selectFirst("a")?.attr("href") ?: "")
                            loadExtractor(
                                unshortenLink,
                                filmpertuttiUrl,
                                subtitleCallback,
                                callback
                            )
                        }
                } else { //movie
                    doc.select("#info > ul > li").forEach {
                        val unshortenLink = unshorten(it.selectFirst("a")?.attr("href") ?: "")
                        loadExtractor(
                            unshortenLink,
                            filmpertuttiUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }
    }

    suspend fun invoCb01(
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val res = app.get("$cb01Url/search/$title%20$year/feed").text
        val links = Regex("<link>(.*)</link>").findAll(res).map { it.groupValues.last() }.toList()
            .filter { it != cb01Url && it != "$cb01Url/" }
        if (links.size != 1) return

        links.apmap {
            val doc = app.get(it).document
            doc.select("tr > td > a[href*='stayonline.pro']").forEach {
                val link = it.selectFirst("a")?.attr("href") ?: ""
                val apiPostId = link.substringAfter("/l/")
                    .substringBefore("/")  //https://stayonline.pro/l/abcdef/ -> abcdef
                val apiBodyBypass = app.post(
                    "https://stayonline.pro/ajax/linkView.php",
                    data = mapOf("id" to apiPostId)
                ).text
                var bypassedUrl =
                    apiBodyBypass.substringAfter("\"value\": \"").substringBefore("\"")
                        .replace("\\", "") //bypass stayonline link

                if (bypassedUrl.contains("mixdrop.club")) //https://mixdrop.club/f/lllllllll/2/abcdefghilmn.mp4 (fake mp4 url) -> https://mixdrop.ch/e/lllllllll
                    bypassedUrl = bypassedUrl.replace("mixdrop.club", "mixdrop.ch")
                        .substringBeforeLast("/")
                        .substringBeforeLast("/")
                        .replace("/f/", "/e/")
                else
                    bypassedUrl = unshorten(bypassedUrl)
                loadExtractor(
                    bypassedUrl,
                    cb01Url,
                    subtitleCallback,
                    callback
                )
            }

        }
    }

    suspend fun invoAnimeWorld(
        malId: String?,
        title: String?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val pagedata = app.get("$animeworldUrl/search?keyword=$title").document

        pagedata.select(".film-list > .item").map {
            fixUrl(it.select("a.name").firstOrNull()?.attr("href") ?: "", animeworldUrl)
        }.apmap {
            val document = app.get(it).document
            val pageMalId = document.select("#mal-button").attr("href")
                .split('/').last().toString()
            if (malId == pageMalId) {
                val servers = document.select(".widget.servers")
                servers.select(".server[data-name=\"9\"] .episode > a").toList()
                    .filter { it.attr("data-episode-num").toIntOrNull()?.equals(episode) ?: false }
                    .forEach { id ->
                        val url = tryParseJson<AnimeWorldJson>(
                            app.get("$animeworldUrl/api/episode/info?id=${id.attr("data-id")}").text
                        )?.grabber
                        var dub = false
                        for (meta in document.select(".meta dt, .meta dd")) {
                            val text = meta.text()
                            if (text.contains("Audio")) {
                                dub = meta.nextElementSibling()?.text() == "Italiano"
                            }
                        }
                        val nameData = if (dub) {
                            "AnimeWorld DUB"
                        } else {
                            "AnimeWorld SUB"
                        }

                        callback.invoke(
                            ExtractorLink(
                                "AnimeWorld",
                                nameData,
                                url ?: "",
                                referer = animeworldUrl,
                                quality = Qualities.Unknown.value
                            )
                        )
                    }
            }
        }
    }

    suspend fun invoAniPlay(
        malId: String?,
        title: String?,
        episode: Int?,
        year: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val response =
            parseJson<List<AniPlayApiSearchResult>>(app.get("$aniplayUrl/api/anime/advanced-search?page=0&size=36&query=$title&startYear=$year").text)
        val links = response.filter { it.websites.joinToString().contains("anime/$malId") }
            .map { "https://aniplay.it/api/anime/${it.id}" }

        links.apmap { url ->
            val response = parseJson<AniplayApiAnime>(app.get(url).text)
            val AnimeName = if (isDub(response.title)) {
                "AniPlay DUB"
            } else {
                "AniPlay SUB"
            }
            if (response.seasons.isNullOrEmpty()) {
                val episodeUrl =
                    "$aniplayUrl/api/episode/${response.episodes.find { it.number.toInt() == episode }?.id}"
                val streamUrl =
                    parseJson<AniPlayApiEpisodeUrl>(app.get(episodeUrl).text).url
                callback.invoke(
                    ExtractorLink(
                        name,
                        AnimeName,
                        streamUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = streamUrl.contains(".m3u8"),
                    )
                )
            } else {
                val seasonid = response.seasons.sortedBy { it.episodeStart }
                    .last { it.episodeStart < episode!! }
                val episodesData =
                    tryParseJson<List<AniplayApiEpisode>>(
                        app.get(
                            "$url/season/${seasonid.id}"
                        ).text
                    )
                val episodeData = episodesData?.find { it.number == episode.toString() }?.id
                if (episodeData != null) {
                    val streamUrl =
                        parseJson<AniPlayApiEpisodeUrl>(app.get("$aniplayUrl/api/episode/${episodeData}").text).url
                    callback.invoke(
                        ExtractorLink(
                            name,
                            AnimeName,
                            streamUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = streamUrl.contains(".m3u8"),
                        )
                    )
                }
            }
        }

    }

    suspend fun invoAnimeSaturn(
        malId: String?,
        title: String?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val document =
            app.get("$animesaturnUrl/animelist?search=${title?.replace("-", " ")}").document
        val links = document.select("div.item-archivio").map {
            it.select("a.badge-archivio").first()!!.attr("href")
        }
        links.apmap { url ->
            val response = app.get(url).document
            val AnimeName = if (isDub(response.select("img.cover-anime").first()!!.attr("alt"))) {
                "AnimeSaturn DUB"
            } else {
                "AnimeSaturn SUB"
            }
            var malID: String?

            response.select("a[href*=myanimelist]").forEach {
                malID = it.attr("href").substringBeforeLast("/")
                    .substringAfterLast("/") //https://myanimelist.net/anime/19/ -> 19
                if (malId == malID) {
                    val link = response.select("a.bottone-ep")
                        .find { it.text().substringAfter("Episodio ") == episode.toString() }
                        ?.attr("href")
                    if (link?.isBlank() == false) { //links exists
                        val page = app.get(link).document
                        val episodeLink = page.select("div.card-body > a[href*=watch]").attr("href")
                            ?: throw ErrorLoadingException("No link Found")

                        val episodePage = app.get(episodeLink).document
                        val episodeUrl: String? = episodePage.select("video.afterglow > source")
                            .let { // Old player
                                it.first()?.attr("src")
                            }
                            ?: //new player
                            Regex("\"(https[A-z0-9\\/\\:\\.]*\\.m3u8)\",").find(
                                episodePage.select("script").find {
                                    it.toString().contains("jwplayer('player_hls').setup({")
                                }.toString()
                            )?.value

                        callback.invoke(
                            ExtractorLink(
                                name,
                                AnimeName,
                                episodeUrl!!,
                                isM3u8 = episodeUrl.contains(".m3u8"),
                                referer = "https://www.animesaturn.io/", //Some servers need the old host as referer, and the new ones accept it too
                                quality = Qualities.Unknown.value
                            )
                        )
                    }

                }
            }

        }
    }
}


private fun isDub(title: String?): Boolean {
    return title?.contains(" (ITA)") ?: false
}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }

}

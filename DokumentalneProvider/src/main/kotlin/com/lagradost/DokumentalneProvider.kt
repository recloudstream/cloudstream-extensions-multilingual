package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import android.util.Log

open class DokumentalneProvider : MainAPI() {
    override var mainUrl = "https://dokumentalne.net/"
    override var name = "Dokumentalne.net"
    override var lang = "pl"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Documentary
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select(".body-content article.cactus-post-item").mapNotNull{ it -> 
            val a = it.selectFirst("h3 a") ?: return@mapNotNull null
            val name = a.attr("title").trim()
            val href = a.attr("href")
            val img = it.selectFirst("img")?.attr("src")
            newMovieSearchResponse(
                name,
                href,
                TvType.Documentary
            ) {
                this.posterUrl = img
            }
        }
        return HomePageResponse(listOf(HomePageList("Najnowsze", items, isHorizontalImages = true)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.cactus-post-item").mapNotNull{ it -> 
            val a = it.selectFirst("h3 a") ?: return@mapNotNull null
            val name = a.attr("title").trim()
            val href = a.attr("href")
            val img = it.selectFirst("img")?.attr("src")
            newMovieSearchResponse(
                name,
                href,
                TvType.Documentary
            ) {
                this.posterUrl = img
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val embedUrl = document.selectFirst("iframe[allowfullscreen]")?.attr("src")?.let { it ->
            return@let if (it.startsWith("//")) "https:$it"
            else it
        }
        val title = document.select("h1.single-title").text().trim()

        val plot = document.select(".single-post-content p").text().trim()
        
        return newMovieLoadResponse(title, url, TvType.Documentary, embedUrl) {
            this.plot = plot
            this.recommendations = document.select(".post-list-in-single article.cactus-post-item").mapNotNull{ it -> 
                val a = it.selectFirst("h3 a") ?: return@mapNotNull null
                val name = a.attr("title").trim()
                val href = a.attr("href")
                val img = it.selectFirst("img")?.attr("src")
                newMovieSearchResponse(
                    name,
                    href,
                    TvType.Documentary
                ) {
                    this.posterUrl = img
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(data, subtitleCallback, callback)
        return true
    }
}

data class LinkElement(
    @JsonProperty("src") val src: String
)

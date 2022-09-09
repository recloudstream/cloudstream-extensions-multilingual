package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import me.xdrop.fuzzywuzzy.FuzzySearch

open class TvpolanProvider : MainAPI() {
    override var mainUrl = "http://tvpolan.ml/"
    override var name = "TV Polan"
    override var lang = "pl"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val lists = document.select(".channels ul li a[href]").map { it -> 
            return@map LiveSearchResponse(
                name = it.attr("title"),
                url = it.attr("href"),
                apiName = this.name,
                type = TvType.Live,
                posterUrl = it.selectFirst("img[src]")?.attr("src")
            )
        }
        return newHomePageResponse(this.name, lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(mainUrl).document
        val lists = document.select(".channels ul li a[href]").map { it ->
            return@map LiveSearchResponse(
                name = it.attr("title"),
                url = it.attr("href"),
                apiName = this.name,
                type = TvType.Live,
                posterUrl = it.selectFirst("img[src]")?.attr("src")
            )
        }
        return lists.sortedBy { -FuzzySearch.ratio(it.name, query) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val name = Regex("http://tvpolen\\.ml/(.+)\\.php").find(url)?.groupValues?.get(1) ?: this.name

        val src = document.selectFirst("video source[src]")?.attr("src") ?: return null

        return LiveStreamLoadResponse(
            name,
            url,
            this.name,
            src,
            "http://tvpolen.ml/tv/${name}.png"
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            ExtractorLink(
                "tvpolen.ml",
                this.name,
                data,
                "",
                Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        return true
    }
}
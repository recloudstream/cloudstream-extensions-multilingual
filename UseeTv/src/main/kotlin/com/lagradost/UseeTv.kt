package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class UseeTv : MainAPI() {
    override var mainUrl = "https://www.useetv.com"
    override var name = "Useetv"
    override var lang = "id"
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/tv/live").document

        val homePageList = ArrayList<HomePageList>()

        val tvAll = document.select("div#channelContainer div.col-channel").mapNotNull {
            it.toSearchResult()
        }
        if (tvAll.isNotEmpty()) homePageList.add(HomePageList("Semua", tvAll, true))

        val tvLocal =
            document.select("div#channelContainer div.col-channel").mapNotNull { it }.filter {
                it.attr("class").contains("local", true)
            }.mapNotNull {
                it.toSearchResult()
            }
        if (tvLocal.isNotEmpty()) homePageList.add(HomePageList("Local", tvLocal, true))

        val tvNews =
            document.select("div#channelContainer div.col-channel").mapNotNull { it }.filter {
                it.attr("class").contains("news", true)
            }.mapNotNull {
                it.toSearchResult()
            }
        if (tvNews.isNotEmpty()) homePageList.add(HomePageList("News", tvNews, true))

        return HomePageResponse(homePageList)

    }

    private fun Element.toSearchResult(): LiveSearchResponse? {
        return LiveSearchResponse(
            this.selectFirst("a")?.attr("data-name") ?: return null,
            fixUrl(this.selectFirst("a")!!.attr("href")),
            this@UseeTv.name,
            TvType.Live,
            fixUrlNull(this.select("img").attr("data-src")),
        )

    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/tv/live").document.select("div#channelContainer div.col-channel").mapNotNull {
            it
        }.filter { it.select("a").attr("data-name").contains(query, true) }.mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val content = document.selectFirst("div.d-flex.video-schedule-time p")?.text()?.split("•")
        val mainLink = "https://streaming.useetv.com"
        val link = document.select("script").findLast { it.data().contains("\$('.live').last()") }?.data()?.let{
            Regex("'$mainLink(.*)';var").find(it)?.groupValues?.getOrNull(1)
        }
        return LiveStreamLoadResponse(
            content?.firstOrNull()?.trim() ?: return null,
            url,
            this.name,
            "$mainLink$link",
            fixUrlNull(document.selectFirst("div.row.video-schedule img")?.attr("src")),
            plot = "Live Now : ${document.selectFirst("div.row.video-schedule h5")?.text()}"
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        M3u8Helper.generateM3u8(
            this.name,
            data,
            mainUrl
        ).forEach(callback)

        return true

    }

}
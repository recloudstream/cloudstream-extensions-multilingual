package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*


class CalcioStreamingProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://calciostreaming.live"
    override var name = "CalcioStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Live,

        )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl+"/partite-streaming.html").document
        val sections = document.select("div.slider-title").filter {it -> it.select("div.item").isNotEmpty()}

        if (sections.isEmpty()) throw ErrorLoadingException()

        return HomePageResponse(sections.map { it ->
            val categoryname = it.selectFirst("h2 > strong")!!.text()
            val shows = it.select("div.item").map {
                val href = it.selectFirst("a")!!.attr("href")
                val name = it.selectFirst("a > div > h1")!!.text()
                val posterurl = fixUrl(it.selectFirst("a > img")!!.attr("src"))
                LiveSearchResponse(
                    name,
                    href,
                    this@CalcioStreamingProvider.name,
                    TvType.Live,
                    posterurl,
                )
            }
            HomePageList(
                categoryname,
                shows,
                isHorizontalImages = true
            )

        })

    }


    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document
        val poster =  fixUrl(document.select("#title-single > div").attr("style").substringAfter("url(").substringBeforeLast(")"))
        val Matchstart = document.select("div.info-wrap > div").textNodes().joinToString("").trim()
        return LiveStreamLoadResponse(
            document.selectFirst(" div.info-t > h1")!!.text(),
            url,
            this.name,
            url,
            poster,
            plot = Matchstart
        )


    }




    private suspend fun extractVideoLinks(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        document.select("button.btn").forEach { button ->
            val link1 = button.attr("data-link")
            val doc2 = app.get(link1).document
            val truelink = doc2.selectFirst("iframe")!!.attr("src")
            val newpage = app.get(truelink, referer = link1).document
            val streamurl = Regex(""""((.|\n)*?).";""").find(
                getAndUnpack(
                    newpage.select("script")[6].childNode(0).toString()
                ))!!.value.replace("""src="""", "").replace(""""""", "").replace(";", "")

            callback(
                ExtractorLink(
                    this.name,
                    button.text(),
                    streamurl,
                    truelink,
                    quality = 0,
                    true
                )
            )
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        extractVideoLinks(data, callback)

        return true
    }
}

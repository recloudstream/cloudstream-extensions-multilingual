package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class SkillShareProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://www.skillshare.com"
    override var name = "SkillShare"

    private val apiUrl = "https://www.skillshare.com/api/graphql"
    private val bypassApiUrl = "https://skillshare.techtanic.xyz/id"

    override val supportedTypes = setOf(TvType.Others)
    override val hasChromecastSupport = true
    override var lang = "en"
    override val hasMainPage = true
    private var cursor = mutableMapOf("SIX_MONTHS_ENGAGEMENT" to "", "ML_TRENDINESS" to "")

    override val mainPage =
        mainPageOf(
            "SIX_MONTHS_ENGAGEMENT" to "Popular Classes",
            "ML_TRENDINESS" to "Trending Classes",
        )

    private suspend fun queryMovieApi(payload: String): String {
        val req = payload.toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        return app.post(apiUrl, requestBody = req, referer = "$mainUrl/", timeout = 30).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sortAttribute = request.data
        if (page == 1) //reset the cursor to "" if the first page is requested
            cursor[sortAttribute] = ""
        val payload=
            """
            {
                "query":"query GetClassesByType(${'$'}filter: ClassFilters!, ${'$'}pageSize: Int, ${'$'}cursor: String, ${'$'}type: ClassListType!, ${'$'}sortAttribute: ClassListByTypeSortAttribute) {
                  classListByType(type: ${'$'}type, where: ${'$'}filter, first: ${'$'}pageSize, after: ${'$'}cursor, sortAttribute: ${'$'}sortAttribute) {
                     nodes {
                      id
                      title
                      url
                      sku
                      smallCoverUrl
                      largeCoverUrl
                    }
                  }
                }",
                "variables":{
                    "type":"TRENDING_CLASSES",
                    "filter":{
                        "subCategory":"",
                        "classLength":[]
                    },
                    "pageSize":30,
                    "cursor":"${cursor[sortAttribute]}",
                    "sortAttribute":"$sortAttribute"
                },
                "operationName":"GetClassesByType"
            }
            """.replace(Regex("\n")," ")

        val responseBody = queryMovieApi(payload)
        val parsedJson = parseJson<ApiData>(responseBody).data.classListByType.nodes
        val home = parsedJson.map {
            it.toSearchResult()
        }
        cursor[sortAttribute] = parsedJson.lastOrNull()?.id ?: "" //set the right cursor for the nextPage to work
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, home, isHorizontalImages = true)),
            hasNext = home.isNotEmpty(),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val payload =
            """
            {
                "query":"fragment ClassFields on Class {
                  id
                  smallCoverUrl
                  largeCoverUrl
                  sku
                  title
                  url
                }
                
                query GetClassesQuery(${"$"}query: String!, ${"$"}where: SearchFilters!, ${"$"}after: String!, ${"$"}first: Int!) {
                  search(query: ${"$"}query, where: ${"$"}where, analyticsTags: [\"src:browser\", \"src:browser:search\"], after: ${"$"}after, first: ${"$"}first) {
                    edges {
                      node {
                        ...ClassFields
                      }
                    }
                  }
                }",
                "variables":{
                    "query":"$query",
                    "where":{
                        "level":
                            ["ALL_LEVELS","BEGINNER","INTERMEDIATE","ADVANCED"]
                    },
                    "after":"-1",
                    "first":30
                },
                "operationName":"GetClassesQuery"
            }
            """.replace(Regex("\n")," ")

        val responseBody = queryMovieApi(payload)
        val home = parseJson<SearchApiData>(responseBody).data.search.edges.map {
            it.node.toSearchResult()
        }
        return home
    }

    private fun ApiNode.toSearchResult(): SearchResponse {
        val title = this.title ?: ""
        val posterUrl = this.smallCoverUrl
        return newTvSeriesSearchResponse(
            title,
            Data(
                title = this.title,
                courseId = this.courseId,
                largeCoverUrl = this.largeCoverUrl
            ).toJson(),
            TvType.TvSeries
        ) {
            addPoster(posterUrl)
        }
    }


    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val document = app.get(bypassApiUrl + "/${data.courseId}")
            .parsedSafe<BypassApiData>() ?: throw ErrorLoadingException("Invalid Json Response")
        val title = data.title ?: ""
        val poster = data.largeCoverUrl
        val episodeList = document.lessons.mapIndexed { index, episode ->
            Episode(episode.url ?: "", episode.title, 1, index)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            addPoster(poster)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            ExtractorLink(
                name,
                name,
                data,
                isM3u8 = false,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value
            )
        )
        return true
    }


    data class ApiNode(
        //mainpage and search page
        @JsonProperty("id") var id: String? = null,
        @JsonProperty("title") var title: String? = null,
        @JsonProperty("url") var url: String? = null,
        @JsonProperty("sku") var courseId: String? = null,
        @JsonProperty("smallCoverUrl") var smallCoverUrl: String? = null,
        @JsonProperty("largeCoverUrl") var largeCoverUrl: String? = null,
    )

    data class ApiNodes( //mainpage

        @JsonProperty("nodes") var nodes: ArrayList<ApiNode> = arrayListOf()

    )

    data class ApiClassListByType( //mainpage

        @JsonProperty("classListByType") var classListByType: ApiNodes = ApiNodes()

    )

    data class ApiData( //mainpage

        @JsonProperty("data") var data: ApiClassListByType = ApiClassListByType()

    )

    data class SearchApiNodes( //search

        @JsonProperty("node") var node: ApiNode = ApiNode()

    )

    data class SearchApiEdges( //search

        @JsonProperty("edges") var edges: ArrayList<SearchApiNodes> = arrayListOf()

    )

    data class SearchApiSearch( //search

        @JsonProperty("search") var search: SearchApiEdges = SearchApiEdges()

    )

    data class SearchApiData( //search

        @JsonProperty("data") var data: SearchApiSearch = SearchApiSearch()

    )

    data class BypassApiLesson( //bypass

        @JsonProperty("title") var title: String? = null,
        @JsonProperty("url") var url: String? = null

    )

    data class BypassApiData( //bypass

        @JsonProperty("class") var title: String? = null,
        @JsonProperty("class_thumbnail") var largeCoverUrl: String? = null,
        @JsonProperty("lessons") var lessons: ArrayList<BypassApiLesson> = arrayListOf()

    )

    data class Data(
        //for loading
        val title: String? = null,
        val courseId: String? = null,
        val largeCoverUrl: String? = null,
    )
}

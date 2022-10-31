
package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.TvType

@CloudstreamPlugin
class WebFlixProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(WebFlixProvider("en", "https://dhfilmtv.com", "DHFilmTv", setOf(TvType.Movie, TvType.TvSeries)))
        //registerMainAPI(WebFlixProvider("pl", "https://app.vodi.cc", "Vodi.cc", setOf(TvType.Movie, TvType.TvSeries))) // removed due to complaint from owner of site
        registerMainAPI(WebFlixProvider("fr", "http://www.vanflix.cm", "Vanflix", setOf(TvType.Movie, TvType.TvSeries, TvType.Live)))
        registerMainAPI(WebFlixProvider("pt-pt", "https://www.brflix.xyz", "BrFlix", setOf(TvType.Movie, TvType.TvSeries, TvType.Live)))
        registerMainAPI(WebFlixProvider("ar", "https://ifilm.live", "ifilm.live", setOf(TvType.Movie, TvType.TvSeries)))
        registerMainAPI(WebFlixProvider("en", "https://karmadarna.com", "KarMaDarNa", setOf(TvType.NSFW)))
    }
}

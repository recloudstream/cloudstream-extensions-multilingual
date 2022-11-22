// use an integer for version numbers
version = 1


cloudstream {
    language = "it"
    // All of these properties are optional, you can safely remove them

    description = "Provider che utilizza tmdb. Non tutti i links sono funzionanti"
    authors = listOf("Adippe")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AnimeMovie",
        "Anime",
        "OVA"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=seriesflix.video&sz=%size%"
}
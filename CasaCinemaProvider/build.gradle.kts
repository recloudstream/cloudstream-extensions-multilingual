// use an integer for version numbers
version = 3


cloudstream {
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
    language= "it"
    authors = listOf("Forthe")

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
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=casacinema.lol&sz=%size%"
}

// use an integer for version numbers
version = 3

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Adds multiple sites using WebFlix. This includes sites in English, Polish, Portuguese and Arabic"
    authors = listOf("Cloudburst")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movies",
        "TvSeries",
        "Live"
    )

    iconUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream-extensions-multilingual/master/WebFlix/icon.png"
}

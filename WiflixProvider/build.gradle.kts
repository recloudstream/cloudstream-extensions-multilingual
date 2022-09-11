// use an integer for version numbers
version = 1


cloudstream {
    language = "fr"
    // All of these properties are optional, you can safely remove them

    description = "WIFLIX, le site grâce auquel vous allez pouvoir regarder vos films et séries préférées"
    authors = listOf("Eddy")

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

    iconUrl = "https://www.google.com/s2/favicons?domain=wiflix.zone&sz=%size%"
}
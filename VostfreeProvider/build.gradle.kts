// use an integer for version numbers
version = 1


cloudstream {
    language = "fr"
    // All of these properties are optional, you can safely remove them

    description = " Ce site est certainement l’un des meilleurs sites permettant de regarder des animes en ligne et gratuitement. Il vous propose la version « VF » version française et la « VOSTFR » version originale Sous-titrée en Français."
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
        "Anime",
		"AnimeMovie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=vostfree.cx&sz=%size%"
}
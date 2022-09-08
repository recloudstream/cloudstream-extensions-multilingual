// use an integer for version numbers
version = 1


cloudstream {
    language = "fr"
    // All of these properties are optional, you can safely remove them

    description = " Ce site fait son entrée dans la catégorie des meilleurs sites animes Français. Il est très fiable car quasiment tous ses liens vidéos marchent. Il propose des animes en « VF » version française et en « VOSTFR » version originale Sous-titrée en Français."
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

    iconUrl = "https://www.google.com/s2/favicons?domain=neko-sama.fr&sz=%size%"
}
// use an integer for version numbers
version = 4


cloudstream {
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
    language= "en"
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
        "Others",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=skillshare.com&sz=%size%"
}

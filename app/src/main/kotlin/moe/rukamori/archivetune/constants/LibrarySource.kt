/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.constants

/** Which service the Library's sections read from. */
enum class LibrarySource {
    YTM,
    SPOTIFY,
    ;

    /**
     * This choice as the Library can actually honour it.
     *
     * The stored choice is the user's; this is what the sections read. Spotify without a usable
     * session reports YTM, so no section asks a service that is not signed in, and the Spotify
     * choice itself survives a sign-out instead of being rewritten behind the user's back.
     */
    fun resolved(spotifyAvailable: Boolean): LibrarySource =
        if (this == SPOTIFY && !spotifyAvailable) YTM else this
}

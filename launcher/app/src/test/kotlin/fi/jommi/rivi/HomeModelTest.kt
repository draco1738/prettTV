package fi.jommi.rivi

import fi.jommi.rivi.HomeModel.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeModelTest {
    private val netflix = Item("com.netflix.ninja/.MainActivity", "com.netflix.ninja", "Netflix")
    private val youtube = Item("com.google.android.youtube.tv/.Main", "com.google.android.youtube.tv", "YouTube")
    private val prime = Item("com.amazon.amazonvideo.livingroom/.Main", "com.amazon.amazonvideo.livingroom", "Prime Video")
    private val plex = Item("com.plexapp.android/.Main", "com.plexapp.android", "Plex")
    private val arena = Item("com.yle.webtv/.Main", "com.yle.webtv", "Yle Areena")
    private val all = listOf(plex, youtube, arena, netflix, prime)
    private val preferred = listOf("com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv", "com.apple.atve.androidtv.appletv")

    @Test fun initialFavouritesFollowPreferredOrderAndSkipMissingApps() {
        assertEquals(listOf(netflix.key, prime.key, youtube.key), HomeModel.initialFavourites(preferred, all))
        assertEquals(emptyList<String>(), HomeModel.initialFavourites(preferred, listOf(plex)))
    }

    @Test fun arrangeKeepsFavouriteOrderAndSortsRestAlphabetically() {
        val a = HomeModel.arrange(all, listOf(youtube.key, netflix.key), emptySet(), emptyMap())
        assertEquals(listOf(youtube.key, netflix.key), a.favourites)
        assertEquals(listOf(plex.key, prime.key, arena.key), a.rest)
    }

    @Test fun missingFavouriteIsSkippedNotCrashed() {
        val a = HomeModel.arrange(listOf(netflix, plex), listOf("gone.app/.Main", netflix.key, "gone.app/.Main"), emptySet(), emptyMap())
        assertEquals(listOf(netflix.key), a.favourites)
        assertEquals(listOf(plex.key), a.rest)
    }

    @Test fun duplicateFavouritesCollapse() {
        val a = HomeModel.arrange(all, listOf(netflix.key, netflix.key), emptySet(), emptyMap())
        assertEquals(listOf(netflix.key), a.favourites)
        assertEquals(4, a.rest.size)
    }

    @Test fun hiddenAppsAppearNowhere() {
        val a = HomeModel.arrange(all, listOf(netflix.key, youtube.key), setOf(youtube.key, plex.key, "inputs"), emptyMap(), listOf("inputs"))
        assertEquals(listOf(netflix.key), a.favourites)
        assertEquals(listOf(prime.key, arena.key), a.rest)
    }

    @Test fun recentlyLaunchedAppsLeadTheGrid() {
        val a = HomeModel.arrange(all, listOf(netflix.key), emptySet(), mapOf(arena.key to 200L, prime.key to 100L, netflix.key to 999L), listOf("inputs"))
        assertEquals(listOf(arena.key, prime.key, plex.key, youtube.key, "inputs"), a.rest)
    }

    @Test fun builtInTilesCanBeFavouritesToo() {
        val a = HomeModel.arrange(all, listOf(netflix.key, "inputs"), emptySet(), emptyMap(), listOf("inputs"))
        assertEquals(listOf(netflix.key, "inputs"), a.favourites)
        assertEquals(false, "inputs" in a.rest)
    }

    @Test fun emptyDeviceGivesEmptyScreen() {
        val a = HomeModel.arrange(emptyList(), listOf(netflix.key), emptySet(), emptyMap())
        assertEquals(0, a.favourites.size); assertEquals(0, a.rest.size)
        assertNull(HomeModel.focusTarget("x", a))
    }

    @Test fun moveClampsAndIgnoresUnknownKeys() {
        val l = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), HomeModel.move(l, "a", 1))
        assertEquals(listOf("a", "c", "b"), HomeModel.move(l, "c", -1))
        assertEquals(l, HomeModel.move(l, "a", -1))
        assertEquals(l, HomeModel.move(l, "c", 1))
        assertEquals(l, HomeModel.move(l, "zzz", 1))
    }

    @Test fun recentRoundTripsAndIsBounded() {
        var s: String? = null
        for (i in 1..40) s = HomeModel.touchRecent(HomeModel.parseRecent(s), "app$i/.Main", i.toLong(), keep = 24)
        val m = HomeModel.parseRecent(s)
        assertEquals(24, m.size)
        assertEquals(40L, m["app40/.Main"]); assertNull(m["app1/.Main"])
        s = HomeModel.touchRecent(m, "app20/.Main", 1000L)
        assertEquals(1000L, HomeModel.parseRecent(s)["app20/.Main"])
    }

    @Test fun parseRecentToleratesGarbage() {
        assertEquals(emptyMap<String, Long>(), HomeModel.parseRecent(null))
        assertEquals(emptyMap<String, Long>(), HomeModel.parseRecent(""))
        assertEquals(mapOf("a/.b" to 5L), HomeModel.parseRecent("junk\na/.b=5\n=7\nc/.d=notanumber"))
        // component names may contain '=': only the last one separates the timestamp
        assertEquals(mapOf("a=b/.c" to 9L), HomeModel.parseRecent("a=b/.c=9"))
    }

    @Test fun focusFallsBackWhenTheFocusedAppDisappears() {
        val a = HomeModel.arrange(all, listOf(netflix.key), emptySet(), emptyMap())
        assertEquals(plex.key, HomeModel.focusTarget(plex.key, a))
        assertEquals(netflix.key, HomeModel.focusTarget("gone/.Main", a))
        assertEquals(netflix.key, HomeModel.focusTarget(null, a))
        val noFavs = HomeModel.arrange(all, emptyList(), emptySet(), emptyMap())
        assertEquals(netflix.key, HomeModel.focusTarget(null, noFavs))   // first alphabetically
    }

    @Test fun staleCacheFilesAreIdentified() {
        assertEquals(listOf("old_1_256x144r14.png"), HomeModel.staleCacheFiles(listOf("a_2_256x144r14.png", "old_1_256x144r14.png"), setOf("a_2_256x144r14.png")))
    }

    @Test fun onlyTheStockHomeScreenIsRedirected() {
        val lx = "com.google.android.apps.tv.launcherx"
        assertEquals(true, HomeKeyService.isStockHomeScreen(lx, "$lx.home.HomeActivity"))
        assertEquals(true, HomeKeyService.isStockHomeScreen(lx, "$lx.home.VanillaModeHomeActivity"))
        // the quick-settings panel, profile chooser and re-auth screens must stay reachable
        assertEquals(false, HomeKeyService.isStockHomeScreen(lx, "$lx.dashboard.DashboardHandler"))
        assertEquals(false, HomeKeyService.isStockHomeScreen(lx, "$lx.profile.chooser.ProfileChooserActivity"))
        assertEquals(false, HomeKeyService.isStockHomeScreen(lx, "com.google.android.libraries.tv.reauth.ReauthActivity"))
        assertEquals(false, HomeKeyService.isStockHomeScreen(lx, "android.widget.FrameLayout"))
        // other apps are never redirected, whatever their class is called
        assertEquals(false, HomeKeyService.isStockHomeScreen("com.netflix.ninja", "com.netflix.ninja.home.HomeActivity"))
        assertEquals(false, HomeKeyService.isStockHomeScreen(null, null))
    }

    @Test fun motionSpecIsParsed() {
        assertEquals(Motion.DEFAULT, Motion.parse(null))
        assertEquals(Motion.DEFAULT, Motion.parse(" default "))
        assertEquals(Motion.NONE, Motion.parse("none"))
        assertEquals(Motion(settle = true, fade = false, shadow = false, hold = true), Motion.parse("Settle, hold,glow"))
        assertEquals(listOf("settle", "fade", "shadow", "hold"), Motion.DEFAULT.names)
        assertEquals(false, Motion.DEFAULT.sound)   // silent unless asked for
        assertEquals(true, Motion.parse("settle,sound").sound)
        assertEquals(Motion.DEFAULT, Motion.parse(Motion.DEFAULT.names.joinToString(",")))
    }

    @Test fun themeNameIsParsed() {
        assertEquals(Theme.MIDNIGHT, Theme.parse(null))
        assertEquals(Theme.MIDNIGHT, Theme.parse("no such theme"))
        assertEquals(Theme.CINEMA, Theme.parse(" Cinema "))
        assertEquals(true, Theme.CINEMA.unfocused < 1f)   // cinema holds unfocused tiles back
        assertEquals(1f, Theme.MIDNIGHT.unfocused)
    }
}

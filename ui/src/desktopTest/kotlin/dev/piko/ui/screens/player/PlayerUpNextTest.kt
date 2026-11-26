package dev.piko.ui.screens.player

import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.screens.drive.posterTileColumns
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerUpNextTest {
    @Test
    fun recommendationColumnsFollowPosterSizePreferences() {
        assertEquals(4, posterTileColumns("SMALL", WidthClass.Compact))
        assertEquals(3, posterTileColumns("MEDIUM", WidthClass.Compact))
        assertEquals(2, posterTileColumns("LARGE", WidthClass.Compact))
        assertEquals(6, posterTileColumns("SMALL", WidthClass.Expanded))
        assertEquals(4, posterTileColumns("MEDIUM", WidthClass.Expanded))
        assertEquals(3, posterTileColumns("LARGE", WidthClass.Expanded))
    }

    @Test
    fun startsAfterCurrentThenContinuesOtherSectionsBeforeWrapping() {
        val playlist = listOf(
            entry("a1", section = "main", group = "g1"),
            entry("a2", section = "main", group = "g2"),
            entry("a3", section = "main", group = "g3"),
            entry("b1", section = "sp", group = "g4"),
        )

        assertEquals(
            listOf("a3", "b1", "a1"),
            upNextEntries(playlist, "a2").map { it.fileId },
        )
    }

    @Test
    fun keepsCurrentVersionAndShowsEachEpisodeOnlyOnce() {
        val playlist = listOf(
            entry("a1", section = "main", group = "g1"),
            entry("a2-sdr", section = "main", group = "g2", version = "SDR"),
            entry("a2-hdr", section = "main", group = "g2", version = "HDR", primary = false),
            entry("a3-sdr", section = "main", group = "g3", version = "SDR", primary = false),
            entry("a3-hdr", section = "main", group = "g3", version = "HDR"),
        )

        assertEquals(
            listOf("a3-hdr", "a1"),
            upNextEntries(playlist, "a2-hdr").map { it.fileId },
        )
    }

    @Test
    fun deduplicatesVersionsWhenCurrentIsNotInThePlaylist() {
        val playlist = listOf(
            entry("a1-sdr", section = "main", group = "g1", version = "SDR", primary = false),
            entry("a1-hdr", section = "main", group = "g1", version = "HDR"),
            entry("a2", section = "main", group = "g2"),
        )

        assertEquals(
            listOf("a1-hdr", "a2"),
            upNextEntries(playlist, "missing").map { it.fileId },
        )
    }

    private fun entry(
        id: String,
        section: String,
        group: String,
        version: String = "",
        primary: Boolean = true,
    ) = PlaylistEntry(
        fileId = id,
        name = "$id.mkv",
        label = id,
        sectionKey = section,
        sectionLabel = section,
        groupKey = group,
        versionLabel = version,
        primary = primary,
    )
}

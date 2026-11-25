package dev.piko.shared.media

import io.github.nihildigit.pikpak.DownloadLink
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.VideoInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackQualityOptionsTest {
    @Test
    fun offersOnlyVisiblePlayableTranscodesInDescendingHeight() {
        val info = videoInfo(
            listOf(
                variant("origin", "Original", 1080, origin = true),
                variant("1080", "1080P", 1080),
                variant("720", "720P", 720, visible = false),
                variant("480", "480P", 480, url = ""),
                variant("360a", "360P", 360),
                variant("360b", "360P", 360),
            ),
        )

        assertEquals(listOf(ORIGINAL_QUALITY, "1080P", "360P"), info.playbackQualityOptions())
        assertEquals(1080, info.playbackQualityHeight("1080P"))
        assertNull(info.playbackQualityHeight("720P"))
    }

    @Test
    fun mountedSourceWithoutVariantsHasNoQualitySwitch() {
        assertEquals(emptyList(), videoInfo(emptyList()).playbackQualityOptions())
    }

    private fun videoInfo(variants: List<MediaVariant>) = PlayableMediaInfo(
        fileId = "f1",
        name = "movie.mkv",
        gcid = "GCID",
        currentUrl = "https://cdn.test/origin",
        durationSeconds = 600,
        currentResolution = ORIGINAL_QUALITY,
        availableVariants = variants,
        kind = PlayableMediaKind.Video,
    )

    private fun variant(
        id: String,
        name: String,
        height: Int,
        origin: Boolean = false,
        visible: Boolean = true,
        url: String = "https://cdn.test/$id",
    ) = MediaVariant(
        mediaId = id,
        mediaName = name,
        video = VideoInfo(height = height, width = height * 16 / 9),
        link = DownloadLink(url = url),
        isOrigin = origin,
        isVisible = visible,
    )
}

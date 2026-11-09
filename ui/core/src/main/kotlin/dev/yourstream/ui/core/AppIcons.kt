package dev.yourstream.ui.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons the core Material set lacks, drawn from the Material Symbols paths (Apache-2.0),
 * so we avoid pulling in the whole extended icon artifact for a handful of glyphs.
 */
object AppIcons {
    val Movie: ImageVector by lazy {
        icon("Movie", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z")
    }
    val Tv: ImageVector by lazy {
        icon("Tv", "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z")
    }

    val Pause: ImageVector by lazy { icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z") }
    val SkipNext: ImageVector by lazy { icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val SkipPrevious: ImageVector by lazy { icon("SkipPrevious", "M6,6h2v12H6zm3.5,6l8.5,6V6z") }
    val FastRewind: ImageVector by lazy { icon("FastRewind", "M11,18V6l-8.5,6 8.5,6zm0.5,-6l8.5,6V6l-8.5,6z") }
    val FastForward: ImageVector by lazy { icon("FastForward", "M4,18l8.5,-6L4,6v12zm9,-12v12l8.5,-6L13,6z") }
    val Subtitles: ImageVector by lazy {
        icon("Subtitles", "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zm10,6H4v-2h10v2zm6,0h-4v-2h4v2zm0,-4H10v-2h10v2z")
    }
    val Audio: ImageVector by lazy {
        icon("Audio", "M12,3v9.28c-0.47,-0.17 -0.97,-0.28 -1.5,-0.28C8.01,12 6,14.01 6,16.5S8.01,21 10.5,21c2.31,0 4.2,-1.75 4.45,-4H15V6h4V3h-7z")
    }
    val Speed: ImageVector by lazy {
        icon("Speed", "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58H5.07A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z")
    }
    val Episodes: ImageVector by lazy {
        icon("Episodes", "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zm16,-4H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zm-8,12.5v-9l6,4.5 -6,4.5z")
    }
    val ScreenRotation: ImageVector by lazy {
        icon("ScreenRotation", "M16.48,2.52c3.27,1.55 5.61,4.72 5.97,8.48h1.5C23.44,4.84 18.29,0 12,0l-0.66,0.03 3.81,3.81 1.33,-1.32zM10.23,1.75c-0.59,-0.59 -1.54,-0.59 -2.12,0L1.75,8.11c-0.59,0.59 -0.59,1.54 0,2.12l12.02,12.02c0.59,0.59 1.54,0.59 2.12,0l6.36,-6.36c0.59,-0.59 0.59,-1.54 0,-2.12L10.23,1.75zM14.83,21.19L2.81,9.17l6.36,-6.36 12.02,12.02 -6.36,6.36zM7.52,21.48C4.25,19.94 1.91,16.76 1.55,13L0.05,13C0.56,19.16 5.71,24 12,24l0.66,-0.03 -3.81,-3.81 -1.33,1.32z")
    }
    val Brightness: ImageVector by lazy {
        icon("Brightness", "M20,15.31L23.31,12 20,8.69V4h-4.69L12,0.69 8.69,4H4v4.69L0.69,12 4,15.31V20h4.69L12,23.31 15.31,20H20v-4.69zM12,18V6c3.31,0 6,2.69 6,6s-2.69,6 -6,6z")
    }
    val Volume: ImageVector by lazy {
        icon("Volume", "M3,9v6h4l5,5V4L7,9H3zm13.5,3c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
    }
    val AspectRatio: ImageVector by lazy {
        icon("AspectRatio", "M19,12h-2v3h-3v2h5v-5zM7,9h3L10,7L5,7v5h2L7,9zM21,3L3,3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,19.01L3,19.01L3,4.99h18v14.02z")
    }
    val Update: ImageVector by lazy {
        icon("Update", "M17,1.01L7,1c-1.1,0 -1.99,0.9 -1.99,2v18c0,1.1 0.89,2 1.99,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14zM16,13h-3V8h-2v5H8l4,4 4,-4z")
    }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}

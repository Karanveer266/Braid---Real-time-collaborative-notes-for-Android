package dev.braid.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The handful of icons not in material-icons-core, as vector paths (from Material Symbols,
 * Apache 2.0). Avoids the 30 MB material-icons-extended artifact for six glyphs.
 */
object BraidIcons {
    val Undo: ImageVector by lazy {
        materialIcon("Undo") {
            materialPath {
                moveTo(12.5f, 8f); curveToRelative(-2.65f, 0f, -5.05f, 0.99f, -6.9f, 2.6f)
                lineTo(2f, 7f); verticalLineToRelative(9f); horizontalLineToRelative(9f)
                lineToRelative(-3.62f, -3.62f); curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
                curveToRelative(3.54f, 0f, 6.55f, 2.31f, 7.6f, 5.5f); lineToRelative(2.37f, -0.78f)
                curveTo(21.08f, 11.03f, 17.15f, 8f, 12.5f, 8f); close()
            }
        }
    }
    val Redo: ImageVector by lazy {
        materialIcon("Redo") {
            materialPath {
                moveTo(18.4f, 10.6f); curveTo(16.55f, 8.99f, 14.15f, 8f, 11.5f, 8f)
                curveToRelative(-4.65f, 0f, -8.58f, 3.03f, -9.96f, 7.22f); lineTo(3.9f, 16f)
                curveToRelative(1.05f, -3.19f, 4.05f, -5.5f, 7.6f, -5.5f); curveToRelative(1.95f, 0f, 3.73f, 0.72f, 5.12f, 1.88f)
                lineTo(13f, 16f); horizontalLineToRelative(9f); verticalLineTo(7f); lineToRelative(-3.6f, 3.6f); close()
            }
        }
    }
    val History: ImageVector by lazy {
        materialIcon("History") {
            materialPath {
                moveTo(13f, 3f); curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f); horizontalLineTo(1f)
                lineToRelative(3.89f, 3.89f); lineToRelative(0.07f, 0.14f); lineTo(9f, 12f); horizontalLineTo(6f)
                curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f); reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
                reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f); curveToRelative(-1.93f, 0f, -3.68f, -0.79f, -4.94f, -2.06f)
                lineToRelative(-1.42f, 1.42f); curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
                curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f); reflectiveCurveToRelative(-4.03f, -9f, -9f, -9f); close()
                moveTo(12f, 8f); verticalLineToRelative(5f); lineToRelative(4.28f, 2.54f); lineToRelative(0.72f, -1.21f)
                lineToRelative(-3.5f, -2.08f); verticalLineTo(8f); horizontalLineTo(12f); close()
            }
        }
    }
    val Devices: ImageVector by lazy {
        materialIcon("Devices") {
            materialPath {
                moveTo(4f, 6f); horizontalLineToRelative(18f); verticalLineTo(4f); horizontalLineTo(4f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(11f); horizontalLineTo(0f)
                verticalLineToRelative(3f); horizontalLineToRelative(14f); verticalLineToRelative(-3f); horizontalLineTo(4f)
                verticalLineTo(6f); close()
                moveTo(23f, 8f); horizontalLineToRelative(-6f); curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(10f); curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f); horizontalLineToRelative(6f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f); verticalLineTo(9f); curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                close(); moveTo(22f, 17f); horizontalLineToRelative(-4f); verticalLineToRelative(-7f)
                horizontalLineToRelative(4f); verticalLineToRelative(7f); close()
            }
        }
    }
    val Pin: ImageVector by lazy {
        materialIcon("PushPin") {
            materialPath {
                moveTo(16f, 9f); verticalLineTo(4f); horizontalLineToRelative(1f); curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f); horizontalLineTo(7f); curveTo(6.45f, 2f, 6f, 2.45f, 6f, 3f)
                reflectiveCurveToRelative(0.45f, 1f, 1f, 1f); horizontalLineToRelative(1f); verticalLineToRelative(5f)
                curveToRelative(0f, 1.66f, -1.34f, 3f, -3f, 3f); verticalLineToRelative(2f); horizontalLineToRelative(5.97f)
                verticalLineToRelative(7f); lineToRelative(1f, 1f); lineToRelative(1f, -1f); verticalLineToRelative(-7f)
                horizontalLineTo(19f); verticalLineToRelative(-2f); curveToRelative(-1.66f, 0f, -3f, -1.34f, -3f, -3f); close()
            }
        }
    }
    val QrCode: ImageVector by lazy {
        materialIcon("QrCode") {
            materialPath {
                moveTo(3f, 11f); horizontalLineToRelative(8f); verticalLineTo(3f); horizontalLineTo(3f); verticalLineTo(11f); close()
                moveTo(5f, 5f); horizontalLineToRelative(4f); verticalLineToRelative(4f); horizontalLineTo(5f); verticalLineTo(5f); close()
                moveTo(3f, 21f); horizontalLineToRelative(8f); verticalLineToRelative(-8f); horizontalLineTo(3f); verticalLineTo(21f); close()
                moveTo(5f, 15f); horizontalLineToRelative(4f); verticalLineToRelative(4f); horizontalLineTo(5f); verticalLineTo(15f); close()
                moveTo(13f, 3f); verticalLineToRelative(8f); horizontalLineToRelative(8f); verticalLineTo(3f); horizontalLineTo(13f); close()
                moveTo(19f, 9f); horizontalLineToRelative(-4f); verticalLineTo(5f); horizontalLineToRelative(4f); verticalLineTo(9f); close()
                moveTo(19f, 19f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
                moveTo(13f, 13f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
                moveTo(15f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
                moveTo(13f, 17f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
                moveTo(15f, 19f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
                moveTo(17f, 17f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
                moveTo(17f, 13f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
                moveTo(19f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            }
        }
    }
}

@Suppress("unused") private val ensureIconsLinked = Icons.Default

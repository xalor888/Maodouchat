package androidx.compose.material.icons.automirrored.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.AutoMirrored.Filled.InsertDriveFile: ImageVector
    get() {
        if (_insertDriveFile != null) {
            return _insertDriveFile!!
        }
        _insertDriveFile = materialIcon(name = "AutoMirrored.Filled.InsertDriveFile", autoMirror =
                true) {
            materialPath {
                moveTo(6.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                lineTo(4.0f, 20.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
                lineTo(18.0f, 22.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(20.0f, 8.0f)
                lineToRelative(-6.0f, -6.0f)
                lineTo(6.0f, 2.0f)
                close()
                moveTo(13.0f, 9.0f)
                lineTo(13.0f, 3.5f)
                lineTo(18.5f, 9.0f)
                lineTo(13.0f, 9.0f)
                close()
            }
        }
        return _insertDriveFile!!
    }

private var _insertDriveFile: ImageVector? = null

public val Icons.AutoMirrored.Filled.VolumeUp: ImageVector
    get() {
        if (_volumeUp != null) {
            return _volumeUp!!
        }
        _volumeUp = materialIcon(name = "AutoMirrored.Filled.VolumeUp", autoMirror = true) {
            materialPath {
                moveTo(3.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(5.0f, 5.0f)
                lineTo(12.0f, 4.0f)
                lineTo(7.0f, 9.0f)
                lineTo(3.0f, 9.0f)
                close()
                moveTo(16.5f, 12.0f)
                curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                close()
                moveTo(14.0f, 3.23f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
                reflectiveCurveToRelative(-2.11f, 5.85f, -5.0f, 6.71f)
                verticalLineToRelative(2.06f)
                curveToRelative(4.01f, -0.91f, 7.0f, -4.49f, 7.0f, -8.77f)
                reflectiveCurveToRelative(-2.99f, -7.86f, -7.0f, -8.77f)
                close()
            }
        }
        return _volumeUp!!
    }

private var _volumeUp: ImageVector? = null


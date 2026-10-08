package com.treadz.console.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the launcher icon the way a launcher would draw it: the 108 dp layers scaled so the central 72 dp
 * fill the icon, cut with a circle or a squircle mask, on light and dark launcher backgrounds, plus the monochrome layer
 * as a themed icon. Images go to `build/outputs/roborazzi/icon_*.png` (`icon_wired.png` is the unmasked adaptive icon drawn by Android) (only with `recordRoborazziDebug`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1100dp-h900dp-xhdpi")
class IconRenderScreenshots {

    @get:Rule val rule = createComposeRule()

    private class Variant(val background: Int, val foreground: Int, val monochrome: Int)

    private val icon = Variant(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_monochrome)

    private val circle: Shape = CircleShape
    private val squircle: Shape = RoundedCornerShape(percent = 34)

    /** One icon: layers on a 108 dp canvas, of which the central 72 dp are shown inside [shape]. */
    @Composable
    private fun Icon(variant: Variant, size: Dp, shape: Shape, themed: Boolean = false, darkTheme: Boolean = false) {
        val layers = size * 1.5f
        Box(Modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredSize(layers)) {
                if (themed) {
                    // Android 13+ themed icon: a tone from the wallpaper palette behind a tinted monochrome layer.
                    val container = if (darkTheme) Color(0xFF2B3A33) else Color(0xFFD7EBDD)
                    val tint = if (darkTheme) Color(0xFFB6F0C8) else Color(0xFF14432A)
                    Box(Modifier.requiredSize(layers).background(container))
                    Image(painterResource(variant.monochrome), null, Modifier.requiredSize(layers), colorFilter = ColorFilter.tint(tint))
                } else {
                    Image(painterResource(variant.background), null, Modifier.requiredSize(layers))
                    Image(painterResource(variant.foreground), null, Modifier.requiredSize(layers))
                }
            }
        }
    }

    @Composable
    private fun IconRow(variant: Variant, themed: Boolean = false, darkTheme: Boolean = false, sizes: List<Int> = listOf(144, 96, 64, 48, 36)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            sizes.forEachIndexed { i, size ->
                Icon(variant, size.dp, if (i % 2 == 0) circle else squircle, themed, darkTheme)
            }
        }
    }

    @Composable
    private fun Section(title: String, background: Color, textColor: Color, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().background(background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = textColor, fontSize = 14.sp)
            content()
        }
    }

    @Composable
    private fun Sheet(variant: Variant) {
        Column(Modifier.background(Color.Gray)) {
            Section("Dark launcher", Color(0xFF111418), Color(0xFFCBD2D9)) { IconRow(variant) }
            Section("Light launcher", Color(0xFFF1F3F5), Color(0xFF3D4753)) { IconRow(variant) }
            Section("Themed, light palette", Color(0xFFE9F2EC), Color(0xFF3D4753)) { IconRow(variant, themed = true) }
            Section("Themed, dark palette", Color(0xFF1B2420), Color(0xFFCBD2D9)) { IconRow(variant, themed = true, darkTheme = true) }
        }
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        rule.setContent(content)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** The launcher icon with circle and squircle masks, at several sizes, on dark and light launchers and themed. */
    @Test fun maskedAtSeveralSizes() = render("icon_sheet") { Sheet(icon) }

    /** The two layers on the full 108 dp canvas, without any mask: for comparing with the design at 512 px. */
    @Test fun fullCanvas() = render("icon_full") {
        Box(Modifier.size(256.dp)) {
            Image(painterResource(icon.background), null, Modifier.size(256.dp))
            Image(painterResource(icon.foreground), null, Modifier.size(256.dp))
        }
    }

    /** The icon the manifest points at, drawn by Android itself (adaptive icon with its monochrome layer). */
    @Test fun wiredIconIsAnAdaptiveIconWithAMonochromeLayer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        assertTrue(icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        assertNotNull(icon.monochrome)
        val size = 432
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bitmap))
        render("icon_wired") {
            Box(Modifier.background(Color(0xFF111418)).padding(24.dp)) {
                Image(bitmap.asImageBitmap(), null, Modifier.size(216.dp))
            }
        }
    }
}

package com.craxiom.networksurvey.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.theme.NsTheme
import com.craxiom.networksurvey.util.ThemePref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = Application::class, qualifiers = "zh-rCN-w320dp-h1750dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ColorPaletteFlowTest {
    @Test
    fun presetAndDisplayModeUpdateTheRunningThemeAndPersist() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean("monet_enabled", true).commit()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val systemPaletteKey = "theme_customization_overlay_packages"
        val oldPalette = Settings.Secure.getString(activity.contentResolver, systemPaletteKey)
        Settings.Secure.putString(
            activity.contentResolver,
            systemPaletteKey,
            """{"android.theme.customization.system_palette":"9b264c"}""",
        )
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(Dispatchers.Main.immediate)
        val recomposition = CoroutineScope(Dispatchers.Main.immediate + frameClock)
            .launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        fun settleTheme() = settle(activity, frameClock)
        var primary = Color.Unspecified
        var miuixPrimary = Color.Unspecified
        var background = Color.Unspecified
        fun newView() = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent {
                NsTheme(window = activity.window) {
                    val scheme = MaterialTheme.colorScheme
                    val currentPrimary = MiuixTheme.colorScheme.primary
                    SideEffect {
                        primary = scheme.primary
                        miuixPrimary = currentPrimary
                        background = scheme.background
                    }
                    ColorPaletteScreen({})
                }
            }
        }
        val view = newView()
        activity.setContentView(view)
        try {
            settleTheme()
            assertFalse(ThemePref.isMonet(context))
            val originalPrimary = primary
            click(view, "跟随系统壁纸")
            settleTheme()
            assertTrue(ThemePref.isMonet(context))
            assertNotEquals("Wallpaper opt-in did not update the theme", originalPrimary, primary)
            val wallpaperPrimary = primary
            click(view, "绿色")
            settleTheme()
            assertSystemBars(activity, background, darkIcons = true)
            assertEquals(ThemePref.SEED_GREEN, ThemePref.getSeed(context))
            assertFalse(ThemePref.isMonet(context))
            capture(activity, "palette-green-light")
            assertNotEquals("Miuix did not apply the new seed", originalPrimary, miuixPrimary)
            assertNotEquals("Preset did not replace the wallpaper accent", wallpaperPrimary, miuixPrimary)
            assertEquals("Material did not receive the new Miuix accent", miuixPrimary, primary)
            click(view, "\u914d\u8272\u98ce\u683c")
            settleTheme()
            click(view, "Vibrant")
            settleTheme()
            assertEquals("Vibrant", ThemePref.getPaletteStyle(context))
            click(view, "\u914d\u8272\u7b97\u6cd5")
            settleTheme()
            click(view, "SPEC_2025")
            settleTheme()
            assertEquals("SPEC_2025", ThemePref.getColorSpec(context))
            val lightBackground = background
            click(view, "深色")
            settleTheme()
            assertEquals(ThemePref.MODE_DARK, ThemePref.get(context))
            assertNotEquals(lightBackground, background)
            assertSystemBars(activity, background, darkIcons = false)
            capture(activity, "palette-green-dark")
            click(view, "纯黑背景")
            settleTheme()
            assertTrue(ThemePref.isAmoled(context))
            assertEquals(Color.Black, background)
            val selectedPrimary = primary
            val remountedView = newView()
            activity.setContentView(remountedView)
            settleTheme()
            assertEquals(selectedPrimary, primary)
            assertEquals(Color.Black, background)
            assertSystemBars(activity, background, darkIcons = false)
            assertFalse(ThemePref.isFloatingNavigationEnabled(context))
            assertFalse(ThemePref.isLiquidGlassEnabled(context))
            click(remountedView, activity.getString(R.string.floating_navigation))
            settleTheme()
            assertTrue(ThemePref.isFloatingNavigationEnabled(context))
            capture(activity, "palette-floating-dark")
            click(remountedView, activity.getString(R.string.home_tools))
            settleTheme()
            assertTrue(root(remountedView).hasSelectedLabel(activity.getString(R.string.home_tools)))
        } finally {
            controller.pause().stop().destroy()
            recomposer.cancel()
            recomposition.cancel()
            Settings.Secure.putString(activity.contentResolver, systemPaletteKey, oldPalette)
            prefs.edit().clear().commit()
            ThemePref.apply(context)
        }
    }

    @Test
    @Config(sdk = [30, 33, 34])
    fun materialContentInMiuixContainersFollowsLightAndDarkThemes() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val context = activity.applicationContext
        var iconTint = Color.Unspecified
        var expectedColor = Color.Unspecified
        var background = Color.Unspecified
        val view = ComposeView(activity).apply {
            setContent {
                NsTheme(dynamicColor = false, window = activity.window) {
                    expectedColor = MaterialTheme.colorScheme.onBackground
                    background = MaterialTheme.colorScheme.background
                    Scaffold {
                        Column {
                            Card {
                                iconTint = LocalContentColor.current
                                Text("Mixed content", fontSize = 28.sp)
                            }
                        }
                    }
                }
            }
        }
        try {
            ThemePref.set(context, ThemePref.MODE_LIGHT)
            ThemePref.apply(context)
            activity.setContentView(view)
            settle(activity)
            assertEquals(expectedColor, iconTint)
            assertTrue(rendersColor(activity, expectedColor))
            assertSystemBars(activity, background, darkIcons = true)

            val lightColor = expectedColor
            ThemePref.set(context, ThemePref.MODE_DARK)
            ThemePref.apply(context)
            settle(activity)
            assertNotEquals(lightColor, expectedColor)
            assertEquals(expectedColor, iconTint)
            assertTrue(rendersColor(activity, expectedColor))
            assertSystemBars(activity, background, darkIcons = false)
        } finally {
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
            ThemePref.apply(context)
            controller.pause().stop().destroy()
        }
    }

    private fun assertSystemBars(activity: ComponentActivity, background: Color, darkIcons: Boolean) {
        val window = activity.window
        assertEquals(background.toArgb(), window.statusBarColor)
        assertEquals(background.toArgb(), window.navigationBarColor)
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals(darkIcons, insets.isAppearanceLightStatusBars)
        assertEquals(darkIcons, insets.isAppearanceLightNavigationBars)
    }

    private fun rendersColor(activity: ComponentActivity, color: Color): Boolean {
        val bitmap = Bitmap.createBitmap(640, 3500, Bitmap.Config.ARGB_8888)
        return try {
            activity.window.decorView.draw(Canvas(bitmap))
            val foreground = color.toArgb()
            (0 until 120).any { y -> (10 until 400).any { x -> bitmap.getPixel(x, y) == foreground } }
        } finally {
            bitmap.recycle()
        }
    }

    private fun root(view: ComposeView) =
        (view.getChildAt(0) as RootForTest).semanticsOwner.unmergedRootSemanticsNode

    private fun click(view: ComposeView, label: String) {
        val action = checkNotNull(root(view).actionFor(label)) { "No click action for $label" }
        assertTrue(action())
    }

    private fun SemanticsNode.hasSelectedLabel(label: String, parentSelected: Boolean = false): Boolean {
        val selected = config.getOrNull(SemanticsProperties.Selected) ?: parentSelected
        return (selected && config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }) ||
            children.any { it.hasSelectedLabel(label, selected) }
    }

    private fun SemanticsNode.actionFor(label: String, parentAction: (() -> Boolean)? = null): (() -> Boolean)? {
        val action = config.getOrNull(SemanticsActions.OnClick)?.action ?: parentAction
        val hasLabel = config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } ||
            label in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        return if (hasLabel && action != null) action else children.firstNotNullOfOrNull { it.actionFor(label, action) }
    }

    private fun settle(activity: ComponentActivity, frameClock: BroadcastFrameClock? = null) {
        val decor = activity.window.decorView
        repeat(20) {
            Snapshot.sendApplyNotifications()
            decor.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(3500, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 640, 3500)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            frameClock?.sendFrame(SystemClock.uptimeMillis() * 1_000_000)
        }
    }

    private fun capture(activity: ComponentActivity, name: String) {
        val output = System.getProperty("ui.preview.output") ?: return
        val bitmap = Bitmap.createBitmap(640, 3500, Bitmap.Config.ARGB_8888)
        activity.window.decorView.draw(Canvas(bitmap))
        val file = File(output, "$name-${android.os.Build.VERSION.SDK_INT}.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

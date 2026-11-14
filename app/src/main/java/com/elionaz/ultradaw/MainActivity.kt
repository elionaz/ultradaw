package com.elionaz.ultradaw

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.elionaz.ultradaw.ui.UltraDawScreen
import com.elionaz.ultradaw.ui.UltraDawTheme

class MainActivity : ComponentActivity() {
    private val controller get() = (application as UltraDawApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        controller.start()
        setContent {
            UltraDawTheme { UltraDawScreen(controller) }
        }
    }

    override fun onDestroy() {
        if (isFinishing) controller.stop()
        super.onDestroy()
    }
}

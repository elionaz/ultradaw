package com.elionaz.ultradaw

import android.app.Application

class UltraDawApp : Application() {
    val controller: SurfaceController by lazy { SurfaceController(this) }
}

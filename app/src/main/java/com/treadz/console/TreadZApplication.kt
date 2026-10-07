package com.treadz.console

import android.app.Application

class TreadZApplication : Application() {
    val dependencies: AppDependencies by lazy { RealDependencies(this) }
}

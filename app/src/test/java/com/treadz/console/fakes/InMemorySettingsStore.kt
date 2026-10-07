package com.treadz.console.fakes

import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.settings.SettingsStore

class InMemorySettingsStore(
    override var lastTreadmill: RememberedTreadmill? = null,
    override var darkTheme: Boolean = true,
) : SettingsStore

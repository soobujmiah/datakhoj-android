package dev.datakhoj.app

import android.app.Application
import dev.datakhoj.app.net.DuckDuckGoProvider
import dev.datakhoj.core.provider.ProviderRegistry

class DataKhojApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Register built-in sources. New data types plug in here.
        ProviderRegistry.register(DuckDuckGoProvider())
    }
}

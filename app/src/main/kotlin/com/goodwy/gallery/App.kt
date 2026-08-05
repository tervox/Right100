package com.goodwy.gallery

import android.content.ComponentCallbacks2
import com.bumptech.glide.Glide
import com.github.ajalt.reprint.core.Reprint
import com.goodwy.commons.RightApp
import com.goodwy.commons.helpers.PurchaseHelper
import com.goodwy.gallery.activities.MediaActivity
import com.squareup.picasso.Downloader
import com.squareup.picasso.Picasso
import okhttp3.Request
import okhttp3.Response

class App : RightApp() {

    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        PurchaseHelper().initPurchaseIfNeed(this, "1504831423")
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }

    // Único ponto do app que reage a avisos de memória baixa do sistema — antes não existia
    // nenhum. Ver comentário em MediaActivity.clearMemoryCaches() pra mais contexto.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val critical = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            MediaActivity.clearMemoryCaches(aggressive = critical)
            if (critical) {
                try {
                    Glide.get(this).clearMemory()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MediaActivity.clearMemoryCaches(aggressive = true)
        try {
            Glide.get(this).clearMemory()
        } catch (_: Exception) {
        }
    }
}

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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : RightApp() {

    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        setupCrashLogger()
        clearStaleGifThumbnailCacheOnce()
        PurchaseHelper().initPurchaseIfNeed(this, "1504831423")
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }

    // Uma versão anterior do app usou DiskCacheStrategy.RESOURCE, que guarda em disco QUALQUER
    // resultado decodificado — inclusive um resultado ruim/preto, se a decodificação falhar por
    // qualquer instabilidade momentânea (comum com várias GIFs decodificando ao mesmo tempo).
    // Isso já foi revertido no código, mas o que já ficou salvo em disco continua lá e continua
    // sendo reaproveitado (miniaturas pretas "grudadas"). Isso limpa esse cache de disco UMA
    // ÚNICA vez (marcado em SharedPreferences pra não repetir a cada abertura do app, o que
    // pioraria a performance em vez de ajudar).
    private fun clearStaleGifThumbnailCacheOnce() {
        val prefs = getSharedPreferences("right100_maintenance", MODE_PRIVATE)
        val key = "cleared_stale_glide_disk_cache_v1"
        if (!prefs.getBoolean(key, false)) {
            Thread {
                try {
                    // clearDiskCache() precisa rodar fora da UI thread (exigência do Glide)
                    Glide.get(this).clearDiskCache()
                } catch (_: Exception) {
                }
            }.start()
            prefs.edit().putBoolean(key, true).apply()
        }
    }

    // Sem PC/logcat disponível, não dava pra saber o motivo real dos crashes — só sobrava
    // adivinhar pelo código, o que já falhou 3 vezes. Isso grava o erro completo (stack trace)
    // num arquivo de texto simples, sempre que o app fechar sozinho por uma exceção não tratada.
    // Não precisa de adb nem permissão nenhuma: fica em
    //   /storage/emulated/0/Android/data/com.goodwy.gallery/files/crash_log.txt
    // que no Termux (com termux-setup-storage já configurado) é o mesmo que:
    //   ~/storage/shared/Android/data/com.goodwy.gallery/files/crash_log.txt
    // Depois de um crash, roda: cat ~/storage/shared/Android/data/com.goodwy.gallery/files/crash_log.txt
    private fun setupCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = File(getExternalFilesDir(null), "crash_log.txt")
                val stackTraceWriter = StringWriter()
                throwable.printStackTrace(PrintWriter(stackTraceWriter))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = "=== Crash em $timestamp (thread: ${thread.name}) ===\n" +
                    stackTraceWriter.toString() + "\n\n"
                logFile.appendText(entry)
            } catch (_: Exception) {
                // se a própria gravação do log falhar, não pode travar o processo de crash normal
            }
            // deixa o comportamento padrão acontecer (mostrar que o app parou, etc.)
            defaultHandler?.uncaughtException(thread, throwable)
        }
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

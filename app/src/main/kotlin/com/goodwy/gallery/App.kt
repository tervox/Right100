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
import java.util.concurrent.Executors

class App : RightApp() {

    companion object {
        private val maintenanceExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Right100-maintenance").apply {
                priority = Thread.MIN_PRIORITY
            }
        }

        // Pasta pública de Downloads: getExternalFilesDir() (usado antes) grava em
        // Android/data/com.goodwy.gallery/files/, que o Android bloqueia de QUALQUER outro app
        // enxergar desde a versão 11 — inclusive o Termux, mesmo com todas as permissões de
        // armazenamento concedidas. É por isso que os arquivos "não existiam": na verdade
        // provavelmente existiam, só que numa pasta que o Termux nunca conseguiria ler. Como
        // este app já pede MANAGE_EXTERNAL_STORAGE (precisa pra gerenciar arquivos de outras
        // pastas), dá pra gravar direto na pasta Download pública, que o Termux enxerga
        // normalmente em ~/storage/downloads/.
        private fun logsDir(): File {
            val dir = File("/storage/emulated/0/Download/Right100Logs")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        // Grava, numa linha de texto simples, o estado de memória do app e do sistema num
        // momento importante (abrir uma pasta, aviso de memória baixa, etc.). A ideia: mesmo
        // que o crash seja o Android matando o processo direto (sem exceção Java nenhuma — o
        // que aconteceria SEM aparecer nada no crash_log.txt), esse arquivo mostra o estado de
        // memória bem antes de o processo morrer. Se as últimas linhas antes de um crash mostram
        // heap quase cheio e sysLowMemory=true, é sinal forte de que o sistema matou o app por
        // falta de memória, não um bug de código específico.
        fun logMemoryState(context: android.content.Context, tag: String) {
            val line = try {
                val rt = Runtime.getRuntime()
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
                val maxMb = rt.maxMemory() / 1024 / 1024
                val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val sysAvailMb = memInfo.availMem / 1024 / 1024
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                "$timestamp [$tag] heapApp=${usedMb}MB/${maxMb}MB " +
                    "sistemaDisponivel=${sysAvailMb}MB sistemaPoucaMemoria=${memInfo.lowMemory}\n"
            } catch (_: Exception) {
                return
            }

            // O snapshot é calculado rapidamente; criação de diretório e I/O ficam fora da UI.
            maintenanceExecutor.execute {
                try {
                    val logFile = File(logsDir(), "memory_log.txt")
                    logFile.appendText(line)
                    // Só relê o arquivo quando ele ultrapassa um limite, evitando I/O em toda abertura.
                    if (logFile.length() > 64 * 1024) {
                        val lines = logFile.readLines()
                        logFile.writeText(lines.takeLast(300).joinToString("\n") + "\n")
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        setupCrashLogger()
        clearStaleGifThumbnailCacheOnce()
        logMemoryState(this, "app_start")
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
            maintenanceExecutor.execute {
                try {
                    // clearDiskCache() precisa rodar fora da UI thread (exigência do Glide)
                    Glide.get(this).clearDiskCache()
                } catch (_: Exception) {
                }
            }
            prefs.edit().putBoolean(key, true).apply()
        }
    }

    // Sem PC/logcat disponível, não dava pra saber o motivo real dos crashes — só sobrava
    // adivinhar pelo código. Isso grava o erro completo (stack trace) num arquivo de texto
    // simples, sempre que o app fechar sozinho por uma exceção não tratada.
    // Fica em /storage/emulated/0/Download/Right100Logs/crash_log.txt — pasta pública, visível
    // no Termux em ~/storage/downloads/Right100Logs/ (ver comentário em logsDir() acima sobre
    // por que NÃO fica em Android/data, que o Termux não consegue enxergar).
    // Depois de um crash, roda: cat ~/storage/downloads/Right100Logs/crash_log.txt
    private fun setupCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = File(logsDir(), "crash_log.txt")
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
        logMemoryState(this, "trim_memory_level_$level")
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
        logMemoryState(this, "low_memory")
        MediaActivity.clearMemoryCaches(aggressive = true)
        try {
            Glide.get(this).clearMemory()
        } catch (_: Exception) {
        }
    }
}

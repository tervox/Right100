package com.goodwy.gallery.extensions

import android.app.Activity
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.provider.Settings
import android.util.DisplayMetrics
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.dialogs.SecurityDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.extensions.getCurrentFormattedDateTime
import com.goodwy.commons.extensions.internalStoragePath
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.FAQItem
import com.goodwy.commons.models.FileDirItem
import com.goodwy.gallery.BuildConfig
import com.goodwy.gallery.R
import com.goodwy.gallery.activities.MediaActivity
import com.goodwy.gallery.activities.SettingsActivity
import com.goodwy.gallery.activities.SimpleActivity
import com.goodwy.gallery.activities.VideoPlayerActivity
import com.goodwy.gallery.dialogs.AllFilesPermissionDialog
import com.goodwy.gallery.dialogs.PickDirectoryDialog
import com.goodwy.gallery.helpers.DIRECTORY
import com.goodwy.gallery.dialogs.ResizeMultipleImagesDialog
import com.goodwy.gallery.dialogs.ResizeWithPathDialog
import com.goodwy.gallery.helpers.RECYCLE_BIN
import com.goodwy.gallery.helpers.TEMP_FOLDER_NAME
import com.goodwy.gallery.helpers.COPY_CONFLICT_ASK
import com.goodwy.gallery.helpers.FAB_TRASH_ASK
import com.goodwy.gallery.helpers.COPY_CONFLICT_KEEP_BOTH
import com.goodwy.gallery.helpers.COPY_CONFLICT_REPLACE
import com.goodwy.gallery.helpers.COPY_CONFLICT_SKIP
import com.goodwy.gallery.models.DateTaken
import com.squareup.picasso.Picasso
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale
import androidx.core.net.toUri
import com.goodwy.commons.dialogs.NewAppDialog
import kotlin.ranges.random

fun Activity.sharePath(path: String) {
    sharePathIntent(path, BuildConfig.APPLICATION_ID)
}

fun Activity.sharePaths(paths: ArrayList<String>) {
    sharePathsIntent(paths, BuildConfig.APPLICATION_ID)
}

fun Activity.shareMediumPath(path: String) {
    sharePath(path)
}

fun Activity.shareMediaPaths(paths: ArrayList<String>) {
    sharePaths(paths)
}

fun Activity.setAs(path: String) {
    setAsIntent(path, BuildConfig.APPLICATION_ID)
}

fun Activity.openPath(path: String, forceChooser: Boolean, extras: HashMap<String, Boolean> = HashMap()) {
    openPathIntent(path, forceChooser, BuildConfig.APPLICATION_ID, extras = extras)
}

fun Activity.launchGesturePlayer(path: String, extras: HashMap<String, Boolean> = HashMap()) {
    ensureBackgroundThread {
        val newUri = getFinalUriFromPath(path, BuildConfig.APPLICATION_ID)
        if (newUri == null) {
            toast(com.goodwy.commons.R.string.unknown_error_occurred)
            return@ensureBackgroundThread
        }

        val mimeType = getUriMimeType(path, newUri)
        runOnUiThread {
            Intent(applicationContext, VideoPlayerActivity::class.java).apply {
                setDataAndType(newUri, mimeType)
                for ((key, value) in extras) putExtra(key, value)
                startActivity(this)
            }
        }
    }
}

fun Activity.openEditor(path: String, forceChooser: Boolean = false) {
    val newPath = path.removePrefix("file://")
    openEditorIntent(newPath, forceChooser, BuildConfig.APPLICATION_ID)
}

fun Activity.launchCamera() {
    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
    launchActivityIntent(intent)
}

fun SimpleActivity.launchSettings() {
    hideKeyboard()
    startActivity(Intent(applicationContext, SettingsActivity::class.java))
}

fun SimpleActivity.launchAbout() {
    val licenses = LICENSE_GLIDE or LICENSE_CROPPER or LICENSE_RTL or LICENSE_SUBSAMPLING or LICENSE_PATTERN or LICENSE_REPRINT or LICENSE_GIF_DRAWABLE or
        LICENSE_PICASSO or LICENSE_EXOPLAYER or LICENSE_SANSELAN or LICENSE_FILTERS or LICENSE_GESTURE_VIEWS or LICENSE_APNG

    val faqItems = arrayListOf(
        FAQItem(R.string.faq_3_title, R.string.faq_3_text),
        FAQItem(R.string.faq_12_title, R.string.faq_12_text),
        FAQItem(R.string.faq_7_title, R.string.faq_7_text),
        FAQItem(R.string.faq_14_title, R.string.faq_14_text),
        FAQItem(R.string.faq_1_title_g, R.string.faq_1_text_g),
        FAQItem(com.goodwy.commons.R.string.faq_5_title_commons, com.goodwy.commons.R.string.faq_5_text_commons),
        FAQItem(R.string.faq_5_title, R.string.faq_5_text_g),
        FAQItem(R.string.faq_4_title, R.string.faq_4_text),
        FAQItem(R.string.faq_6_title, R.string.faq_6_text),
        FAQItem(R.string.faq_8_title, R.string.faq_8_text),
        FAQItem(R.string.faq_10_title, R.string.faq_10_text),
        FAQItem(R.string.faq_11_title, R.string.faq_11_text),
        FAQItem(R.string.faq_13_title, R.string.faq_13_text),
        FAQItem(R.string.faq_15_title, R.string.faq_15_text),
        FAQItem(R.string.faq_2_title, R.string.faq_2_text),
        FAQItem(R.string.faq_18_title, R.string.faq_18_text),
        FAQItem(com.goodwy.commons.R.string.faq_9_title_commons, com.goodwy.commons.R.string.faq_9_text_commons),
    )

    if (!resources.getBoolean(com.goodwy.commons.R.bool.hide_google_relations)) {
        faqItems.add(FAQItem(com.goodwy.commons.R.string.faq_2_title_commons, com.goodwy.strings.R.string.faq_2_text_commons_g))
        faqItems.add(FAQItem(com.goodwy.commons.R.string.faq_6_title_commons, com.goodwy.strings.R.string.faq_6_text_commons_g))
        faqItems.add(FAQItem(com.goodwy.commons.R.string.faq_7_title_commons, com.goodwy.commons.R.string.faq_7_text_commons))
        faqItems.add(FAQItem(com.goodwy.commons.R.string.faq_10_title_commons, com.goodwy.commons.R.string.faq_10_text_commons))
    }

    if (isRPlus() && !isExternalStorageManager()) {
        faqItems.add(0, FAQItem(R.string.faq_16_title, "${getString(R.string.faq_16_text)} ${getString(R.string.faq_16_text_extra)}"))
        faqItems.add(1, FAQItem(R.string.faq_17_title, R.string.faq_17_text))
        faqItems.removeIf { it.text == R.string.faq_7_text }
        faqItems.removeIf { it.text == R.string.faq_14_text }
        faqItems.removeIf { it.text == R.string.faq_8_text }
    }

    val productIdX1 = BuildConfig.PRODUCT_ID_X1
    val productIdX2 = BuildConfig.PRODUCT_ID_X2
    val productIdX3 = BuildConfig.PRODUCT_ID_X3
    val productIdX4 = BuildConfig.PRODUCT_ID_X4
    val subscriptionIdX1 = BuildConfig.SUBSCRIPTION_ID_X1
    val subscriptionIdX2 = BuildConfig.SUBSCRIPTION_ID_X2
    val subscriptionIdX3 = BuildConfig.SUBSCRIPTION_ID_X3
    val subscriptionYearIdX1 = BuildConfig.SUBSCRIPTION_YEAR_ID_X1
    val subscriptionYearIdX2 = BuildConfig.SUBSCRIPTION_YEAR_ID_X2
    val subscriptionYearIdX3 = BuildConfig.SUBSCRIPTION_YEAR_ID_X3

    val flavorName = BuildConfig.FLAVOR
    val storeDisplayName = when (flavorName) {
        "gplay" -> "Google Play"
        "foss" -> "FOSS"
        "rustore" -> "RuStore"
        else -> "Huawei"
    }
    val versionName = BuildConfig.VERSION_NAME
    val fullVersionText = "$versionName ($storeDisplayName)"

    startAboutActivity(
        appNameId = R.string.app_name,
        licenseMask = licenses,
        versionName = fullVersionText,
        flavorName = BuildConfig.FLAVOR,
        faqItems = faqItems,
        showFAQBeforeMail = true,
        productIdList= arrayListOf(productIdX1, productIdX2, productIdX3),
        productIdListRu = arrayListOf(productIdX1, productIdX2, productIdX4),
        subscriptionIdList = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionIdListRu = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionYearIdList = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
        subscriptionYearIdListRu = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
    )
}

fun BaseSimpleActivity.handleMediaManagementPrompt(callback: () -> Unit) {
    if (canManageMedia() || isExternalStorageManager()) {
        callback()
    } else if (isRPlus() && resources.getBoolean(R.bool.require_all_files_access) && !config.avoidShowingAllFilesPrompt) {
        if (Environment.isExternalStorageManager()) {
            callback()
        } else {
            var messagePrompt = getString(com.goodwy.commons.R.string.access_storage_prompt)
            messagePrompt += if (isSPlus()) {
                "\n\n${getString(R.string.media_management_alternative)}"
            } else {
                "\n\n${getString(R.string.alternative_media_access)}"
            }

            AllFilesPermissionDialog(this, messagePrompt, callback = { success ->
                if (success) {
                    launchGrantAllFilesIntent()
                }
            }, neutralPressed = {
                if (isSPlus()) {
                    launchMediaManagementIntent(callback)
                } else {
                    config.avoidShowingAllFilesPrompt = true
                }
            })
        }
    } else {
        callback()
    }
}

fun BaseSimpleActivity.launchGrantAllFilesIntent() {
    try {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.addCategory("android.intent.category.DEFAULT")
        intent.data = "package:$packageName".toUri()
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    } catch (e: Exception) {
        val intent = Intent()
        intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun AppCompatActivity.showSystemUI() {
    window.showBars()
}

fun AppCompatActivity.hideSystemUI() {
    window.hideBars(transient = false)
}

fun BaseSimpleActivity.addNoMedia(path: String, callback: () -> Unit) {
    val file = File(path, NOMEDIA)
    if (getDoesFilePathExist(file.absolutePath)) {
        callback()
        return
    }

    if (needsStupidWritePermissions(path)) {
        handleSAFDialog(file.absolutePath) {
            if (!it) {
                return@handleSAFDialog
            }

            val fileDocument = getDocumentFile(path)
            if (fileDocument?.exists() == true && fileDocument.isDirectory) {
                fileDocument.createFile("", NOMEDIA)
                addNoMediaIntoMediaStore(file.absolutePath)
                callback()
            } else {
                toast(com.goodwy.commons.R.string.unknown_error_occurred)
                callback()
            }
        }
    } else {
        try {
            if (file.createNewFile()) {
                ensureBackgroundThread {
                    addNoMediaIntoMediaStore(file.absolutePath)
                }
            } else {
                toast(com.goodwy.commons.R.string.unknown_error_occurred)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
        callback()
    }
}

fun BaseSimpleActivity.addNoMediaIntoMediaStore(path: String) {
    try {
        val content = ContentValues().apply {
            put(Files.FileColumns.TITLE, NOMEDIA)
            put(Files.FileColumns.DATA, path)
            put(Files.FileColumns.MEDIA_TYPE, Files.FileColumns.MEDIA_TYPE_NONE)
        }
        contentResolver.insert(Files.getContentUri("external"), content)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun BaseSimpleActivity.removeNoMedia(path: String, callback: (() -> Unit)? = null) {
    val file = File(path, NOMEDIA)
    if (!getDoesFilePathExist(file.absolutePath)) {
        callback?.invoke()
        return
    }

    tryDeleteFileDirItem(file.toFileDirItem(applicationContext), false, false) {
        callback?.invoke()
        deleteFromMediaStore(file.absolutePath) { needsRescan ->
            if (needsRescan) {
                rescanAndDeletePath(path) {
                    rescanFolderMedia(path)
                }
            } else {
                rescanFolderMedia(path)
            }
        }
    }
}

fun BaseSimpleActivity.toggleFileVisibility(oldPath: String, hide: Boolean, callback: ((newPath: String) -> Unit)? = null) {
    val path = oldPath.getParentPath()
    var filename = oldPath.getFilenameFromPath()
    if ((hide && filename.startsWith('.')) || (!hide && !filename.startsWith('.'))) {
        callback?.invoke(oldPath)
        return
    }

    filename = if (hide) {
        ".${filename.trimStart('.')}"
    } else {
        filename.substring(1, filename.length)
    }

    val newPath = "$path/$filename"
    renameFile(oldPath, newPath, false) { success, useAndroid30Way ->
        runOnUiThread {
            callback?.invoke(newPath)
        }

        ensureBackgroundThread {
            updateDBMediaPath(oldPath, newPath)
        }
    }
}

// Mostra um dialogo com o erro REAL (classe, mensagem e linhas da pilha) e um botao para copiar o
// texto. O toast do commons vinha cortado e o log fica numa pasta privada no build Release.
fun Throwable.toReport(): String =
    toString() + "\n" + stackTrace.take(8).joinToString("\n") {
        "  " + it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
    }

fun android.app.Activity.showFailureDialog(title: String, details: String) {
    runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(details)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Copiar texto") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("erro", details))
                }
                .show()
        } catch (_: Throwable) {
        }
    }
}

// Executa uma acao de UI; se lancar excecao, mostra o erro real em vez de fechar o app ou sumir.
fun android.app.Activity.safeRun(where: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        com.goodwy.gallery.App.logGesture("SAFERUN falhou em " + where + ": " + e.toReport().replace("\n", " | "))
        showFailureDialog("Erro em $where", e.toReport())
    }
}

// Caminho direto (java.io) para copiar/mover dentro do armazenamento principal quando o app tem
// acesso total a arquivos. O copyMoveFilesTo do commons falha com IllegalArgumentException (era o
// erro "java.lang" ao copiar). Antes o caminho direto so cobria casos "limpos"; todo o resto
// (nome ja existente no destino, mesma pasta de origem) caia no commons e quebrava. Agora esses
// casos sao resolvidos aqui: nome repetido vira "nome(1).ext" (manter os dois) e mover para a
// pasta onde o arquivo ja esta e ignorado. So SD/OTG e falta de acesso total usam o commons.
private fun BaseSimpleActivity.canDirectCopyMove(fileDirItems: ArrayList<FileDirItem>, destination: String): Boolean {
    if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return false
    val primary = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
    if (!destination.startsWith("$primary/") || !File(destination).isDirectory) return false
    return fileDirItems.all { it.path.startsWith("$primary/") && File(it.path).isFile }
}

// Escolhas "lembradas" SO ate o app fechar. Ficam na memoria do processo: com o app ativo ou em
// segundo plano elas continuam valendo; se o app for fechado ou reiniciado, voltam a perguntar.
// Escolha permanente so pelas opcoes em Configuracoes.
object SessionChoices {
    @Volatile var conflict: Int = COPY_CONFLICT_ASK
    @Volatile var fabTrash: Int = FAB_TRASH_ASK
}

// Dialogo com ate 3 botoes e ate 2 caixas ("aplicar a todos" e "lembrar"). Fechar sem escolher nao
// chama nada. choice: 1 = botao positivo, 2 = negativo, 3 = neutro.
fun android.app.Activity.showChoiceDialog(
    message: String, applyAllText: String?, rememberText: String?,
    positive: String, negative: String?, neutral: String?,
    onChoice: (choice: Int, applyAll: Boolean, remember: Boolean) -> Unit
) {
    runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        try {
            val density = resources.displayMetrics.density
            val pad = (20 * density).toInt()
            val root = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }
            root.addView(android.widget.TextView(this).apply {
                text = message
                textSize = 16f
            })
            var applyAllCheck: android.widget.CheckBox? = null
            var rememberCheck: android.widget.CheckBox? = null
            if (applyAllText != null) {
                applyAllCheck = android.widget.CheckBox(this).apply { text = applyAllText }
                root.addView(applyAllCheck)
            }
            if (rememberText != null) {
                rememberCheck = android.widget.CheckBox(this).apply { text = rememberText }
                root.addView(rememberCheck)
            }
            val builder = android.app.AlertDialog.Builder(this)
                .setView(root)
                .setPositiveButton(positive) { _, _ ->
                    onChoice(1, applyAllCheck?.isChecked == true, rememberCheck?.isChecked == true)
                }
            if (negative != null) {
                builder.setNegativeButton(negative) { _, _ ->
                    onChoice(2, applyAllCheck?.isChecked == true, rememberCheck?.isChecked == true)
                }
            }
            if (neutral != null) {
                builder.setNeutralButton(neutral) { _, _ ->
                    onChoice(3, applyAllCheck?.isChecked == true, rememberCheck?.isChecked == true)
                }
            }
            builder.show()
        } catch (_: Throwable) {
        }
    }
}

// Um conflito: o arquivo de origem e o que ja existe (ou o outro do mesmo lote com o mesmo nome).
// sameFile = copiar para a propria pasta de origem (so da para manter os dois ou ignorar).
private class CopyMoveConflict(val src: File, val other: File, val sameFile: Boolean)

private fun adaptConflictPolicy(policy: Int, sameFile: Boolean) =
    if (sameFile && policy == COPY_CONFLICT_REPLACE) COPY_CONFLICT_SKIP else policy

// Compara o CONTEUDO byte a byte (para no primeiro byte diferente). Tamanho diferente = diferente.
private fun filesIdentical(a: File, b: File): Boolean {
    if (a.length() != b.length()) return false
    if (a.canonicalPath == b.canonicalPath) return true
    var same = true
    java.io.FileInputStream(a).buffered(65536).use { ia ->
        java.io.FileInputStream(b).buffered(65536).use { ib ->
            val ba = ByteArray(65536)
            val bb = ByteArray(65536)
            loop@ while (true) {
                val ra = readFullyInto(ia, ba)
                val rb = readFullyInto(ib, bb)
                if (ra != rb) {
                    same = false
                    break@loop
                }
                if (ra <= 0) break@loop
                for (i in 0 until ra) {
                    if (ba[i] != bb[i]) {
                        same = false
                        break@loop
                    }
                }
            }
        }
    }
    return same
}

private fun readFullyInto(input: java.io.InputStream, buf: ByteArray): Int {
    var total = 0
    while (total < buf.size) {
        val n = input.read(buf, total, buf.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

private fun BaseSimpleActivity.buildConflictMessage(c: CopyMoveConflict, identical: Boolean?, remaining: Int): String {
    val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
    val sb = StringBuilder()
    if (c.sameFile) {
        sb.append("\"").append(c.src.name).append("\" já está nesta pasta. Copiar aqui cria uma segunda cópia.")
    } else {
        sb.append("Já existe \"").append(c.src.name).append("\" nesta pasta.")
        sb.append("\n\nOrigem: ").append(c.src.length().formatSize())
            .append(" • ").append(df.format(java.util.Date(c.src.lastModified())))
        sb.append("\nJá existente: ").append(c.other.length().formatSize())
            .append(" • ").append(df.format(java.util.Date(c.other.lastModified())))
        sb.append("\n\n")
        sb.append(
            when (identical) {
                true -> "Os dois arquivos são IDÊNTICOS (mesmo conteúdo)."
                false -> "Os arquivos são DIFERENTES."
                null -> "Não foi possível comparar o conteúdo."
            }
        )
    }
    sb.append("\n\nO que fazer?")
    if (remaining > 1) sb.append("\n(+").append(remaining - 1).append(" outro(s) com o mesmo problema)")
    return sb.toString()
}

// Pergunta, arquivo por arquivo, o que fazer com cada conflito (com "aplicar a todos os restantes").
private fun BaseSimpleActivity.askNextConflict(
    conflicts: List<CopyMoveConflict>, index: Int, decisions: HashMap<String, Int>,
    onDone: (decisions: HashMap<String, Int>) -> Unit
) {
    if (index >= conflicts.size) {
        runOnUiThread { onDone(decisions) }
        return
    }
    val c = conflicts[index]
    ensureBackgroundThread {
        if (!c.sameFile && c.src.length() > 50L * 1024 * 1024) {
            runOnUiThread { toast("Comparando arquivos...") }
        }
        val identical = try {
            if (c.sameFile) true else filesIdentical(c.src, c.other)
        } catch (_: Throwable) {
            null
        }
        val remaining = conflicts.size - index
        showChoiceDialog(
            buildConflictMessage(c, identical, remaining),
            if (remaining > 1) "Aplicar a todos os restantes ($remaining)" else null,
            "Lembrar escolha até fechar o app",
            "Manter os dois",
            if (c.sameFile) null else "Substituir",
            "Ignorar"
        ) { choice, applyAll, remember ->
            val policy = when (choice) {
                2 -> COPY_CONFLICT_REPLACE
                3 -> COPY_CONFLICT_SKIP
                else -> COPY_CONFLICT_KEEP_BOTH
            }
            if (remember) SessionChoices.conflict = policy
            if (applyAll) {
                for (i in index until conflicts.size) {
                    decisions[conflicts[i].src.path] = adaptConflictPolicy(policy, conflicts[i].sameFile)
                }
                onDone(decisions)
            } else {
                decisions[c.src.path] = adaptConflictPolicy(policy, c.sameFile)
                askNextConflict(conflicts, index + 1, decisions, onDone)
            }
        }
    }
}

// Descobre os conflitos e decide: pela escolha fixa das Configuracoes, pela lembrada ate o app
// fechar, ou perguntando. NUNCA cria "nome(1)" sozinho: so quando a escolha e "Manter os dois".
private fun BaseSimpleActivity.resolveCopyMoveConflicts(
    fileDirItems: ArrayList<FileDirItem>, destination: String, isCopyOperation: Boolean,
    onDone: (decisions: HashMap<String, Int>) -> Unit
) {
    ensureBackgroundThread {
        val destDir = File(destination)
        val destPath = destDir.absolutePath.trimEnd('/')
        val conflicts = ArrayList<CopyMoveConflict>()
        val firstSourceByName = HashMap<String, File>()
        for (item in fileDirItems) {
            val src = File(item.path)
            val sameFolder = src.parentFile?.absolutePath?.trimEnd('/') == destPath
            // Mover para a pasta onde ja esta nao faz nada.
            if (!isCopyOperation && sameFolder) continue
            val dst = File(destDir, src.name)
            val earlier = firstSourceByName[src.name]
            when {
                sameFolder -> conflicts.add(CopyMoveConflict(src, dst, true))
                dst.exists() -> conflicts.add(CopyMoveConflict(src, dst, false))
                earlier != null -> conflicts.add(CopyMoveConflict(src, earlier, false))
            }
            if (!firstSourceByName.containsKey(src.name)) firstSourceByName[src.name] = src
        }

        val decisions = HashMap<String, Int>()
        if (conflicts.isEmpty()) {
            runOnUiThread { onDone(decisions) }
            return@ensureBackgroundThread
        }

        val fixed = if (config.conflictAction != COPY_CONFLICT_ASK) config.conflictAction else SessionChoices.conflict
        if (fixed != COPY_CONFLICT_ASK) {
            conflicts.forEach { decisions[it.src.path] = adaptConflictPolicy(fixed, it.sameFile) }
            runOnUiThread { onDone(decisions) }
            return@ensureBackgroundThread
        }

        askNextConflict(conflicts, 0, decisions, onDone)
    }
}

private fun BaseSimpleActivity.directCopyMoveFiles(
    fileDirItems: ArrayList<FileDirItem>, destination: String, isCopyOperation: Boolean,
    decisions: HashMap<String, Int>, callback: (destinationPath: String) -> Unit
) {
    android.widget.Toast.makeText(this, if (isCopyOperation) "Copiando..." else "Movendo...", android.widget.Toast.LENGTH_SHORT).show()
    ensureBackgroundThread {
        val touched = ArrayList<String>()
        val failures = ArrayList<String>()
        var okCount = 0
        var renamedCount = 0
        var replacedCount = 0
        var skippedCount = 0
        val destDir = File(destination)
        val destPath = destDir.absolutePath.trimEnd('/')
        for (item in fileDirItems) {
            // Cada arquivo tem seu proprio try/catch: um erro nao derruba os outros.
            try {
                val src = File(item.path)
                val sameFolder = src.parentFile?.absolutePath?.trimEnd('/') == destPath
                if (!isCopyOperation && sameFolder) {
                    skippedCount++
                    continue
                }
                var dst = File(destDir, src.name)
                var replacing = false
                val decision = decisions[src.path]
                if (decision != null) {
                    when (decision) {
                        COPY_CONFLICT_SKIP -> {
                            skippedCount++
                            continue
                        }

                        COPY_CONFLICT_REPLACE -> replacing = true

                        else -> {
                            // Manter os dois (escolha do usuario): nome(1), nome(2)...
                            if (dst.exists()) {
                                var n = 1
                                while (dst.exists()) {
                                    val ext = if (src.extension.isEmpty()) "" else "." + src.extension
                                    dst = File(destDir, src.nameWithoutExtension + "(" + n + ")" + ext)
                                    n++
                                }
                                renamedCount++
                            }
                        }
                    }
                } else if (dst.exists()) {
                    // Apareceu depois da checagem e ninguem decidiu: nunca sobrescreve nem renomeia sozinho.
                    skippedCount++
                    continue
                }
                val modified = src.lastModified()
                var done = false
                if (!isCopyOperation) {
                    // rename sobrescreve o destino de forma atomica quando ele existe (mesmo volume)
                    done = try {
                        src.renameTo(dst)
                    } catch (_: Exception) {
                        false
                    }
                }
                if (!done) {
                    if (replacing) {
                        // Copia para um arquivo temporario e so entao troca: se falhar, o original do
                        // destino continua intacto.
                        val tmp = File(destDir, ".r100tmp_" + System.nanoTime() + "_" + src.name)
                        try {
                            src.copyTo(tmp, false)
                            if (tmp.length() != src.length()) {
                                throw java.io.IOException("tamanho diferente apos copiar: " + tmp.length() + " != " + src.length())
                            }
                            if (modified > 0L) tmp.setLastModified(modified)
                            if (!tmp.renameTo(dst)) {
                                throw java.io.IOException("nao foi possivel substituir " + dst.name)
                            }
                        } finally {
                            if (tmp.exists()) tmp.delete()
                        }
                        if (!isCopyOperation) src.delete()
                    } else {
                        src.copyTo(dst, false)
                        if (modified > 0L) dst.setLastModified(modified)
                        if (!isCopyOperation) {
                            if (dst.length() != src.length()) {
                                dst.delete()
                                throw java.io.IOException("tamanho diferente apos copiar: " + dst.length() + " != " + src.length())
                            }
                            src.delete()
                        }
                    }
                }
                if (replacing) replacedCount++
                okCount++
                touched.add(src.absolutePath)
                touched.add(dst.absolutePath)
                com.goodwy.gallery.App.logGesture("COPYDIAG direto ok " + src.name + " -> " + dst.name + " copy=" + isCopyOperation + " replaced=" + replacing)
                Right100Diag.add((if (isCopyOperation) "copiar" else "mover") + " ok: " + src.path + " -> " + dst.path + (if (replacing) " (substituiu)" else ""))
            } catch (e: Throwable) {
                failures.add(File(item.path).name + ": " + e.toString())
                com.goodwy.gallery.App.logGesture("COPYDIAG direto FALHOU: " + e.toReport().replace("\n", " | "))
            }
        }
        if (touched.isNotEmpty()) {
            try {
                // Espera o sistema indexar os arquivos (ate 6 s) ANTES de atualizar as telas. Antes a
                // lista era relida na hora, com o MediaStore ainda desatualizado: o arquivo aparecia e
                // sumia, e as vezes nao aparecia mais.
                val latch = java.util.concurrent.CountDownLatch(touched.size)
                val scanStart = System.currentTimeMillis()
                android.media.MediaScannerConnection.scanFile(applicationContext, touched.toTypedArray(), null) { p, u ->
                    Right100Diag.add("indexacao: " + p + " -> " + (u?.toString() ?: "SEM LINHA no MediaStore"))
                    latch.countDown()
                }
                val finished = latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
                Right100Diag.add("indexacao " + (if (finished) "concluida" else "NAO concluiu em 6 s") + " em " + (System.currentTimeMillis() - scanStart) + " ms (" + touched.size + " caminhos)")
            } catch (_: Throwable) {
            }
        }
        runOnUiThread {
            // Callback so quando algo realmente mudou (atualiza lista, favoritos e contadores).
            if (okCount > 0) callback(destination)
            val verb = if (isCopyOperation) "Copiado" else "Movido"
            if (failures.isEmpty()) {
                val notes = ArrayList<String>()
                if (renamedCount > 0) notes.add("$renamedCount mantidos com nome(1)")
                if (replacedCount > 0) notes.add("$replacedCount substituídos")
                if (skippedCount > 0) notes.add("$skippedCount ignorados")
                val extra = if (notes.isEmpty()) "" else " (" + notes.joinToString(", ") + ")"
                when {
                    okCount > 0 -> toast("$verb com sucesso$extra")
                    skippedCount > 0 -> toast("Nada a fazer: " + notes.joinToString(", "))
                    else -> Unit
                }
            } else {
                showFailureDialog(
                    "$okCount ok, ${failures.size} falharam",
                    failures.take(5).joinToString("\n\n") + if (failures.size > 5) "\n\n(+${failures.size - 5} falhas)" else ""
                )
            }
        }
    }
}

// Diagnostico temporario: o toast de erro ao copiar/mover vem cortado e a excecao e tratada
// dentro do commons (nem crash_log nem logcat mostram o texto). Reproduz aqui, em background e
// com try/catch, o primeiro passo do commons (abrir o destino pra escrita) e grava tudo em
// gesture_log.txt (linhas COPYDIAG).
private fun BaseSimpleActivity.logCopyMoveDiagnostics(
    fileDirItems: ArrayList<FileDirItem>, source: String, destination: String, isCopyOperation: Boolean
) {
    val firstItem = fileDirItems.first()
    val sdk = android.os.Build.VERSION.SDK_INT
    com.goodwy.gallery.App.logGesture("COPYDIAG inicio copy=$isCopyOperation sdk=$sdk itens=${fileDirItems.size} source=$source destino=$destination primeiro=${firstItem.path}")
    ensureBackgroundThread {
        fun fmt(e: Throwable): String = e.toString() + " @ " + e.stackTrace.take(10).joinToString(" | ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        try {
            val allFiles = if (sdk >= 30) android.os.Environment.isExternalStorageManager() else true
            com.goodwy.gallery.App.logGesture("COPYDIAG allFilesAccess=$allFiles srcExists=${File(firstItem.path).exists()} srcCanRead=${File(firstItem.path).canRead()} dstExists=${File(destination).exists()} dstIsDir=${File(destination).isDirectory} dstCanWrite=${File(destination).canWrite()} dstViaCommons=${getDoesFilePathExist(destination)}")
        } catch (e: Throwable) {
            com.goodwy.gallery.App.logGesture("COPYDIAG preflight FALHOU: ${fmt(e)}")
        }
    }
}

fun BaseSimpleActivity.tryCopyMoveFilesTo(fileDirItems: ArrayList<FileDirItem>, isCopyOperation: Boolean, callback: (destinationPath: String) -> Unit) {
    if (fileDirItems.isEmpty()) {
        toast(com.goodwy.commons.R.string.unknown_error_occurred)
        return
    }

    val source = fileDirItems[0].getParentPath()
    PickDirectoryDialog(this, source, true, false, true, false) {
        val destination = it
        logCopyMoveDiagnostics(fileDirItems, source, destination, isCopyOperation)
        if (canDirectCopyMove(fileDirItems, destination)) {
            resolveCopyMoveConflicts(fileDirItems, destination, isCopyOperation) { decisions ->
                directCopyMoveFiles(fileDirItems, destination, isCopyOperation, decisions, callback)
            }
        } else handleSAFDialog(source) { sourceGranted ->
            if (sourceGranted) {
                handleSAFDialogSdk30(destination) { destGranted ->
                    if (destGranted) {
                      safeRun("Copiar/Mover (commons)") {
                        copyMoveFilesTo(fileDirItems, source.trimEnd('/'), destination, isCopyOperation, true, config.shouldShowHidden) { copiedTo ->
                            com.goodwy.gallery.App.logGesture("COPYDIAG copiar/mover venceu destino=$copiedTo")
                            // Sem isto a operacao terminava em silencio e o usuario ficava
                            // sem confirmacao de que deu certo.
                            runOnUiThread {
                                toast(if (isCopyOperation) "Copiado com sucesso" else "Movido com sucesso")
                            }
                            callback(copiedTo)
                        }
                      }
                    }
                }
            }
        }
    }
}

fun BaseSimpleActivity.tryDeleteFileDirItem(
    fileDirItem: FileDirItem, allowDeleteFolder: Boolean = false, deleteFromDatabase: Boolean,
    callback: ((wasSuccess: Boolean) -> Unit)? = null
) {
    deleteFile(fileDirItem, allowDeleteFolder, isDeletingMultipleFiles = false) {
        if (deleteFromDatabase) {
            ensureBackgroundThread {
                deleteDBPath(fileDirItem.path)
                runOnUiThread {
                    callback?.invoke(it)
                }
            }
        } else {
            callback?.invoke(it)
        }
    }
}

fun BaseSimpleActivity.movePathsInRecycleBin(paths: ArrayList<String>, callback: ((wasSuccess: Boolean) -> Unit)?) {
    ensureBackgroundThread {
        var pathsCnt = paths.size
        val OTGPath = config.OTGPath

        for (source in paths) {
            if (OTGPath.isNotEmpty() && source.startsWith(OTGPath)) {
                var inputStream: InputStream? = null
                var out: OutputStream? = null
                try {
                    val destination = "$recycleBinPath/$source"
                    val fileDocument = getSomeDocumentFile(source)
                    inputStream = applicationContext.contentResolver.openInputStream(fileDocument?.uri!!)
                    out = getFileOutputStreamSync(destination, source.getMimeType())

                    var copiedSize = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytes = inputStream!!.read(buffer)
                    while (bytes >= 0) {
                        out!!.write(buffer, 0, bytes)
                        copiedSize += bytes
                        bytes = inputStream.read(buffer)
                    }

                    out?.flush()

                    if (fileDocument.getItemSize(true) == copiedSize && getDoesFilePathExist(destination)) {
                        mediaDB.updateDeleted("$RECYCLE_BIN$source", System.currentTimeMillis(), source)
                        pathsCnt--
                    }
                } catch (e: Exception) {
                    showErrorToast(e)
                    continue
                } finally {
                    inputStream?.close()
                    out?.close()
                }
            } else {
                val file = File(source)
                val internalFile = File(recycleBinPath, source)
                val lastModified = file.lastModified()
                try {
                    if (file.copyRecursively(internalFile, true)) {
                        mediaDB.updateDeleted("$RECYCLE_BIN$source", System.currentTimeMillis(), source)
                        pathsCnt--

                        if (config.keepLastModified && lastModified != 0L) {
                            internalFile.setLastModified(lastModified)
                        }
                    }
                } catch (e: Exception) {
                    showErrorToast(e)
                    continue
                }
            }
        }
        callback?.invoke(pathsCnt == 0)
    }
}

fun BaseSimpleActivity.restoreRecycleBinPath(path: String, callback: () -> Unit) {
    restoreRecycleBinPaths(arrayListOf(path), callback)
}

fun BaseSimpleActivity.restoreRecycleBinPaths(paths: ArrayList<String>, callback: () -> Unit) {
    ensureBackgroundThread {
        val newPaths = ArrayList<String>()
        var shownRestoringToPictures = false
        for (source in paths) {
            var destination = source.removePrefix(recycleBinPath)

            val destinationParent = destination.getParentPath()
            if (isRestrictedWithSAFSdk30(destinationParent) && !isInDownloadDir(destinationParent)) {
                // if the file is not writeable on SDK30+, change it to Pictures
                val picturesDirectory = getPicturesDirectoryPath(destination)
                destination = File(picturesDirectory, destination.getFilenameFromPath()).path
                if (!shownRestoringToPictures) {
                    toast(getString(R.string.restore_to_path, humanizePath(picturesDirectory)))
                    shownRestoringToPictures = true
                }
            }

            val lastModified = File(source).lastModified()

            val isShowingSAF = handleSAFDialog(destination) {}
            if (isShowingSAF) {
                return@ensureBackgroundThread
            }

            val isShowingSAFSdk30 = handleSAFDialogSdk30(destination) {}
            if (isShowingSAFSdk30) {
                return@ensureBackgroundThread
            }

            if (getDoesFilePathExist(destination)) {
                val newFile = getAlternativeFile(File(destination))
                destination = newFile.path
            }

            var inputStream: InputStream? = null
            var out: OutputStream? = null
            try {
                out = getFileOutputStreamSync(destination, source.getMimeType())
                inputStream = getFileInputStreamSync(source)

                var copiedSize = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes = inputStream!!.read(buffer)
                while (bytes >= 0) {
                    out!!.write(buffer, 0, bytes)
                    copiedSize += bytes
                    bytes = inputStream.read(buffer)
                }

                out?.flush()

                if (File(source).length() == copiedSize) {
                    mediaDB.updateDeleted(destination.removePrefix(recycleBinPath), 0, "$RECYCLE_BIN${source.removePrefix(recycleBinPath)}")
                }
                newPaths.add(destination)

                if (config.keepLastModified && lastModified != 0L) {
                    File(destination).setLastModified(lastModified)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            } finally {
                inputStream?.close()
                out?.close()
            }
        }

        runOnUiThread {
            callback()
        }

        rescanPaths(newPaths) {
            fixDateTaken(newPaths, false)
        }
    }
}

fun BaseSimpleActivity.emptyTheRecycleBin(callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        try {
            recycleBin.deleteRecursively()
            mediaDB.clearRecycleBin()
            directoryDB.deleteRecycleBin()
            toast(com.goodwy.commons.R.string.recycle_bin_emptied)
            callback?.invoke()
        } catch (e: Exception) {
            toast(com.goodwy.commons.R.string.unknown_error_occurred)
        }
    }
}

fun BaseSimpleActivity.emptyAndDisableTheRecycleBin(callback: () -> Unit) {
    ensureBackgroundThread {
        emptyTheRecycleBin {
            config.useRecycleBin = false
            callback()
        }
    }
}

fun BaseSimpleActivity.showRecycleBinEmptyingDialog(callback: () -> Unit) {
    ConfirmationDialog(
        this,
        "",
        com.goodwy.commons.R.string.empty_recycle_bin_confirmation,
        com.goodwy.commons.R.string.yes,
        com.goodwy.commons.R.string.no
    ) {
        callback()
    }
}

fun BaseSimpleActivity.showRestoreConfirmationDialog(count: Int, callback: () -> Unit) {
    ConfirmationDialog(
        activity = this,
        message = resources.getQuantityString(R.plurals.restore_confirmation, count, count),
        positive = com.goodwy.commons.R.string.yes,
        negative = com.goodwy.commons.R.string.no
    ) {
        callback()
    }
}

fun BaseSimpleActivity.updateFavoritePaths(fileDirItems: ArrayList<FileDirItem>, destination: String) {
    ensureBackgroundThread {
        fileDirItems.forEach {
            val newPath = "$destination/${it.name}"
            updateDBMediaPath(it.path, newPath)
        }
    }
}

fun Activity.hasNavBar(): Boolean {
    val display = windowManager.defaultDisplay

    val realDisplayMetrics = DisplayMetrics()
    display.getRealMetrics(realDisplayMetrics)

    val displayMetrics = DisplayMetrics()
    display.getMetrics(displayMetrics)

    return (realDisplayMetrics.widthPixels - displayMetrics.widthPixels > 0) || (realDisplayMetrics.heightPixels - displayMetrics.heightPixels > 0)
}

fun AppCompatActivity.fixDateTaken(
    paths: ArrayList<String>,
    showToasts: Boolean,
    hasRescanned: Boolean = false,
    callback: (() -> Unit)? = null
) {
    val BATCH_SIZE = 50
    if (showToasts && !hasRescanned) {
        toast(R.string.fixing)
    }

    val pathsToRescan = ArrayList<String>()
    try {
        var didUpdateFile = false
        val operations = ArrayList<ContentProviderOperation>()

        ensureBackgroundThread {
            val dateTakens = ArrayList<DateTaken>()

            for (path in paths) {
                try {
                    val dateTime: String = ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME) ?: continue

                    // some formats contain a "T" in the middle, some don't
                    // sample dates: 2015-07-26T14:55:23, 2018:09:05 15:09:05
                    val t = if (dateTime.substring(10, 11) == "T") "\'T\'" else " "
                    val separator = dateTime.substring(4, 5)
                    val format = "yyyy${separator}MM${separator}dd${t}kk:mm:ss"
                    val formatter = SimpleDateFormat(format, Locale.getDefault())
                    val timestamp = formatter.parse(dateTime).time

                    val uri = getFileUri(path)
                    ContentProviderOperation.newUpdate(uri).apply {
                        val selection = "${Images.Media.DATA} = ?"
                        val selectionArgs = arrayOf(path)
                        withSelection(selection, selectionArgs)
                        withValue(Images.Media.DATE_TAKEN, timestamp)
                        operations.add(build())
                    }

                    if (operations.size % BATCH_SIZE == 0) {
                        contentResolver.applyBatch(MediaStore.AUTHORITY, operations)
                        operations.clear()
                    }

                    mediaDB.updateFavoriteDateTaken(path, timestamp)
                    didUpdateFile = true

                    val dateTaken = DateTaken(
                        null,
                        path,
                        path.getFilenameFromPath(),
                        path.getParentPath(),
                        timestamp,
                        (System.currentTimeMillis() / 1000).toInt(),
                        File(path).lastModified()
                    )
                    dateTakens.add(dateTaken)
                    if (!hasRescanned && getFileDateTaken(path) == 0L) {
                        pathsToRescan.add(path)
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            if (!didUpdateFile) {
                if (showToasts) {
                    toast(R.string.no_date_takens_found)
                }

                runOnUiThread {
                    callback?.invoke()
                }
                return@ensureBackgroundThread
            }

            val resultSize = contentResolver.applyBatch(MediaStore.AUTHORITY, operations).size
            if (resultSize == 0) {
                didUpdateFile = false
            }

            if (hasRescanned || pathsToRescan.isEmpty()) {
                if (dateTakens.isNotEmpty()) {
                    dateTakensDB.insertAll(dateTakens)
                }

                runOnUiThread {
                    if (showToasts) {
                        toast(if (didUpdateFile) R.string.dates_fixed_successfully else com.goodwy.commons.R.string.unknown_error_occurred)
                    }

                    callback?.invoke()
                }
            } else {
                rescanPaths(pathsToRescan) {
                    fixDateTaken(paths, showToasts, true, callback)
                }
            }
        }
    } catch (e: Exception) {
        if (showToasts) {
            showErrorToast(e)
        }
    }
}

fun BaseSimpleActivity.saveRotatedImageToFile(oldPath: String, newPath: String, degrees: Int, showToasts: Boolean, callback: () -> Unit) {
    var newDegrees = degrees
    if (newDegrees < 0) {
        newDegrees += 360
    }

    if (oldPath == newPath && oldPath.isJpg()) {
        if (tryRotateByExif(oldPath, newDegrees, showToasts, callback)) {
            return
        }
    }

    val tmpPath = "$recycleBinPath/.tmp_${newPath.getFilenameFromPath()}"
    val tmpFileDirItem = FileDirItem(tmpPath, tmpPath.getFilenameFromPath())
    try {
        getFileOutputStream(tmpFileDirItem) {
            if (it == null) {
                if (showToasts) {
                    toast(com.goodwy.commons.R.string.unknown_error_occurred)
                }
                return@getFileOutputStream
            }

            val oldLastModified = File(oldPath).lastModified()
            if (oldPath.isJpg()) {
                copyFile(oldPath, tmpPath)
                saveExifRotation(ExifInterface(tmpPath), newDegrees)
            } else {
                val inputstream = getFileInputStreamSync(oldPath)
                val bitmap = BitmapFactory.decodeStream(inputstream)
                saveFile(tmpPath, bitmap, it as FileOutputStream, newDegrees)
            }

            copyFile(tmpPath, newPath)
            rescanPaths(arrayListOf(newPath))
            fileRotatedSuccessfully(newPath, oldLastModified)

            it.flush()
            it.close()
            callback.invoke()
        }
    } catch (e: OutOfMemoryError) {
        if (showToasts) {
            toast(com.goodwy.commons.R.string.out_of_memory_error)
        }
    } catch (e: Exception) {
        if (showToasts) {
            showErrorToast(e)
        }
    } finally {
        tryDeleteFileDirItem(tmpFileDirItem, false, true)
    }
}

fun Activity.tryRotateByExif(path: String, degrees: Int, showToasts: Boolean, callback: () -> Unit): Boolean {
    return try {
        val file = File(path)
        val oldLastModified = file.lastModified()
        if (saveImageRotation(path, degrees)) {
            fileRotatedSuccessfully(path, oldLastModified)
            callback.invoke()
            if (showToasts) {
                toast(com.goodwy.commons.R.string.file_saved)
            }
            true
        } else {
            false
        }
    } catch (e: Exception) {
        // lets not show IOExceptions, rotating is saved just fine even with them
        if (showToasts && e !is IOException) {
            showErrorToast(e)
        }
        false
    }
}

fun Activity.fileRotatedSuccessfully(path: String, lastModified: Long) {
    if (config.keepLastModified && lastModified != 0L) {
        File(path).setLastModified(lastModified)
        updateLastModified(path, lastModified)
    }

    Picasso.get().invalidate(path.getFileKey(lastModified))
    // we cannot refresh a specific image in Glide Cache, so just clear it all
    val glide = Glide.get(applicationContext)
    glide.clearDiskCache()
    runOnUiThread {
        glide.clearMemory()
    }
}

fun BaseSimpleActivity.copyFile(source: String, destination: String) {
    var inputStream: InputStream? = null
    var out: OutputStream? = null
    try {
        out = getFileOutputStreamSync(destination, source.getMimeType())
        inputStream = getFileInputStreamSync(source)
        inputStream!!.copyTo(out!!)
    } catch (e: Exception) {
        showErrorToast(e)
    } finally {
        inputStream?.close()
        out?.close()
    }
}

fun BaseSimpleActivity.ensureWriteAccess(path: String, callback: () -> Unit) {
    when {
        isRestrictedSAFOnlyRoot(path) -> {
            handleAndroidSAFDialog(path) {
                if (!it) {
                    return@handleAndroidSAFDialog
                }
                callback.invoke()
            }
        }

        needsStupidWritePermissions(path) -> {
            handleSAFDialog(path) {
                if (!it) {
                    return@handleSAFDialog
                }
                callback()
            }
        }

        isAccessibleWithSAFSdk30(path) -> {
            handleSAFDialogSdk30(path) {
                if (!it) {
                    return@handleSAFDialogSdk30
                }
                callback()
            }
        }

        else -> {
            callback()
        }
    }
}

fun BaseSimpleActivity.launchResizeMultipleImagesDialog(paths: List<String>, callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        val imagePaths = mutableListOf<String>()
        val imageSizes = mutableListOf<Point>()
        for (path in paths) {
            val size = path.getImageResolution(this)
            if (size != null) {
                imagePaths.add(path)
                imageSizes.add(size)
            }
        }

        runOnUiThread {
            ResizeMultipleImagesDialog(this, imagePaths, imageSizes) {
                callback?.invoke()
            }
        }
    }
}

fun BaseSimpleActivity.launchResizeImageDialog(path: String, callback: (() -> Unit)? = null) {
    val originalSize = path.getImageResolution(this) ?: return
    ResizeWithPathDialog(this, originalSize, path) { newSize, newPath ->
        ensureBackgroundThread {
            val file = File(newPath)
            val pathLastModifiedMap = mapOf(file.absolutePath to file.lastModified())
            try {
                resizeImage(path, newPath, newSize) { success ->
                    if (success) {
                        toast(com.goodwy.commons.R.string.file_saved)

                        val paths = arrayListOf(file.absolutePath)
                        rescanPathsAndUpdateLastModified(paths, pathLastModifiedMap) {
                            runOnUiThread {
                                callback?.invoke()
                            }
                        }
                    } else {
                        toast(R.string.image_editing_failed)
                    }
                }
            } catch (e: OutOfMemoryError) {
                toast(com.goodwy.commons.R.string.out_of_memory_error)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }
}

fun BaseSimpleActivity.resizeImage(oldPath: String, newPath: String, size: Point, callback: (success: Boolean) -> Unit) {
    var oldExif: ExifInterface?
    val inputStream = contentResolver.openInputStream(Uri.fromFile(File(oldPath)))
    oldExif = ExifInterface(inputStream!!)

    val newBitmap = Glide.with(applicationContext).asBitmap().load(oldPath).submit(size.x, size.y).get()

    val newFile = File(newPath)
    val newFileDirItem = FileDirItem(newPath, newPath.getFilenameFromPath())
    getFileOutputStream(newFileDirItem, true) { out ->
        if (out != null) {
            out.use {
                try {
                    newBitmap.compress(newFile.absolutePath.getCompressionFormat(), 90, out)

                    val newExif = ExifInterface(newFile.absolutePath)
                    oldExif.copyNonDimensionAttributesTo(newExif)
                } catch (ignored: Exception) {
                }

                callback(true)
            }
        } else {
            callback(false)
        }
    }
}

fun BaseSimpleActivity.rescanPathsAndUpdateLastModified(paths: ArrayList<String>, pathLastModifiedMap: Map<String, Long>, callback: () -> Unit) {
    fixDateTaken(paths, false)
    for (path in paths) {
        val file = File(path)
        val lastModified = pathLastModifiedMap[path]
        if (config.keepLastModified && lastModified != null && lastModified != 0L) {
            File(file.absolutePath).setLastModified(lastModified)
            updateLastModified(file.absolutePath, lastModified)
        }
    }
    rescanPaths(paths, callback)
}

fun saveFile(path: String, bitmap: Bitmap, out: FileOutputStream, degrees: Int) {
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    val bmp = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    bmp.compress(path.getCompressionFormat(), 90, out)
}

fun Activity.getShortcutImage(tmb: String, drawable: Drawable, callback: () -> Unit) {
    ensureBackgroundThread {
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .fitCenter()

        val size = resources.getDimension(com.goodwy.commons.R.dimen.shortcut_size).toInt()
        val builder = Glide.with(this)
            .asDrawable()
            .load(tmb)
            .apply(options)
            .centerCrop()
            .into(size, size)

        try {
            (drawable as LayerDrawable).setDrawableByLayerId(R.id.shortcut_image, builder.get())
        } catch (e: Exception) {
        }

        runOnUiThread {
            callback()
        }
    }
}

fun Activity.showFileOnMap(path: String) {
    val exif = try {
        if (path.startsWith("content://")) {
            ExifInterface(contentResolver.openInputStream(path.toUri())!!)
        } else {
            ExifInterface(path)
        }
    } catch (e: Exception) {
        showErrorToast(e)
        return
    }

    val latLon = FloatArray(2)
    if (exif.getLatLong(latLon)) {
        showLocationOnMap("${latLon[0]}, ${latLon[1]}")
    } else {
        toast(R.string.unknown_location)
    }
}

fun Activity.handleExcludedFolderPasswordProtection(callback: () -> Unit) {
    if (config.isExcludedPasswordProtectionOn) {
        SecurityDialog(this, config.excludedPasswordHash, config.excludedProtectionType) { _, _, success ->
            if (success) {
                callback()
            }
        }
    } else {
        callback()
    }
}

fun Activity.openRecycleBin() {
    Intent(this, MediaActivity::class.java).apply {
        putExtra(DIRECTORY, RECYCLE_BIN)
        startActivity(this)
    }
}

fun BaseSimpleActivity.writeBitmapToCache(
    source: Uri,
    bitmap: Bitmap,
    callback: (path: String?) -> Unit
) {
    val bytes = ByteArrayOutputStream()
    bitmap.compress(CompressFormat.PNG, 0, bytes)

    val folder = File(cacheDir, TEMP_FOLDER_NAME)
    if (!folder.exists()) {
        if (!folder.mkdir()) {
            callback(null)
            return
        }
    }

    val filename = applicationContext.getFilenameFromContentUri(source)
        ?: "tmp-${System.currentTimeMillis()}.jpg"
    val newPath = "$folder/$filename"
    val fileDirItem = FileDirItem(newPath, filename)
    getFileOutputStream(fileDirItem, true) {
        if (it != null) {
            try {
                it.write(bytes.toByteArray())
                callback(newPath)
            } catch (_: Exception) {
                callback(null)
            } finally {
                it.close()
            }
        } else {
            callback(null)
        }
    }
}

fun BaseSimpleActivity.ensureWritablePath(
    targetPath: String,
    confirmOverwrite: Boolean = true,
    onCancel: (() -> Unit)? = null,
    callback: (String) -> Unit,
) {
    fun proceedAfterGrants() {
        handleSAFDialogSdk30(targetPath) { granted ->
            if (!granted) {
                onCancel?.invoke()
                return@handleSAFDialogSdk30
            }
            callback(targetPath)
        }
    }

    fun requestGrantsThenProceed() {
        if (isRPlus() && !isExternalStorageManager()) {
            val fileDirItem = arrayListOf(File(targetPath).toFileDirItem(this))
            val fileUris = getFileUrisFromFileDirItems(fileDirItem)
            updateSDK30Uris(fileUris) { success ->
                if (success) proceedAfterGrants() else onCancel?.invoke()
            }
        } else {
            proceedAfterGrants()
        }
    }

    if (confirmOverwrite && getDoesFilePathExist(targetPath)) {
        val title = String.format(
            getString(com.goodwy.commons.R.string.file_already_exists_overwrite),
            targetPath.getFilenameFromPath()
        )
        ConfirmationDialog(this, title) {
            requestGrantsThenProceed()
        }
    } else {
        requestGrantsThenProceed()
    }
}

fun Activity.proposeNewFilePath(uri: Uri): Pair<String, Boolean> {
    var newPath = applicationContext.getRealPathFromURI(uri) ?: ""
    if (newPath.startsWith("/mnt/")) {
        newPath = ""
    }

    var shouldAppendFilename = true
    if (newPath.isEmpty()) {
        val filename = applicationContext.getFilenameFromContentUri(uri) ?: ""
        if (filename.isNotEmpty()) {
            val path = if (intent.extras?.containsKey(REAL_FILE_PATH) == true) {
                intent.getStringExtra(REAL_FILE_PATH)?.getParentPath()
            } else {
                internalStoragePath
            }
            newPath = "$path/$filename"
            shouldAppendFilename = false
        }
    }

    if (newPath.isEmpty()) {
        newPath = "$internalStoragePath/${getCurrentFormattedDateTime()}.${
            uri.toString().getFilenameExtension()
        }"
        shouldAppendFilename = false
    }

    return Pair(newPath, shouldAppendFilename)
}

//Goodwy
fun Activity.newAppRecommendation() {
    if (resources.getBoolean(com.goodwy.commons.R.bool.is_foss)) {
        if (!isNewApp()) {
            if ((0..config.newAppRecommendationDialogCount).random() == 2) {
                val packageName = "yrellag.ywdoog.ved".reversed()
                NewAppDialog(
                    activity = this,
                    packageName = packageName,
                    title = getString(com.goodwy.strings.R.string.notification_of_new_application),
                    text = "AlRight Gallery",
                    drawable = AppCompatResources.getDrawable(this, com.goodwy.commons.R.drawable.ic_gallery_new),
                    showSubtitle = true
                ) {
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// DIAGNOSTICO. Tudo fica so na MEMORIA do app (nada e gravado em disco, nada sai do aparelho):
// registro dos ultimos eventos de atualizacao + uma sonda que compara o MediaStore com o disco
// para um nome de arquivo. Abre em Configuracoes > Diagnostico de midia.
// ---------------------------------------------------------------------------------------------
object Right100Diag {
    private val lock = Any()
    private val lines = java.util.ArrayDeque<Pair<Long, String>>()

    fun add(msg: String) {
        synchronized(lock) {
            lines.addLast(Pair(System.currentTimeMillis(), msg))
            while (lines.size > 400) lines.removeFirst()
        }
    }

    fun snapshot(max: Int): List<String> {
        val df = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        synchronized(lock) {
            return lines.toList().takeLast(max).map { df.format(java.util.Date(it.first)) + " " + it.second }
        }
    }
}

fun android.app.Activity.showTextDialog(title: String, body: String, extraLabel: String?, onExtra: () -> Unit) {
    runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        try {
            val density = resources.displayMetrics.density
            val pad = (12 * density).toInt()
            val tv = android.widget.TextView(this).apply {
                text = body
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(pad, pad, pad, pad)
            }
            val scroll = android.widget.ScrollView(this)
            scroll.addView(tv)
            val builder = android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Fechar", null)
                .setNeutralButton("Copiar") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostico", body))
                    android.widget.Toast.makeText(this, "Texto copiado", android.widget.Toast.LENGTH_SHORT).show()
                }
            if (extraLabel != null) {
                builder.setNegativeButton(extraLabel) { _, _ -> onExtra() }
            }
            builder.show()
        } catch (_: Throwable) {
        }
    }
}

private fun android.app.Activity.buildDiagHeader(): String {
    val sb = StringBuilder()
    sb.append("Versao: ").append(com.goodwy.gallery.BuildConfig.VERSION_NAME).append("\n")
    sb.append("Android API: ").append(android.os.Build.VERSION.SDK_INT).append("\n")
    val manager = if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager().toString() else "n/a"
    sb.append("Acesso total a arquivos: ").append(manager).append("\n")
    sb.append("Listagem MediaStore: ").append(applicationContext.config.mediaStoreListing).append("\n")
    sb.append("Filtro de midia: ").append(applicationContext.config.filterMedia).append("\n")
    sb.append("Mostrar ocultas: ").append(applicationContext.config.shouldShowHidden).append("\n")
    sb.append("Subpastas agrupadas: ").append(applicationContext.config.groupDirectSubfolders)
    return sb.toString()
}

fun android.app.Activity.showDiagnostics() {
    val body = buildDiagHeader() + "\n\n--- eventos recentes (o mais novo fica embaixo) ---\n" +
        Right100Diag.snapshot(150).joinToString("\n")
    showTextDialog("Diagnostico de midia", body, "Procurar arquivo") { askDiagProbeName() }
}

private fun android.app.Activity.askDiagProbeName() {
    runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        val input = android.widget.EditText(this)
        input.hint = "parte do nome do arquivo"
        android.app.AlertDialog.Builder(this)
            .setTitle("Procurar arquivo")
            .setView(input)
            .setPositiveButton("Procurar") { _, _ -> runDiagProbe(input.text.toString().trim()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

// Compara, para um nome, o que o MediaStore sabe com o que existe de fato no disco.
private fun android.app.Activity.runDiagProbe(text: String) {
    if (text.length < 2) return
    ensureBackgroundThread {
        val sb = StringBuilder()
        sb.append("=== PROCURA: \"").append(text).append("\" ===\n")
        val folders = LinkedHashSet<String>()
        try {
            sb.append("\n[MediaStore] linhas com esse nome (sem filtro de tipo):\n")
            val projection = arrayListOf("_id", "_data", "media_type", "mime_type", "_size")
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                projection.add("is_pending")
                projection.add("is_trashed")
            }
            var shown = 0
            contentResolver.query(
                android.provider.MediaStore.Files.getContentUri("external"),
                projection.toTypedArray(), "_display_name LIKE ?", arrayOf("%$text%"), "date_modified DESC"
            )?.use { c ->
                while (c.moveToNext() && shown < 40) {
                    val path = c.getString(1) ?: ""
                    val f = File(path)
                    val pend = if (c.columnCount > 5) c.getInt(5).toString() else "?"
                    val trash = if (c.columnCount > 6) c.getInt(6).toString() else "?"
                    sb.append("id=").append(c.getLong(0)).append(" tipo=").append(c.getInt(2))
                        .append(" mime=").append(c.getString(3)).append(" tam=").append(c.getLong(4))
                        .append(" pendente=").append(pend).append(" lixeira=").append(trash).append("\n")
                        .append("  ").append(path).append("\n")
                        .append("  existe no disco: ").append(f.exists()).append(" (").append(f.length()).append(" bytes)\n")
                    f.parent?.let { folders.add(it) }
                    shown++
                }
            }
            if (shown == 0) sb.append("(nenhuma linha)\n")
        } catch (e: Throwable) {
            sb.append("erro na consulta: ").append(e).append("\n")
        }

        try {
            sb.append("\n[Disco] arquivos com esse nome (ate 7 niveis, 15 s):\n")
            val deadline = System.currentTimeMillis() + 15000L
            var found = 0
            var timedOut = false
            val walk = android.os.Environment.getExternalStorageDirectory().walkTopDown().maxDepth(7)
                .onEnter { dir -> !dir.name.equals("Android", true) }
            for (f in walk) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    break
                }
                if (f.isFile && f.name.contains(text, true)) {
                    sb.append(f.path).append(" (").append(f.length()).append(" bytes)\n")
                    f.parent?.let { folders.add(it) }
                    found++
                    if (found >= 40) break
                }
            }
            if (found == 0) sb.append("(nenhum)\n")
            if (timedOut) sb.append("(busca interrompida por tempo)\n")
        } catch (e: Throwable) {
            sb.append("erro na busca no disco: ").append(e).append("\n")
        }

        sb.append("\n[Pastas envolvidas]\n")
        for (folder in folders.take(8)) {
            var storeCount = -1
            try {
                contentResolver.query(
                    android.provider.MediaStore.Files.getContentUri("external"), arrayOf("_id"),
                    "_data LIKE ? AND _data NOT LIKE ? AND (media_type IN (1,3) OR mime_type LIKE 'image/%' OR mime_type LIKE 'video/%')",
                    arrayOf("$folder/%", "$folder/%/%"), null
                )?.use { storeCount = it.count }
            } catch (_: Throwable) {
            }
            val diskCount = try {
                File(folder).list()?.size ?: -1
            } catch (_: Throwable) {
                -1
            }
            val noMedia = File(folder, ".nomedia").exists()
            sb.append(folder).append("\n  MediaStore(imagens+videos)=").append(storeCount)
                .append("  disco(itens)=").append(diskCount).append("  .nomedia=").append(noMedia).append("\n")
        }

        try {
            val viaApp = com.goodwy.gallery.helpers.MediaFetcher(applicationContext).getAndroid11FolderMedia(
                isPickImage = false, isPickVideo = false, favoritePaths = getFavoritePaths(),
                getFavoritePathsOnly = false, getProperDateTaken = false, dateTakens = HashMap(), nameQuery = text
            ).values.flatten().filter { it.name.contains(text, true) }
            sb.append("\n[Pesquisa do app] resultados para esse nome: ").append(viaApp.size).append("\n")
            viaApp.take(15).forEach { sb.append("  ").append(it.path).append("\n") }
        } catch (e: Throwable) {
            sb.append("\n[Pesquisa do app] erro: ").append(e).append("\n")
        }

        Right100Diag.add("sonda '" + text + "' concluida")
        showTextDialog("Resultado da procura", sb.toString(), null) { }
    }
}


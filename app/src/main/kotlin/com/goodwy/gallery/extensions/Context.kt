package com.goodwy.gallery.extensions

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import java.util.concurrent.Executors
import android.os.Process
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.widget.ImageView
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.integration.webp.WebpBitmapFactory
import com.bumptech.glide.integration.webp.decoder.WebpDownsampler
import com.bumptech.glide.integration.webp.decoder.WebpDrawable
import com.bumptech.glide.integration.webp.decoder.WebpDrawableTransformation
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.views.MySquareImageView
import com.goodwy.gallery.R
import com.goodwy.gallery.asynctasks.GetMediaAsynctask
import com.goodwy.gallery.databases.GalleryDatabase
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.interfaces.*
import com.goodwy.gallery.models.*
import com.goodwy.gallery.svg.SvgSoftwareLayerSetter
import com.squareup.picasso.Callback as PicassoCallback
import com.squareup.picasso.Picasso
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Cache de noMediaFolders — TTL de 60s para não repetir query cara ao MediaStore
private var noMediaFoldersCache: ArrayList<String>? = null
private var noMediaFoldersCacheTime: Long = 0L
private const val NO_MEDIA_CACHE_TTL = 60_000L

// Chamado ao criar/apagar um .nomedia: o "(oculto)" no nome nao pode esperar o cache vencer.
fun invalidateNoMediaFoldersCache() {
    noMediaFoldersCache = null
    noMediaFoldersCacheTime = 0L
}

val Context.audioManager get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

fun Context.getHumanizedFilename(path: String): String {
    val humanized = humanizePath(path)
    return humanized.substring(humanized.lastIndexOf("/") + 1)
}

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.widgetsDB: WidgetsDao
    get() = GalleryDatabase.getInstance(applicationContext).WidgetsDao()

val Context.mediaDB: MediumDao get() = GalleryDatabase.getInstance(applicationContext).MediumDao()

val Context.directoryDB: DirectoryDao
    get() = GalleryDatabase.getInstance(applicationContext).DirectoryDao()

val Context.favoritesDB: FavoritesDao
    get() = GalleryDatabase.getInstance(applicationContext).FavoritesDao()

val Context.dateTakensDB: DateTakensDao
    get() = GalleryDatabase.getInstance(applicationContext).DateTakensDao()

val Context.recycleBin: File get() = filesDir

/**
 * Onde o arquivo fica de verdade quando vai para a lixeira.
 *
 * Antes: a lixeira era filesDir = /data/user/0/<pacote>/files, e a midia mora em
 * /storage/emulated/0. Sao pontos de montagem DIFERENTES: renameTo entre eles falha sempre com
 * EXDEV, entao moveIntoRecycleBin caia no plano B e copiava o arquivo inteiro. Excluir um video
 * de 1 GB = copiar 1 GB; restaurar = copiar 1 GB de volta. Era essa a demora.
 *
 * Agora: getExternalFilesDir()/.trash, no MESMO volume da midia
 * (/storage/emulated/0/Android/data/<pacote>/files/.trash), entao renameTo e instantaneo nos dois
 * sentidos. Continua privada do app: nao aparece na galeria, outro app nao le e some ao
 * desinstalar - o mesmo comportamento de antes.
 *
 * Itens que ja estavam na lixeira antiga continuam funcionando: toda leitura passa por
 * resolveTrashedFile(), que procura no lugar novo e, se nao achar, na lixeira antiga.
 */
object TrashStore {
    private const val DIR_NAME = ".trash"

    /** Arquivo de indice: uma linha por item, "nome-fisico<TAB>caminho-original". */
    const val FLAT_INDEX_NAME = ".right100_index"
    private val indexLock = Any()

    /**
     * Indice da lixeira. Com a lixeira plana (todos os arquivos soltos numa pasta so), o nome do
     * arquivo nao diz de onde ele veio - o indice diz. O caminho original continua tambem na
     * chave do banco (RECYCLE_BIN + caminho), entao o restaurar nao depende do layout fisico.
     */
    fun readFlatIndex(context: Context): HashMap<String, String> {
        val map = HashMap<String, String>()
        arrayListOf(publicRoot(context), root(context)).forEach { raiz ->
            val f = File(raiz, FLAT_INDEX_NAME)
            if (!f.isFile) return@forEach
            try {
                f.forEachLine { linha ->
                    val tab = linha.indexOf('\t')
                    if (tab > 0) map[linha.substring(0, tab)] = linha.substring(tab + 1)
                }
            } catch (_: Exception) {
            }
        }
        return map
    }

    /** Nome livre dentro da raiz: "video.mp4", depois "video~1.mp4", "video~2.mp4"... */
    fun flatNameFor(context: Context, raiz: File, source: File): String {
        val usados = readFlatIndex(context).keys
        var nome = source.name
        if (!File(raiz, nome).exists() && !usados.contains(nome)) return nome
        val base = if (nome.contains('.')) nome.substringBeforeLast('.') else nome
        val ext = if (nome.contains('.')) "." + nome.substringAfterLast('.') else ""
        var n = 1
        while (true) {
            nome = "$base~$n$ext"
            if (!File(raiz, nome).exists() && !usados.contains(nome)) return nome
            n++
        }
    }

    fun rememberFlat(context: Context, raiz: File, physicalName: String, originalPath: String) {
        synchronized(indexLock) {
            try {
                raiz.mkdirs()
                File(raiz, FLAT_INDEX_NAME).appendText("$physicalName\t$originalPath\n")
            } catch (_: Exception) {
            }
        }
    }

    /** Caminho original de um item plano (null se o arquivo nao esta solto na raiz da lixeira). */
    fun originalPathOfFlat(context: Context, physical: File): String? {
        val pai = physical.parentFile?.absolutePath ?: return null
        val naRaiz = arrayListOf(publicRoot(context), root(context)).any { it.absolutePath == pai }
        if (!naRaiz) return null
        return readFlatIndex(context)[physical.name]
    }

    fun legacyRoot(context: Context): File = context.filesDir

    /** Nome da pasta oculta da lixeira na raiz publica de cada volume. */
    const val PUBLIC_DIR_NAME = ".right100_trash"

    /**
     * Pasta oculta na raiz publica do armazenamento interno. Fica na MESMA montagem da midia -
     * ao contrario de Android/data/<pacote>, que o Android expoe como montagem separada e onde
     * renameTo acaba sendo copia (medido no aparelho: 9889 ms para um video de 297 MB).
     */
    fun publicRoot(context: Context): File =
        File(android.os.Environment.getExternalStorageDirectory(), PUBLIC_DIR_NAME)

    /** Volume do caminho: /storage/<x>; null para o armazenamento interno emulado. */
    private fun sdVolumeOf(path: String): String? {
        if (!path.startsWith("/storage/")) return null
        val seg = path.removePrefix("/storage/").substringBefore('/')
        return if (seg.isEmpty() || seg == "emulated" || seg == "self") null else seg
    }

    /** Raiz publica no MESMO volume do arquivo (cartao SD ganha a sua propria). */
    fun publicRootFor(context: Context, sourcePath: String): File {
        val vol = sdVolumeOf(sourcePath) ?: return publicRoot(context)
        return File("/storage/$vol", PUBLIC_DIR_NAME)
    }

    /** Todas as raizes publicas plausiveis para uma chave relativa. */
    fun publicRootsFor(context: Context, relativePath: String): List<File> {
        val list = arrayListOf(publicRoot(context))
        sdVolumeOf("/" + relativePath.trimStart('/'))?.let { list.add(File("/storage/$it", PUBLIC_DIR_NAME)) }
        return list
    }

    /** Caminho fisico de um item da lixeira -> chave do banco (.recycle_bin/<caminho original>). */
    fun toTrashKey(context: Context, path: String): String {
        val novo = root(context).absolutePath
        if (path.startsWith("$novo/")) return RECYCLE_BIN + path.removePrefix(novo)
        val antigo = legacyRoot(context).absolutePath
        if (path.startsWith("$antigo/")) return RECYCLE_BIN + path.removePrefix(antigo)
        val marker = "/$PUBLIC_DIR_NAME"
        val idx = path.indexOf(marker)
        if (idx >= 0) {
            val depois = path.substring(idx + marker.length).trimStart('/')
            // Item plano: o nome fisico nao carrega a origem, o indice carrega.
            if (!depois.contains('/')) {
                originalPathOfFlat(context, File(path))?.let { return RECYCLE_BIN + it }
            }
            return RECYCLE_BIN + path.substring(idx + marker.length)
        }
        return path
    }

    // getExternalFilesDir() consulta o servico de armazenamento a cada chamada e pode levar
    // segundos com o disco ocupado (a varredura estava martelando o aparelho). O caminho nao
    // muda durante a vida do processo.
    @Volatile
    private var cachedRoot: File? = null

    fun root(context: Context): File {
        cachedRoot?.let { return it }
        val base = context.getExternalFilesDir(null) ?: return File(context.filesDir, DIR_NAME)
        return File(base, DIR_NAME).also { cachedRoot = it }
    }

    fun resolveTrashedFile(context: Context, relativePath: String): File {
        val rel = relativePath.trimStart('/')
        val novo = File(root(context), rel)
        if (novo.exists()) return novo
        publicRootsFor(context, rel).forEach { raiz ->
            val publico = File(raiz, rel)
            if (publico.exists()) return publico
        }
        // Item plano: procura no indice qual nome fisico corresponde a este caminho original.
        val alvo = "/" + rel
        readFlatIndex(context).forEach { (nome, original) ->
            if (original == alvo || original == rel) {
                publicRootsFor(context, rel).forEach { raiz ->
                    val f = File(raiz, nome)
                    if (f.exists()) return f
                }
                val f = File(root(context), nome)
                if (f.exists()) return f
            }
        }
        val antigo = File(legacyRoot(context), rel)
        return if (antigo.exists()) antigo else novo
    }

    fun isTrashedFile(context: Context, path: String): Boolean {
        val novo = root(context).absolutePath.trimEnd('/')
        val antigo = legacyRoot(context).absolutePath.trimEnd('/')
        // Qualquer pasta oculta de lixeira publica, em qualquer volume.
        val naPublica = path.contains("/$PUBLIC_DIR_NAME/")
        return path.startsWith("$novo/") || naPublica || path.startsWith("$antigo/")
    }

    /**
     * true para caminho que esta DENTRO de uma pasta de lixeira. A lixeira publica fica na raiz do
     * armazenamento (/storage/emulated/0/.right100_trash), entao a descoberta de pastas a tratava
     * como pasta comum de midia e ela aparecia na galeria junto com o "recycle_bin" - o usuario
     * via duas lixeiras. Nada ali e midia do usuario: e o deposito da propria lixeira.
     */
    fun isTrashFolderPath(path: String): Boolean {
        val p = path.replace('\\', '/')
        return p.contains("/$PUBLIC_DIR_NAME") || p.contains("/.recycle_bin")
    }
}

val Context.trashRootPath: String get() = TrashStore.root(this).absolutePath

// Verdadeiro se o caminho esta na lixeira em QUALQUER uma das raizes: a nova
// (Android/data/<pacote>/files/.trash), a oculta publica (.right100_trash) ou a antiga
// (/data/user/0/<pacote>/files), onde estao os itens excluidos antes desta correcao.
fun Context.isInTrashPath(path: String): Boolean = TrashStore.isTrashedFile(this, path)

fun Context.movePinnedDirectoriesToFront(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val foundFolders = ArrayList<Directory>()
    val pinnedFolders = config.pinnedFolders

    dirs.forEach {
        if (pinnedFolders.contains(it.path)) {
            foundFolders.add(it)
        }
    }

    dirs.removeAll(foundFolders)
    dirs.addAll(0, foundFolders)
    if (config.tempFolderPath.isNotEmpty()) {
        val newFolder = dirs.firstOrNull { it.path == config.tempFolderPath }
        if (newFolder != null) {
            dirs.remove(newFolder)
            dirs.add(0, newFolder)
        }
    }

    if (config.showRecycleBinAtFolders && config.showRecycleBinLast) {
        val binIndex = dirs.indexOfFirst { it.isRecycleBin() }
        if (binIndex != -1) {
            val bin = dirs.removeAt(binIndex)
            dirs.add(bin)
        }
    }
    return dirs
}

@Suppress("UNCHECKED_CAST")
fun Context.getSortedDirectories(source: ArrayList<Directory>): ArrayList<Directory> {
    val sorting = config.directorySorting
    val dirs = source.clone() as ArrayList<Directory>

    if (sorting and SORT_BY_RANDOM != 0) {
        dirs.shuffle()
        return movePinnedDirectoriesToFront(dirs)
    } else if (sorting and SORT_BY_CUSTOM != 0) {
        val newDirsOrdered = ArrayList<Directory>()
        config.customFoldersOrder.split("|||").forEach { path ->
            val index = dirs.indexOfFirst { it.path == path }
            if (index != -1) {
                val dir = dirs.removeAt(index)
                newDirsOrdered.add(dir)
            }
        }

        dirs.mapTo(newDirsOrdered, { it })
        return newDirsOrdered
    }

    dirs.sortWith { o1, o2 ->
        o1 as Directory
        o2 as Directory

        var result = when {
            sorting and SORT_BY_NAME != 0 -> {
                if (o1.sortValue.isEmpty()) {
                    o1.sortValue = o1.name.lowercase(Locale.getDefault())
                }

                if (o2.sortValue.isEmpty()) {
                    o2.sortValue = o2.name.lowercase(Locale.getDefault())
                }

                if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                    AlphanumericComparator().compare(
                        string1 = o1.sortValue.normalizeString().lowercase(Locale.getDefault()),
                        string2 = o2.sortValue.normalizeString().lowercase(Locale.getDefault())
                    )
                } else {
                    o1.sortValue.normalizeString().lowercase(Locale.getDefault())
                        .compareTo(o2.sortValue.normalizeString().lowercase(Locale.getDefault()))
                }
            }

            sorting and SORT_BY_PATH != 0 -> {
                if (o1.sortValue.isEmpty()) {
                    o1.sortValue = o1.path.lowercase(Locale.getDefault())
                }

                if (o2.sortValue.isEmpty()) {
                    o2.sortValue = o2.path.lowercase(Locale.getDefault())
                }

                if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                    AlphanumericComparator().compare(
                        string1 = o1.sortValue.lowercase(Locale.getDefault()),
                        string2 = o2.sortValue.lowercase(Locale.getDefault())
                    )
                } else {
                    o1.sortValue.lowercase(Locale.getDefault())
                        .compareTo(o2.sortValue.lowercase(Locale.getDefault()))
                }
            }

            // SORT_BY_SIZE, SORT_BY_COUNT, SORT_BY_DATE_MODIFIED are numerical
            else -> (o1.sortValue.toLongOrNull() ?: 0).compareTo(o2.sortValue.toLongOrNull() ?: 0)
        }

        if (sorting and SORT_DESCENDING != 0) {
            result *= -1
        }
        result
    }

    return movePinnedDirectoriesToFront(dirs)
}

fun Context.getDirsToShow(
    dirs: ArrayList<Directory>,
    allDirs: ArrayList<Directory>,
    currentPathPrefix: String
): ArrayList<Directory> {
    return if (config.groupDirectSubfolders) {
        dirs.forEach {
            it.subfoldersCount = 0
            it.subfoldersMediaCount = it.mediaCnt
        }

        val filledDirs = fillWithSharedDirectParents(dirs)
        val parentDirs = getDirectParentSubfolders(filledDirs, currentPathPrefix)
        updateSubfolderCounts(filledDirs, parentDirs)

        // show the current folder as an available option too, not just subfolders
        if (currentPathPrefix.isNotEmpty()) {
            val currentFolder = allDirs.firstOrNull {
                parentDirs.firstOrNull {
                    it.path.equals(currentPathPrefix, true)
                } == null && it.path.equals(currentPathPrefix, true)
            }

            currentFolder?.apply {
                subfoldersCount = 1
                parentDirs.add(this)
            }
        }

        getSortedDirectories(parentDirs)
    } else {
        dirs.forEach { it.subfoldersMediaCount = it.mediaCnt }
        dirs
    }
}

private fun Context.addParentWithoutMediaFiles(into: ArrayList<Directory>, path: String): Boolean {
    val isSortingAscending = config.sorting.isSortingAscending()
    val subDirs = into.filter { File(it.path).parent.equals(path, true) } as ArrayList<Directory>
    val newDirId = max(1000L, into.maxOf { it.id ?: 0L })
    if (subDirs.isNotEmpty()) {
        val lastModified = if (isSortingAscending) {
            subDirs.minByOrNull { it.modified }?.modified
        } else {
            subDirs.maxByOrNull { it.modified }?.modified
        } ?: 0

        val dateTaken = if (isSortingAscending) {
            subDirs.minByOrNull { it.taken }?.taken
        } else {
            subDirs.maxByOrNull { it.taken }?.taken
        } ?: 0

        var mediaTypes = 0
        subDirs.forEach {
            mediaTypes = mediaTypes or it.types
        }

        val directory = Directory(
            id = newDirId + 1,
            path = path,
            tmb = subDirs.first().tmb,
            name = getFolderNameFromPath(path),
            mediaCnt = subDirs.sumOf { it.mediaCnt },
            modified = lastModified,
            taken = dateTaken,
            size = subDirs.sumByLong { it.size },
            location = getPathLocation(path),
            types = mediaTypes,
            sortValue = ""
        )

        directory.containsMediaFilesDirectly = false
        into.add(directory)
        return true
    }
    return false
}

fun Context.fillWithSharedDirectParents(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val allDirs = ArrayList<Directory>(dirs)
    val childCounts = mutableMapOf<String, Int>()
    for (dir in dirs) {
        File(dir.path).parent?.let {
            val current = childCounts[it] ?: 0
            childCounts.put(it, current + 1)
        }
    }

    childCounts
        .filter { dir -> dir.value > 1 && dirs.none { it.path.equals(dir.key, true) } }
        .toList()
        .sortedByDescending { it.first.length }
        .forEach { (parent, _) ->
            addParentWithoutMediaFiles(allDirs, parent)
        }
    return allDirs
}

fun Context.getDirectParentSubfolders(
    dirs: ArrayList<Directory>,
    currentPathPrefix: String
): ArrayList<Directory> {
    val folders = dirs.map { it.path }.sorted().toMutableSet() as HashSet<String>
    val currentPaths = LinkedHashSet<String>()
    val foldersWithoutMediaFiles = ArrayList<String>()

    for (path in folders) {
        if (path == RECYCLE_BIN || path == FAVORITES) {
            continue
        }

        if (currentPathPrefix.isNotEmpty()) {
            if (!path.startsWith(currentPathPrefix, true)) {
                continue
            }

            if (!File(path).parent.equals(currentPathPrefix, true)) {
                continue
            }
        }

        if (
            currentPathPrefix.isNotEmpty() &&
            path.equals(currentPathPrefix, true)
            || File(path).parent.equals(currentPathPrefix, true)
        ) {
            currentPaths.add(path)
        } else if (
            folders.any {
                !it.equals(path, true) && (File(path).parent.equals(it, true)
                    || File(it).parent.equals(File(path).parent, true))
            }
        ) {
            // if we have folders like
            // /storage/emulated/0/Pictures/Images and
            // /storage/emulated/0/Pictures/Screenshots,
            // but /storage/emulated/0/Pictures is empty, still Pictures with the first folders thumbnails and proper other info
            val parent = File(path).parent
            if (
                parent != null
                && !folders.contains(parent)
                && dirs.none { it.path.equals(parent, true) }
            ) {
                currentPaths.add(parent)
                if (addParentWithoutMediaFiles(dirs, parent)) {
                    foldersWithoutMediaFiles.add(parent)
                }
            }
        } else {
            currentPaths.add(path)
        }
    }

    var areDirectSubfoldersAvailable = false
    currentPaths.forEach {
        val path = it
        currentPaths.forEach {
            if (
                !foldersWithoutMediaFiles.contains(it)
                && !it.equals(path, true)
                && File(it).parent?.equals(path, true) == true
            ) {
                areDirectSubfoldersAvailable = true
            }
        }
    }

    if (currentPathPrefix.isEmpty() && folders.contains(RECYCLE_BIN)) {
        currentPaths.add(RECYCLE_BIN)
    }

    if (currentPathPrefix.isEmpty() && folders.contains(FAVORITES)) {
        currentPaths.add(FAVORITES)
    }

    if (folders.size == currentPaths.size) {
        return dirs.filter { currentPaths.contains(it.path) } as ArrayList<Directory>
    }

    folders.clear()
    folders.addAll(currentPaths)

    val dirsToShow = dirs.filter { folders.contains(it.path) } as ArrayList<Directory>
    return if (areDirectSubfoldersAvailable) {
        getDirectParentSubfolders(dirsToShow, currentPathPrefix)
    } else {
        dirsToShow
    }
}

fun Context.updateSubfolderCounts(
    children: ArrayList<Directory>,
    parentDirs: ArrayList<Directory>
) {
    for (child in children) {
        var longestSharedPath = ""
        for (parentDir in parentDirs) {
            if (parentDir.path == child.path) {
                longestSharedPath = child.path
                continue
            }

            if (
                child.path.startsWith(parentDir.path, true)
                && parentDir.path.length > longestSharedPath.length
            ) {
                longestSharedPath = parentDir.path
            }
        }

        // make sure we count only the proper direct subfolders, grouped the same way as on the main screen
        parentDirs.firstOrNull { it.path == longestSharedPath }?.apply {
            if (
                path.equals(child.path, true)
                || path.equals(File(child.path).parent, true)
                || children.any { it.path.equals(File(child.path).parent, true) }
            ) {
                if (child.containsMediaFilesDirectly) {
                    subfoldersCount++
                }

                if (path != child.path) {
                    subfoldersMediaCount += child.mediaCnt
                }
            }
        }
    }
}

fun Context.getNoMediaFolders(callback: (folders: ArrayList<String>) -> Unit) {
    ensureBackgroundThread {
        callback(getNoMediaFoldersSync())
    }
}

fun Context.getNoMediaFoldersSync(): ArrayList<String> {
    val now = System.currentTimeMillis()
    val cached = noMediaFoldersCache
    if (cached != null && (now - noMediaFoldersCacheTime) < NO_MEDIA_CACHE_TTL) {
        return cached
    }

    val folders = ArrayList<String>()
    val uri = Files.getContentUri("external")
    val projection = arrayOf(Files.FileColumns.DATA)
    val selection = "${Files.FileColumns.MEDIA_TYPE} = ? AND ${Files.FileColumns.TITLE} LIKE ?"
    val selectionArgs = arrayOf(Files.FileColumns.MEDIA_TYPE_NONE.toString(), "%$NOMEDIA%")
    val sortOrder = "${Files.FileColumns.DATE_MODIFIED} DESC"
    val OTGPath = config.OTGPath

    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        if (cursor?.moveToFirst() == true) {
            do {
                val path = cursor.getStringValue(Files.FileColumns.DATA) ?: continue
                val noMediaFile = File(path)
                if (
                    getDoesFilePathExist(noMediaFile.absolutePath, OTGPath)
                    && noMediaFile.name == NOMEDIA
                ) {
                    folders.add(noMediaFile.parent)
                }
            } while (cursor.moveToNext())
        }
    } catch (ignored: Exception) {
    } finally {
        cursor?.close()
    }

    noMediaFoldersCache = folders
    noMediaFoldersCacheTime = now
    return folders
}

fun Context.rescanFolderMedia(path: String, callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        rescanFolderMediaSync(path, callback)
    }
}

fun Context.rescanFolderMediaSync(path: String, callback: (() -> Unit)? = null) {
    getCachedMedia(path) { cached ->
        GetMediaAsynctask(
            context = applicationContext,
            mPath = path,
            isPickImage = false,
            isPickVideo = false,
            showAll = false
        ) { newMedia ->
            ensureBackgroundThread {
                val media = newMedia.filterIsInstance<Medium>() as ArrayList<Medium>
                try {
                    mediaDB.insertAll(media)

                    cached.forEach { thumbnailItem ->
                        if (!newMedia.contains(thumbnailItem)) {
                            val mediumPath = (thumbnailItem as? Medium)?.path
                            if (mediumPath != null) {
                                deleteDBPath(mediumPath)
                            }
                        }
                    }
                } catch (ignored: Exception) {
                }
                // O snapshot em disco (usado pelo caminho rápido ao abrir uma pasta) é
                // um cache separado do Room - só atualizar mediaDB aqui deixava o
                // snapshot da pasta de destino desatualizado depois de copiar/mover um
                // arquivo pra ela, então o próximo caminho rápido continuava mostrando
                // a lista antiga (sem o item recém-movido) até uma revarredura
                // completa por outro motivo corrigir por acidente.
                try {
                    applicationContext.saveMediaSnapshot(path, newMedia)
                } catch (ignored: Exception) {
                }

                callback?.invoke()

                // A linha da pasta na tabela directories (media_count) so era atualizada pela varredura
                // completa da tela principal; depois de copiar/mover ela mostrava a contagem antiga
                // ate essa varredura terminar. Atualiza a contagem junto com o resto, na origem e no destino.
                try {
                    if (path.isNotEmpty() && path != FAVORITES && path != RECYCLE_BIN) {
                        // Gravava so a contagem: o tamanho da pasta ficava com o valor da
                        // ultima varredura completa (ou zero), sumindo depois de copiar/mover.
                        val totalSize = newMedia.filterIsInstance<Medium>().sumOf { it.size }
                        directoryDB.updateDirectoryMediaCountAndSize(
                            path,
                            newMedia.count { it is Medium },
                            totalSize
                        )
                    }
                } catch (ignored: Exception) {
                }
                com.goodwy.gallery.App.logGesture("rescanFolderMediaSync path=$path items=${newMedia.size}")
            }
        }.execute()
    }
}

fun Context.storeDirectoryItems(items: ArrayList<Directory>) {
    ensureBackgroundThread {
        directoryDB.insertAll(items)
    }
}

fun Context.checkAppendingHidden(
    path: String,
    hidden: String,
    includedFolders: MutableSet<String>,
    noMediaFolders: ArrayList<String>
): String {
    val dirName = getFolderNameFromPath(path)
    val folderNoMediaStatuses = HashMap<String, Boolean>()
    noMediaFolders.forEach { folder ->
        folderNoMediaStatuses["$folder/$NOMEDIA"] = true
    }

    return if (
        (path.contains("/.") || path.doesThisOrParentHaveNoMedia(folderNoMediaStatuses, null))
        && !path.isThisOrParentIncluded(includedFolders)
    ) {
        "$dirName $hidden"
    } else {
        dirName
    }
}

fun Context.getFolderNameFromPath(path: String): String {
    return when (path) {
        internalStoragePath -> getString(com.goodwy.commons.R.string.internal)
        sdCardPath -> getString(com.goodwy.commons.R.string.sd_card)
        otgPath -> getString(com.goodwy.commons.R.string.usb)
        FAVORITES -> getString(com.goodwy.commons.R.string.favorites)
        RECYCLE_BIN -> getString(com.goodwy.commons.R.string.recycle_bin)
        else -> path.getFilenameFromPath()
    }
}

fun Context.loadImage(
    type: Int,
    path: String,
    target: MySquareImageView,
    horizontalScroll: Boolean,
    animateGifs: Boolean,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: ObjectKey,
    skipMemoryCacheAtPaths: Set<String>? = null,
    // Nº de colunas do grid que está pedindo o thumbnail (capas de pasta: config.dirColumnCnt;
    // grid de mídia: config.mediaColumnCnt). Usado pra decodificar no tamanho real da célula
    // em vez de um valor fixo — ver loadImageBase().
    columnCount: Int = 3,
    fallbackPath: String? = null,
    onError: (() -> Unit)? = null
) {
    target.isHorizontalScrolling = horizontalScroll
    if (type == TYPE_SVGS) {
        loadSVG(
            path = path,
            target = target,
            cropThumbnails = cropThumbnails,
            roundCorners = roundCorners,
            signature = signature
        )
    } else {
        loadImageBase(
            path = path,
            target = target,
            cropThumbnails = cropThumbnails,
            roundCorners = roundCorners,
            signature = signature,
            skipMemoryCacheAtPaths = skipMemoryCacheAtPaths,
            animate = animateGifs,
            isVideo = type == TYPE_VIDEOS,
            // O fallback também cobre JPEG/WEBP locais corrompidos no cache do Glide;
            // content:// permanece no Glide porque o Picasso antigo aqui usa file://.
            tryLoadingWithPicasso = (type == TYPE_IMAGES || type == TYPE_GIFS) && !path.startsWith("content://"),
            isGif = type == TYPE_GIFS,
            columnCount = columnCount,
            fallbackPath = fallbackPath,
            onError = onError
        )
    }
}

fun Context.addTempFolderIfNeeded(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val tempFolderPath = config.tempFolderPath
    return if (tempFolderPath.isNotEmpty()) {
        val directories = ArrayList<Directory>()
        val newFolder = Directory(
            id = null,
            path = tempFolderPath,
            tmb = "",
            name = tempFolderPath.getFilenameFromPath(),
            mediaCnt = 0,
            modified = 0,
            taken = 0,
            size = 0L,
            location = getPathLocation(tempFolderPath),
            types = 0,
            sortValue = ""
        )
        directories.add(newFolder)
        directories.addAll(dirs)
        directories
    } else {
        dirs
    }
}

fun Context.getPathLocation(path: String): Int {
    return when {
        isPathOnSD(path) -> LOCATION_SD
        isPathOnOTG(path) -> LOCATION_OTG
        else -> LOCATION_INTERNAL
    }
}

// Um retry/fallback tardio (postado com delay) pode disparar depois que a
// Activity já foi destruída (usuário saiu da tela) - Glide recusa iniciar um
// load nesse caso com "You cannot start a load for a destroyed activity". O
// guard existente (target.tag != requestToken) protege contra a VIEW ser
// reciclada, mas não contra a Activity inteira ter sido destruída.
private fun Context.isDestroyedActivity(): Boolean {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) {
            return ctx.isDestroyed || ctx.isFinishing
        }
        ctx = ctx.baseContext
    }
    return (ctx as? Activity)?.let { it.isDestroyed || it.isFinishing } ?: false
}

@SuppressLint("CheckResult")
fun Context.loadImageBase(
    path: String,
    target: MySquareImageView,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: ObjectKey,
    skipMemoryCacheAtPaths: Set<String>? = null,
    animate: Boolean = false,
    isVideo: Boolean = false,
    tryLoadingWithPicasso: Boolean = false,
    isGif: Boolean = false,
    // Crossfade em cada reciclagem cria trabalho extra no compositor durante o scroll.
    // A grade prioriza pintura direta; a qualidade vem do tamanho de decode correto.
    crossFadeDuration: Int = 0,
    columnCount: Int = 3,
    fallbackPath: String? = null,
    allowMediaStoreFallback: Boolean = true,
    onError: (() -> Unit)? = null,
    retryCount: Int = 0
) {
    val requestToken = target.tag
    // MySquareImageView define apenas a área quadrada da célula; ele não deve esticar
    // o bitmap para preencher essa área. A escala do conteúdo precisa continuar
    // preservando a proporção original quando o recorte estiver desativado.
    target.scaleType = if (cropThumbnails) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
    val shouldAnimate = animate && roundCorners == ROUNDED_CORNERS_NONE && (isGif || path.isGif() || path.isWebP())
    if (shouldAnimate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        loadAnimatedWithImageDecoder(
            path = path,
            target = target,
            cropThumbnails = cropThumbnails,
            columnCount = columnCount,
            onFallback = {
                if (fallbackPath != null && fallbackPath != path) {
                    loadImageBase(
                        path = fallbackPath,
                        target = target,
                        cropThumbnails = cropThumbnails,
                        roundCorners = roundCorners,
                        signature = ObjectKey("$signature-fallback"),
                        skipMemoryCacheAtPaths = skipMemoryCacheAtPaths,
                        animate = animate,
                        isVideo = isVideo,
                        tryLoadingWithPicasso = tryLoadingWithPicasso,
                        isGif = isGif,
                        crossFadeDuration = crossFadeDuration,
                        columnCount = columnCount,
                        onError = onError
                    )
                } else if (!path.startsWith("content://")) {
                    tryLoadingWithPicasso(path, target, cropThumbnails, roundCorners, signature, onError)
                } else {
                    onError?.invoke()
                }
            }
        )
        return
    }

    val options = RequestOptions()
        .signature(signature)
        .skipMemoryCache(skipMemoryCacheAtPaths?.contains(path) == true)
        .priority(Priority.NORMAL)
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        // ARGB_8888 evita imagens pretas em arquivos com alfa, perfis de cor ou
        // transformações que não funcionam corretamente em RGB_565. GIFs recebem RGB_565
        // explicitamente abaixo, onde a economia de memória é mais importante.
        .format(DecodeFormat.PREFER_ARGB_8888)
        .placeholder(R.drawable.placeholder_square)
        .error(R.drawable.ic_vector_warning_colored)

    // Para thumbnails recortadas, a imagem precisa ser decodificada por fora do
    // quadrado. CENTER_INSIDE primeiro reduzia uma foto 16:9 para 360x203 e depois
    // CENTER_CROP ampliava esse bitmap, causando a pixelização relatada. Sem recorte,
    // CENTER_INSIDE preserva a proporção e evita pixels desnecessários.
    options.downsample(
        if (cropThumbnails) DownsampleStrategy.CENTER_OUTSIDE
        else DownsampleStrategy.CENTER_INSIDE
    )
    // Tamanho máximo do thumbnail baseado no nº real de colunas do grid, não mais um valor
    // fixo de metade da tela. Com 3-5 colunas, metade da tela é MUITO maior que a célula
    // realmente exibida — isso fazia cada item decodificar mais que o dobro do necessário,
    // pesando o scroll em pastas com muita mídia. O upperBound garante que o novo cálculo
    // NUNCA passe do valor antigo (largura da tela / 2): com poucas colunas (ex: lista, 1
    // coluna) width/columnCount seria MAIOR que antes, o que pioraria a memória em vez de
    // ajudar — por isso o teto é sempre min(720, width/2), nunca um valor fixo maior.
    val screenWidthPx = resources.displayMetrics.widthPixels
    val cellWidthPx = screenWidthPx / columnCount.coerceAtLeast(1)
    val upperBound = maxOf(150, minOf(720, screenWidthPx / 2))
    // Um pequeno overscan evita ampliar um bitmap exatamente no limite da célula,
    // sem decodificar a imagem original inteira.
    val desiredThumbSize = (cellWidthPx * 1.35f).roundToInt()
    val minThumbSize = minOf(180, upperBound)
    val maxThumbSize = desiredThumbSize.coerceIn(minThumbSize, upperBound)
    options.override(maxThumbSize, maxThumbSize)

    if (cropThumbnails) {
        options.optionalTransform(CenterCrop())
        options.optionalTransform(
            WebpDrawable::class.java,
            WebpDrawableTransformation(CenterCrop())
        )
    } else {
        options.optionalTransform(FitCenter())
        options.optionalTransform(WebpDrawable::class.java, WebpDrawableTransformation(FitCenter()))
    }

    // GIFs on Android 10+ returned early above through ImageDecoder. GIFs/WebP em
    // versões anteriores continuam usando o drawable animado do Glide.
    val shouldAnimateDrawable = animate && roundCorners == ROUNDED_CORNERS_NONE && (isGif || path.isGif() || path.isWebP())
    if (shouldAnimateDrawable) {
        // this is required to make glide cache aware of changes
        options.decode(Drawable::class.java)
        if (isGif || path.isGif()) {
            // Decodifica cada frame do GIF no tamanho do thumbnail (não no tamanho original do arquivo).
            // Ex: GIF 500×500 em thumbnail 220×220 → bem menos memória por frame, sem
            // reduzir a capa a um bitmap visivelmente pixelado.
            options.downsample(
                if (cropThumbnails) DownsampleStrategy.CENTER_OUTSIDE
                else DownsampleStrategy.CENTER_INSIDE
            )
            // A prioridade LOW deixava GIFs visíveis esperando atrás de outros requests.
            // O tamanho continua limitado ao necessário para a célula, mas a imagem não
            // sofre a perda visível do RGB_565.
            options.priority(Priority.NORMAL)
            val gifSize = maxThumbSize.coerceAtMost(320)
            options.override(gifSize, gifSize)
            options.format(DecodeFormat.PREFER_ARGB_8888)
        }
    } else {
        options.dontAnimate()
        // don't animate is not enough for webp files, decode as bitmap forces first frame use in animated webps
        options.decode(Bitmap::class.java)
    }

    if (roundCorners != ROUNDED_CORNERS_NONE) {
        val cornerSize =
            if (roundCorners == ROUNDED_CORNERS_SMALL) com.goodwy.commons.R.dimen.rounded_corner_radius_big else com.goodwy.commons.R.dimen.dialog_corner_radius
        val cornerRadius = resources.getDimension(cornerSize).toInt()
        val roundedCornersTransform = RoundedCorners(cornerRadius)
        // CenterCrop só é aplicado quando o usuário pediu recorte. Sem recorte,
        // FitCenter mantém a razão largura/altura e arredonda a imagem sem achatá-la.
        val baseTransform = if (cropThumbnails) CenterCrop() else FitCenter()
        options.optionalTransform(MultiTransformation(baseTransform, roundedCornersTransform))
        options.optionalTransform(
            WebpDrawable::class.java,
            MultiTransformation(
                WebpDrawableTransformation(baseTransform),
                WebpDrawableTransformation(roundedCornersTransform)
            )
        )
    }

    WebpBitmapFactory.sUseSystemDecoder = false // CVE-2023-4863
    val imageModel: Any = when {
        path.startsWith("content://") || path.startsWith("file://") -> path.toUri()
        else -> File(path)
    }
    var builder = Glide.with(target)
        .load(imageModel)
        .apply(options)
        .set(WebpDownsampler.USE_SYSTEM_DECODER, false) // CVE-2023-4863
        .transition(if (shouldAnimateDrawable) {
            // Crossfade cria uma camada transitória por cima de cada GIF e aumenta o
            // trabalho do compositor durante a rolagem.
            DrawableTransitionOptions()
        } else {
            getOptionalCrossFadeTransition(crossFadeDuration)
        })

    builder = builder.listener(object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            targetBitmap: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            // Glide proíbe iniciar novos loads dentro de RequestListener callbacks.
            // Todas as chamadas recursivas precisam ser postadas na main thread.
            if (fallbackPath != null && fallbackPath != path) {
                target.post {
                    if (target.tag != requestToken || isDestroyedActivity()) return@post
                    loadImageBase(
                        path = fallbackPath,
                        target = target,
                        cropThumbnails = cropThumbnails,
                        roundCorners = roundCorners,
                        signature = ObjectKey("$signature-fallback"),
                        skipMemoryCacheAtPaths = skipMemoryCacheAtPaths,
                        animate = animate,
                        isVideo = isVideo,
                        tryLoadingWithPicasso = tryLoadingWithPicasso,
                        isGif = isGif,
                        crossFadeDuration = crossFadeDuration,
                        columnCount = columnCount,
                        allowMediaStoreFallback = false,
                        onError = onError
                    )
                }
            } else if (allowMediaStoreFallback && !path.startsWith("content://") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ensureBackgroundThread {
                    val mediaStorePath = findMediaStorePath(path)
                    target.post {
                        if (target.tag != requestToken || isDestroyedActivity()) return@post
                        if (mediaStorePath != null) {
                            loadImageBase(
                                path = mediaStorePath,
                                target = target,
                                cropThumbnails = cropThumbnails,
                                roundCorners = roundCorners,
                                signature = ObjectKey("$signature-mediastore"),
                                skipMemoryCacheAtPaths = skipMemoryCacheAtPaths,
                                animate = animate,
                                isVideo = isVideo,
                                tryLoadingWithPicasso = false,
                                isGif = isGif,
                                crossFadeDuration = crossFadeDuration,
                                columnCount = columnCount,
                                fallbackPath = path,
                                allowMediaStoreFallback = false,
                                onError = onError
                            )
                        } else if (tryLoadingWithPicasso) {
                            tryLoadingWithPicasso(path, target, cropThumbnails, roundCorners, signature, onError)
                        } else {
                            onError?.invoke()
                        }
                    }
                }
            } else if (tryLoadingWithPicasso && !path.startsWith("content://")) {
                target.post {
                    if (target.tag != requestToken || isDestroyedActivity()) return@post
                    tryLoadingWithPicasso(path, target, cropThumbnails, roundCorners, signature, onError)
                }
            } else if (retryCount < 2) {
                target.postDelayed({
                    if (target.tag != requestToken || isDestroyedActivity()) return@postDelayed
                    loadImageBase(
                        path = path,
                        target = target,
                        cropThumbnails = cropThumbnails,
                        roundCorners = roundCorners,
                        signature = signature,
                        skipMemoryCacheAtPaths = skipMemoryCacheAtPaths,
                        animate = animate,
                        isVideo = isVideo,
                        tryLoadingWithPicasso = tryLoadingWithPicasso,
                        isGif = isGif,
                        crossFadeDuration = crossFadeDuration,
                        columnCount = columnCount,
                        fallbackPath = fallbackPath,
                        allowMediaStoreFallback = allowMediaStoreFallback,
                        onError = onError,
                        retryCount = retryCount + 1
                    )
                }, 400L * (retryCount + 1))
            } else {
                // Assim como todos os outros ramos acima, isto precisa ser postado -
                // qualquer onError que tente iniciar outro carregamento do Glide
                // (comum: mostrar um ícone de aviso, ou tentar outra fonte) trava
                // com "You can't start or clear loads in RequestListener or Target
                // callbacks" se chamado direto daqui dentro.
                target.post {
                    if (target.tag != requestToken) return@post
                    onError?.invoke()
                }
            }

            return true
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            targetBitmap: Target<Drawable>,
            dataSource: DataSource,
            isFirstResource: Boolean,
        ) = false
    })

    builder.into(target)
}

private fun Context.findMediaStorePath(path: String): String? {
    return try {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        contentResolver.query(
            Files.getContentUri("external"),
            projection,
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(path),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(Files.getContentUri("external"), cursor.getLong(0)).toString()
            } else null
        }
    } catch (_: Exception) {
        null
    }
}

private val gifDecodeExecutor = Executors.newFixedThreadPool(
    2.coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
) { runnable ->
    Thread(runnable, "Right100-gif-decode").apply {
        priority = Thread.NORM_PRIORITY - 1
    }
}

@Suppress("NewApi")
private fun Context.loadAnimatedWithImageDecoder(
    path: String,
    target: MySquareImageView,
    cropThumbnails: Boolean,
    columnCount: Int,
    onFallback: () -> Unit
) {
    val requestToken = target.tag
    val screenWidthPx = resources.displayMetrics.widthPixels
    val cellWidthPx = screenWidthPx / columnCount.coerceAtLeast(1)
    val gifSize = cellWidthPx.coerceIn(160, 320)
    target.scaleType = if (cropThumbnails) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
    Glide.with(target).clear(target)
    target.setImageResource(R.drawable.placeholder_square)

    gifDecodeExecutor.execute {
        if (target.tag != requestToken) return@execute

        fun decodeWith(allocator: Int): AnimatedImageDrawable? {
            val source = if (path.startsWith("content://")) {
                ImageDecoder.createSource(contentResolver, path.toUri())
            } else {
                ImageDecoder.createSource(File(path))
            }
            return ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                decoder.setAllocator(allocator)
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
                // O target quadrado anterior deformava GIFs não quadrados. Reduza
                // proporcionalmente para caber na célula e deixe o ImageView decidir
                // entre FIT_CENTER e CENTER_CROP.
                val sourceWidth = info.size.width.coerceAtLeast(1)
                val sourceHeight = info.size.height.coerceAtLeast(1)
                val scale = if (cropThumbnails) {
                    // CENTER_CROP precisa de pixels suficientes para cobrir o
                    // quadrado; preserve a proporção e dimensione pelo lado externo.
                    maxOf(
                        gifSize.toFloat() / sourceWidth,
                        gifSize.toFloat() / sourceHeight
                    ).coerceAtMost(1f)
                } else {
                    minOf(
                        gifSize.toFloat() / sourceWidth,
                        gifSize.toFloat() / sourceHeight,
                        1f
                    )
                }
                val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
                decoder.setTargetSize(targetWidth, targetHeight)
            } as? AnimatedImageDrawable
        }

        val drawable = try {
            // HARDWARE = frames ficam em textura e a composição de cada frame da
            // animação é feita pela GPU. Com dezenas de capas animadas ao mesmo
            // tempo, ALLOCATOR_SOFTWARE força a CPU a fazer o blit de cada frame de
            // cada capa manualmente a cada tick, e é isso que trava a UI - o
            // allocator anterior (SOFTWARE) contradizia o próprio motivo pelo qual
            // HARDWARE existe. Mantém um fallback para SOFTWARE só por segurança,
            // caso algum aparelho específico tenha problema com bitmap HARDWARE.
            decodeWith(ImageDecoder.ALLOCATOR_HARDWARE)
        } catch (_: Exception) {
            try {
                decodeWith(ImageDecoder.ALLOCATOR_SOFTWARE)
            } catch (_: Exception) {
                null
            }
        }

        target.post {
            if (target.tag != requestToken) {
                drawable?.stop()
                return@post
            }
            if (drawable == null) {
                onFallback()
            } else {
                target.setImageDrawable(drawable)
                // O adapter controla start/stop conforme a viewport e o estado do scroll.
                // Não inicie aqui: binds fora da viewport não podem manter frames ativos.
            }
        }
    }
}


fun Context.loadSVG(
    path: String,
    target: MySquareImageView,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: ObjectKey,
    crossFadeDuration: Int = THUMBNAIL_FADE_DURATION_MS,
) {
    target.scaleType = if (cropThumbnails) {
        ImageView.ScaleType.CENTER_CROP
    } else {
        ImageView.ScaleType.FIT_CENTER
    }

    val options = RequestOptions()
        .signature(signature)
        .placeholder(R.drawable.placeholder_square)
        .error(R.drawable.ic_vector_warning_colored)
    val imageModel: Any = when {
        path.startsWith("content://") || path.startsWith("file://") -> path.toUri()
        else -> File(path)
    }
    var builder = Glide.with(target)
        .`as`(PictureDrawable::class.java)
        .listener(SvgSoftwareLayerSetter())
        .load(imageModel)
        .apply(options)
        .transition(getOptionalCrossFadeTransition(crossFadeDuration))

    if (roundCorners != ROUNDED_CORNERS_NONE) {
        val cornerSize = when (roundCorners) {
            ROUNDED_CORNERS_SMALL -> com.goodwy.commons.R.dimen.rounded_corner_radius_small
            else -> com.goodwy.commons.R.dimen.rounded_corner_radius_big
        }

        val cornerRadius = resources.getDimension(cornerSize).toInt()
        builder = builder.transform(CenterCrop(), RoundedCorners(cornerRadius))
    }

    builder.into(target)
}

// intended mostly for Android 11 issues, that fail loading PNG files bigger than 10 MB
fun Context.tryLoadingWithPicasso(
    path: String,
    view: MySquareImageView,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: ObjectKey,
    onError: (() -> Unit)? = null
) {
    var pathToLoad = "file://$path"
    pathToLoad = pathToLoad.replace("%", "%25").replace("#", "%23")

    try {
        Picasso.get().cancelRequest(view)
        var builder = Picasso.get()
            .load(pathToLoad)
            .stableKey(signature.toString())
            .placeholder(R.drawable.placeholder_square)
            .error(R.drawable.ic_vector_warning_colored)

        builder = if (cropThumbnails) {
            builder.centerCrop().fit()
        } else {
            builder.centerInside()
        }

        if (roundCorners != ROUNDED_CORNERS_NONE) {
            val cornerSize =
                if (roundCorners == ROUNDED_CORNERS_SMALL) com.goodwy.commons.R.dimen.rounded_corner_radius_small else com.goodwy.commons.R.dimen.rounded_corner_radius_big
            val cornerRadius = resources.getDimension(cornerSize).toInt()
            builder = builder.transform(PicassoRoundedCornersTransformation(cornerRadius.toFloat()))
        }

        builder.into(view, object : PicassoCallback {
            override fun onSuccess() = Unit
            override fun onError(e: Exception) {
                onError?.invoke()
            }
        })
    } catch (e: Exception) {
        onError?.invoke()
    }
}

fun Context.getCachedDirectories(
    getVideosOnly: Boolean = false,
    getImagesOnly: Boolean = false,
    forceShowHidden: Boolean = false,
    forceShowExcluded: Boolean = false,
    callback: (ArrayList<Directory>) -> Unit,
) {
    ensureBackgroundThread {
        val directories = try {
            directoryDB.getAll() as ArrayList<Directory>
        } catch (e: Exception) {
            ArrayList()
        }

        if (!config.showRecycleBinAtFolders) {
            directories.removeAll { it.isRecycleBin() }
        }

        val shouldShowHidden = config.shouldShowHidden || forceShowHidden
        val excludedPaths = if (config.temporarilyShowExcluded || forceShowExcluded) {
            HashSet()
        } else {
            config.excludedFolders
        }

        val includedPaths = config.includedFolders

        val folderNoMediaStatuses = HashMap<String, Boolean>()
        val noMediaFolders = getNoMediaFoldersSync()
        noMediaFolders.forEach { folder ->
            folderNoMediaStatuses["$folder/$NOMEDIA"] = true
        }

        var filteredDirectories = directories.filter {
            it.path.shouldFolderBeVisible(
                excludedPaths = excludedPaths,
                includedPaths = includedPaths,
                showHidden = shouldShowHidden,
                folderNoMediaStatuses = folderNoMediaStatuses
            ) { path, hasNoMedia ->
                folderNoMediaStatuses[path] = hasNoMedia
            }
        } as ArrayList<Directory>

        val filterMedia = config.filterMedia
        filteredDirectories = (when {
            getVideosOnly -> filteredDirectories.filter { it.types and TYPE_VIDEOS != 0 }
            getImagesOnly -> filteredDirectories.filter { it.types and TYPE_IMAGES != 0 }
            else -> filteredDirectories.filter {
                (filterMedia and TYPE_IMAGES != 0 && it.types and TYPE_IMAGES != 0)
                    || (filterMedia and TYPE_VIDEOS != 0 && it.types and TYPE_VIDEOS != 0)
                    || (filterMedia and TYPE_GIFS != 0 && it.types and TYPE_GIFS != 0)
                    || (filterMedia and TYPE_RAWS != 0 && it.types and TYPE_RAWS != 0)
                    || (filterMedia and TYPE_SVGS != 0 && it.types and TYPE_SVGS != 0)
                    || (filterMedia and TYPE_PORTRAITS != 0 && it.types and TYPE_PORTRAITS != 0)
            }
        }) as ArrayList<Directory>

        if (shouldShowHidden) {
            val hiddenString = resources.getString(R.string.hidden)
            filteredDirectories.forEach {
                val noMediaPath = "${it.path}/$NOMEDIA"
                val hasNoMedia = if (folderNoMediaStatuses.keys.contains(noMediaPath)) {
                    folderNoMediaStatuses[noMediaPath]!!
                } else {
                    it.path.doesThisOrParentHaveNoMedia(folderNoMediaStatuses) { path, hasNoMedia ->
                        val newPath = "$path/$NOMEDIA"
                        folderNoMediaStatuses[newPath] = hasNoMedia
                    }
                }

                it.name = if ((hasNoMedia || it.path.contains("/.")) && !it.path.isThisOrParentIncluded(includedPaths)) {
                    "${it.name.removeSuffix(hiddenString).trim()} $hiddenString"
                } else {
                    it.name.removeSuffix(hiddenString).trim()
                }
            }
        }

        val clone = filteredDirectories.clone() as ArrayList<Directory>
        callback(clone.distinctBy { it.path.getDistinctPath() } as ArrayList<Directory>)
        removeInvalidDBDirectories(filteredDirectories)
    }
}

private const val MEDIA_SNAPSHOT_PREFS = "media_folder_snapshots_v1"
private const val MEDIA_SNAPSHOT_MAX_ITEMS = 3000

private fun mediaSnapshotKey(path: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
    return "folder_" + digest.joinToString("") { "%02x".format(it) }
}

private fun mediaSnapshotMetaKey(path: String): String =
    mediaSnapshotKey(path) + "_signature"

private fun mediaFolderSignature(path: String): String? {
    if (path.isEmpty() || path == FAVORITES || path == RECYCLE_BIN) {
        return null
    }

    val folder = java.io.File(path)
    if (!folder.isDirectory) return null

    val files = folder.listFiles()?.filter { it.isFile } ?: return null

    val payload = buildString {
        append(folder.lastModified())
            .append('|')
            .append(files.size)
            .append('|')

        files.sortedBy { it.name }.forEach { file ->
            append(file.name.length)
                .append(':')
                .append(file.name)
                .append(':')
                .append(file.length())
                .append(':')
                .append(file.lastModified())
                .append(';')
        }
    }

    val digest = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))

    return folder.lastModified().toString() + ":" +
        digest.joinToString("") { "%02x".format(it) }
}

fun Context.isPersistedMediaSnapshotFresh(path: String): Boolean {
    if (path.isEmpty() || path == FAVORITES || path == RECYCLE_BIN) {
        return false
    }

    val prefs = getSharedPreferences(
        MEDIA_SNAPSHOT_PREFS,
        Context.MODE_PRIVATE
    )

    val raw = prefs.getString(mediaSnapshotKey(path), null) ?: return false

    val count = try {
        JSONArray(raw).length()
    } catch (_: Exception) {
        return false
    }

    // Um snapshot no limite pode estar truncado.
    if (count >= MEDIA_SNAPSHOT_MAX_ITEMS) return false

    val savedSignature = prefs.getString(
        mediaSnapshotMetaKey(path),
        null
    ) ?: return false

    val currentSignature = try {
        mediaFolderSignature(path)
    } catch (_: Exception) {
        null
    } ?: return false

    return savedSignature == currentSignature
}

private fun Context.loadMediaSnapshot(path: String): ArrayList<Medium>? {
    if (path.isEmpty() || path == FAVORITES || path == RECYCLE_BIN) return null
    val raw = getSharedPreferences(MEDIA_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
        .getString(mediaSnapshotKey(path), null) ?: return null
    return try {
        val json = JSONArray(raw)
        ArrayList<Medium>(json.length()).apply {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                add(
                    Medium(
                        id = null,
                        name = item.optString("name"),
                        path = item.optString("path"),
                        parentPath = item.optString("parentPath"),
                        modified = item.optLong("modified"),
                        taken = item.optLong("taken"),
                        size = item.optLong("size"),
                        type = item.optInt("type"),
                        videoDuration = item.optInt("videoDuration"),
                        isFavorite = item.optBoolean("isFavorite"),
                        deletedTS = item.optLong("deletedTS"),
                        mediaStoreId = item.optLong("mediaStoreId")
                    )
                )
            }
        }
    } catch (_: Exception) {
        null
    }
}

fun Context.saveMediaSnapshot(path: String, media: List<ThumbnailItem>) {
    if (path.isEmpty() || path == FAVORITES || path == RECYCLE_BIN) return
    try {
        val json = JSONArray()
        media.asSequence().filterIsInstance<Medium>().take(MEDIA_SNAPSHOT_MAX_ITEMS).forEach { item ->
            json.put(JSONObject().apply {
                put("name", item.name)
                put("path", item.path)
                put("parentPath", item.parentPath)
                put("modified", item.modified)
                put("taken", item.taken)
                put("size", item.size)
                put("type", item.type)
                put("videoDuration", item.videoDuration)
                put("isFavorite", item.isFavorite)
                put("deletedTS", item.deletedTS)
                put("mediaStoreId", item.mediaStoreId)
            })
        }
        val prefs = getSharedPreferences(
            MEDIA_SNAPSHOT_PREFS,
            Context.MODE_PRIVATE
        )

        val editor = prefs.edit()
            .putString(mediaSnapshotKey(path), json.toString())

        val signature = mediaFolderSignature(path)
        if (signature != null) {
            editor.putString(mediaSnapshotMetaKey(path), signature)
        } else {
            editor.remove(mediaSnapshotMetaKey(path))
        }

        editor.apply()
    } catch (_: Exception) {
    }
}

private fun Context.filterMediaSnapshot(
    media: ArrayList<Medium>,
    getVideosOnly: Boolean,
    getImagesOnly: Boolean
): ArrayList<Medium> {
    val filtered = when {
        getVideosOnly -> media.filter { it.type == TYPE_VIDEOS }
        getImagesOnly -> media.filter { it.type == TYPE_IMAGES }
        else -> {
            val filterMedia = config.filterMedia
            media.filter {
                (filterMedia and TYPE_IMAGES != 0 && it.type == TYPE_IMAGES)
                    || (filterMedia and TYPE_VIDEOS != 0 && it.type == TYPE_VIDEOS)
                    || (filterMedia and TYPE_GIFS != 0 && it.type == TYPE_GIFS)
                    || (filterMedia and TYPE_RAWS != 0 && it.type == TYPE_RAWS)
                    || (filterMedia and TYPE_SVGS != 0 && it.type == TYPE_SVGS)
                    || (filterMedia and TYPE_PORTRAITS != 0 && it.type == TYPE_PORTRAITS)
            }
        }
    }
    return filtered.filter { config.shouldShowHidden || !it.path.contains("/.") } as ArrayList<Medium>
}

fun Context.getPersistedMediaSnapshot(
    path: String,
    getVideosOnly: Boolean = false,
    getImagesOnly: Boolean = false
): ArrayList<ThumbnailItem>? {
    val snapshot = loadMediaSnapshot(path) ?: return null
    val filtered = filterMediaSnapshot(snapshot, getVideosOnly, getImagesOnly)
    // O snapshot é gravado depois da ordenação do scan. Não ordene novamente na UI:
    // isso anulava parte do ganho de entrada instantânea. O próximo scan aplica qualquer
    // alteração recente de ordenação.
    return MediaFetcher(this).groupMedia(filtered, path)
}

fun Context.getCachedMedia(
    path: String,
    getVideosOnly: Boolean = false,
    getImagesOnly: Boolean = false,
    callback: (ArrayList<ThumbnailItem>) -> Unit
) {
    ensureBackgroundThread {
        val mediaFetcher = MediaFetcher(this)
        val foldersToScan = if (path.isEmpty()) {
            mediaFetcher.getFoldersToScan()
        } else {
            arrayListOf(path)
        }

        var media = ArrayList<Medium>()
        if (path == FAVORITES) {
            media.addAll(mediaDB.getFavorites())
        }

        if (path == RECYCLE_BIN) {
            media.addAll(getUpdatedDeletedMedia())
        }

        if (config.filterMedia and TYPE_PORTRAITS != 0) {
            val foldersToAdd = ArrayList<String>()
            for (folder in foldersToScan) {
                val allFiles = File(folder).listFiles() ?: continue
                allFiles.filter { it.name.startsWith("img_", true) && it.isDirectory }.forEach {
                    foldersToAdd.add(it.absolutePath)
                }
            }
            foldersToScan.addAll(foldersToAdd)
        }

        val shouldShowHidden = config.shouldShowHidden
        foldersToScan.filter { path.isNotEmpty() || !config.isFolderProtected(it) }.forEach {
            try {
                val currMedia = mediaDB.getMediaFromPath(it)
                media.addAll(currMedia)
            } catch (ignored: Exception) {
            }
        }

        // A lixeira guarda os arquivos numa pasta oculta (.trash). Sem esta excecao, desligar
        // "mostrar ocultas" fazia a lixeira inteira sumir da lista.
        if (!shouldShowHidden && path != RECYCLE_BIN) {
            media = media.filter { !it.path.contains("/.") } as ArrayList<Medium>
        }

        val filterMedia = config.filterMedia
        media = (when {
            getVideosOnly -> media.filter { it.type == TYPE_VIDEOS }
            getImagesOnly -> media.filter { it.type == TYPE_IMAGES }
            else -> media.filter {
                (filterMedia and TYPE_IMAGES != 0 && it.type == TYPE_IMAGES)
                    || (filterMedia and TYPE_VIDEOS != 0 && it.type == TYPE_VIDEOS)
                    || (filterMedia and TYPE_GIFS != 0 && it.type == TYPE_GIFS)
                    || (filterMedia and TYPE_RAWS != 0 && it.type == TYPE_RAWS)
                    || (filterMedia and TYPE_SVGS != 0 && it.type == TYPE_SVGS)
                    || (filterMedia and TYPE_PORTRAITS != 0 && it.type == TYPE_PORTRAITS)
            }
        }) as ArrayList<Medium>

        val pathToUse = path.ifEmpty { SHOW_ALL }
        mediaFetcher.sortMedia(media, config.getFolderSorting(pathToUse))

        val grouped = mediaFetcher.groupMedia(media, pathToUse)
        callback(grouped.clone() as ArrayList<ThumbnailItem>)
        val OTGPath = config.OTGPath

        try {
            val mediaToDelete = ArrayList<Medium>()
            // creating a new thread intentionally, do not reuse the common background thread
            Thread {
                val appCtx = applicationContext
                media.filter {
                    // getDoesFilePathExist() da como inexistente qualquer caminho dentro de
                    // Android/data - e a lixeira fica la, no mesmo volume da midia. Era esta
                    // linha que apagava o registro logo apos excluir, fazendo a midia "sumir"
                    // da lixeira. Para item da lixeira, a verdade e o proprio arquivo.
                    val naLixeira = TrashStore.isTrashedFile(appCtx, it.path)
                    !(if (naLixeira) File(it.path).exists() else getDoesFilePathExist(it.path, OTGPath))
                }.forEach {
                    if (TrashStore.isTrashedFile(appCtx, it.path)) {
                        deleteDBPath(it.path)
                    } else {
                        mediaToDelete.add(it)
                    }
                }

                if (mediaToDelete.isNotEmpty()) {
                    try {
                        // @Delete do Room usa a chave primaria (id), que aqui e nula (a query do DAO
                        // nao seleciona id) => nao apagava NADA e as linhas "fantasma" ficavam no
                        // banco para sempre. Apaga pelo caminho.
                        mediaToDelete.forEach { mediaDB.deleteMediumPath(it.path) }
                        saveMediaSnapshot(path, media.filter { !mediaToDelete.contains(it) })

                        mediaToDelete.filter { it.isFavorite }.forEach {
                            favoritesDB.deleteFavoritePath(it.path)
                        }
                    } catch (ignored: Exception) {
                    }
                }
            }.start()
        } catch (ignored: Exception) {
        }
    }
}

fun Context.removeInvalidDBDirectories(dirs: ArrayList<Directory>? = null) {
    val dirsToCheck = dirs ?: directoryDB.getAll()
    val OTGPath = config.OTGPath
    dirsToCheck.filter {
        !it.areFavorites()
            && !it.isRecycleBin()
            && !getDoesFilePathExist(it.path, OTGPath)
            && it.path != config.tempFolderPath
    }.forEach {
        try {
            directoryDB.deleteDirPath(it.path)
        } catch (ignored: Exception) {
        }
    }
}

fun Context.updateDBMediaPath(oldPath: String, newPath: String) {
    val newFilename = newPath.getFilenameFromPath()
    val newParentPath = newPath.getParentPath()
    try {
        mediaDB.updateMedium(oldPath, newParentPath, newFilename, newPath)
        favoritesDB.updateFavorite(newFilename, newPath, newParentPath, oldPath)
    } catch (ignored: Exception) {
    }
}

fun Context.updateDBDirectory(directory: Directory) {
    try {
        directoryDB.updateDirectory(
            path = directory.path,
            thumbnail = directory.tmb,
            mediaCnt = directory.mediaCnt,
            lastModified = directory.modified,
            dateTaken = directory.taken,
            size = directory.size,
            mediaTypes = directory.types,
            sortValue = directory.sortValue
        )
    } catch (ignored: Exception) {
    }
}

fun Context.getOTGFolderChildren(path: String) = getDocumentFile(path)?.listFiles()

fun Context.getOTGFolderChildrenNames(path: String): MutableList<String?>? {
    return getOTGFolderChildren(path)?.map { it.name }?.toMutableList()
}

fun Context.getFavoritePaths(): ArrayList<String> {
    return try {
        favoritesDB.getValidFavoritePaths() as ArrayList<String>
    } catch (e: Exception) {
        ArrayList()
    }
}

fun Context.getFavoriteFromPath(path: String): Favorite {
    return Favorite(null, path, path.getFilenameFromPath(), path.getParentPath())
}

fun Context.updateFavorite(path: String, isFavorite: Boolean) {
    try {
        if (isFavorite) {
            favoritesDB.insert(getFavoriteFromPath(path))
        } else {
            favoritesDB.deleteFavoritePath(path)
        }

        // MainActivity so adiciona Favoritos se mediaDB.getFavoritesCount() > 0, e esse
        // COUNT le media.is_favorite, testado ANTES do rescan.
        mediaDB.updateFavorite(path, isFavorite)
    } catch (e: Exception) {
        toast(com.goodwy.commons.R.string.unknown_error_occurred)
    }
}

/**
 * Reintegra ao banco os arquivos que estao fisicamente na pasta da lixeira mas perderam a linha
 * (acontecia em massa com os itens movidos para Android/data: getDoesFilePathExist() os dava
 * como inexistentes e a varredura apagava o registro, fazendo a midia "sumir" da lixeira).
 * Nada e apagado aqui - os arquivos so voltam a aparecer.
 */
private var lastTrashRecovery = 0L
private const val TRASH_RECOVERY_INTERVAL_MS = 30_000L

/**
 * Move para a raiz da lixeira o que ainda esta na arvore antiga (storage/emulated/0/DCIM/...).
 * rename dentro da mesma pasta-mae e instantaneo, entao isso e barato e roda uma vez.
 */
fun Context.flattenTrashLayout() {
    try {
        arrayListOf(TrashStore.publicRoot(this), TrashStore.root(this)).forEach { raiz ->
            if (!raiz.isDirectory) return@forEach
            val aninhados = raiz.walkTopDown()
                .filter {
                    it.isFile && it.name != NOMEDIA && it.name != TrashStore.FLAT_INDEX_NAME &&
                        it.parentFile?.absolutePath != raiz.absolutePath
                }
                .toList()
            aninhados.forEach { f ->
                try {
                    val original = "/" + f.absolutePath.removePrefix(raiz.absolutePath).trimStart('/')
                    val nome = TrashStore.flatNameFor(this, raiz, f)
                    val destino = File(raiz, nome)
                    if (f.renameTo(destino)) {
                        TrashStore.rememberFlat(this, raiz, nome, original)
                        Right100Diag.add("lixeira: " + f.name + " movido para a raiz da lixeira")
                    }
                } catch (_: Exception) {
                }
            }
        }
    } catch (_: Exception) {
    }
}

fun Context.recoverOrphanTrashItems() {
    // Uma vez a cada 30 s basta: sem isso, cada abertura da lixeira varreria a pasta toda.
    val agora = android.os.SystemClock.elapsedRealtime()
    if (agora - lastTrashRecovery < TRASH_RECOVERY_INTERVAL_MS) return
    lastTrashRecovery = agora
    flattenTrashLayout()
    try {
        val conhecidos = HashSet<String>()
        mediaDB.getDeletedMedia().forEach { conhecidos.add(it.path) }

        val alvos = arrayListOf(
            TrashStore.root(this) to TrashStore.root(this),
            TrashStore.publicRoot(this) to TrashStore.publicRoot(this)
        )
        val legado = File(TrashStore.legacyRoot(this), "storage")
        if (legado.isDirectory) alvos.add(legado to TrashStore.legacyRoot(this))

        // .nomedia em cada raiz da lixeira: sem ele o MediaStore indexa a pasta e ela volta a
        // aparecer em outras galerias do aparelho.
        arrayListOf(TrashStore.root(this), TrashStore.publicRoot(this)).forEach { raiz ->
            if (raiz.isDirectory) {
                try {
                    File(raiz, NOMEDIA).createNewFile()
                } catch (_: Exception) {
                }
            }
        }

        val novos = ArrayList<Medium>()
        alvos.forEach { (dir, base) ->
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown().forEach { file ->
                if (!file.isFile || file.name == NOMEDIA) return@forEach
                if (file.name == TrashStore.FLAT_INDEX_NAME) return@forEach
                // Item plano: a origem vem do indice, nao do nome do arquivo.
                val rel = TrashStore.originalPathOfFlat(this, file)?.trimStart('/')
                    ?: file.absolutePath.removePrefix(base.absolutePath).trimStart('/')
                if (rel.isEmpty()) return@forEach
                val chave = "$RECYCLE_BIN/$rel"
                if (!conhecidos.add(chave)) return@forEach
                val tipo = when {
                    file.absolutePath.isVideoFast() -> TYPE_VIDEOS
                    file.absolutePath.isGif() -> TYPE_GIFS
                    file.absolutePath.isRawFast() -> TYPE_RAWS
                    file.absolutePath.isSvg() -> TYPE_SVGS
                    file.absolutePath.isPortrait() -> TYPE_PORTRAITS
                    else -> TYPE_IMAGES
                }
                novos.add(
                    Medium(
                        id = null,
                        name = file.name,
                        path = chave,
                        parentPath = RECYCLE_BIN,
                        modified = file.lastModified(),
                        taken = file.lastModified(),
                        size = file.length(),
                        type = tipo,
                        videoDuration = 0,
                        isFavorite = false,
                        deletedTS = System.currentTimeMillis(),
                        mediaStoreId = 0L
                    )
                )
            }
        }

        if (novos.isNotEmpty()) {
            mediaDB.insertAll(novos)
            Right100Diag.add("lixeira: " + novos.size + " item(ns) recuperado(s) do disco")
        }
    } catch (ignored: Exception) {
    }
}

// remove the "recycle_bin" from the file path prefix, replace it with real bin path /data/user...
fun Context.getUpdatedDeletedMedia(): ArrayList<Medium> {
    recoverOrphanTrashItems()
    val media = try {
        mediaDB.getDeletedMedia() as ArrayList<Medium>
    } catch (ignored: Exception) {
        ArrayList()
    }

    media.forEach {
        // Procura no lugar novo e, se nao achar, na lixeira antiga: sem isso os itens
        // excluidos antes desta correcao sumiriam da lixeira (e o getCachedMedia ainda os
        // apagaria do banco, achando que o arquivo nao existe mais).
        val rel = it.path.removePrefix(RECYCLE_BIN).trimStart('/')
        it.path = TrashStore.resolveTrashedFile(this, rel).toString()
    }
    return media
}

// Caminhos excluidos ha pouco. Uma varredura que comecou ANTES da exclusao terminava depois e
// regravava o arquivo no banco (insertAll), fazendo-o "voltar". Enquanto o arquivo nao existir
// mais no disco, ele e ignorado por varreduras e regravacoes por 2 minutos.
object RecentlyDeletedPaths {
    private const val TTL_MS = 120_000L
    private val paths = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun add(path: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        paths[path] = now
        if (paths.size > 500) paths.entries.removeAll { now - it.value > TTL_MS }
    }

    fun isGone(path: String): Boolean {
        val addedAt = paths[path] ?: return false
        if (android.os.SystemClock.elapsedRealtime() - addedAt > TTL_MS) {
            paths.remove(path)
            return false
        }
        return !java.io.File(path).exists()
    }
}

fun Context.deleteDBPath(path: String) {
    RecentlyDeletedPaths.add(path)
    deleteMediumWithPath(TrashStore.toTrashKey(this, path))
}

fun Context.deleteMediumWithPath(path: String) {
    try {
        mediaDB.deleteMediumPath(path)
    } catch (ignored: Exception) {
    }
}

fun Context.updateWidgets() {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)
        ?.getAppWidgetIds(ComponentName(applicationContext, MyWidgetProvider::class.java))
        ?: return

    if (widgetIDs.isNotEmpty()) {
        Intent(applicationContext, MyWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIDs)
            sendBroadcast(this)
        }
    }
}

// based on https://github.com/sannies/mp4parser/blob/master/examples/src/main/java/com/google/code/mp4parser/example/PrintStructure.java
fun Context.parseFileChannel(
    path: String,
    fc: FileChannel,
    level: Int,
    start: Long,
    end: Long,
    callback: () -> Unit
) {
    val fileChannelContainers = arrayListOf("moov", "trak", "mdia", "minf", "udta", "stbl")
    try {
        var iteration = 0
        var currEnd = end
        fc.position(start)
        if (currEnd <= 0) {
            currEnd = start + fc.size()
        }

        while (currEnd - fc.position() > 8) {
            // just a check to avoid deadloop at some videos
            if (iteration++ > 50) {
                return
            }

            val begin = fc.position()
            val byteBuffer = ByteBuffer.allocate(8)
            fc.read(byteBuffer)
            byteBuffer.rewind()
            val size = IsoTypeReader.readUInt32(byteBuffer)
            val type = IsoTypeReader.read4cc(byteBuffer)
            val newEnd = begin + size

            if (type == "uuid") {
                val fis = FileInputStream(File(path))
                fis.skip(begin)

                val sb = StringBuilder()
                val buffer = ByteArray(1024)
                while (sb.length < size) {
                    val n = fis.read(buffer)
                    if (n != -1) {
                        sb.append(String(buffer, 0, n))
                    } else {
                        break
                    }
                }

                val xmlString = sb.toString().lowercase(Locale.getDefault())
                if (
                    xmlString.contains("gspherical:projectiontype>equirectangular")
                    || xmlString.contains("gspherical:projectiontype=\"equirectangular\"")
                ) {
                    callback.invoke()
                }
                return
            }

            if (fileChannelContainers.contains(type)) {
                parseFileChannel(path, fc, level + 1, begin + 8, newEnd, callback)
            }

            fc.position(newEnd)
        }
    } catch (ignored: Exception) {
    }
}

fun Context.addPathToDB(path: String) {
    ensureBackgroundThread {
        if (!getDoesFilePathExist(path)) {
            return@ensureBackgroundThread
        }

        val type = when {
            path.isVideoFast() -> TYPE_VIDEOS
            path.isGif() -> TYPE_GIFS
            path.isRawFast() -> TYPE_RAWS
            path.isSvg() -> TYPE_SVGS
            path.isPortrait() -> TYPE_PORTRAITS
            else -> TYPE_IMAGES
        }

        try {
            val isFavorite = favoritesDB.isFavorite(path)
            val videoDuration = if (type == TYPE_VIDEOS) getDuration(path) ?: 0 else 0
            val medium = Medium(
                id = null,
                name = path.getFilenameFromPath(),
                path = path,
                parentPath = path.getParentPath(),
                modified = System.currentTimeMillis(),
                taken = System.currentTimeMillis(),
                size = File(path).length(),
                type = type,
                videoDuration = videoDuration,
                isFavorite = isFavorite,
                deletedTS = 0L,
                mediaStoreId = 0L
            )

            mediaDB.insert(medium)
        } catch (ignored: Exception) {
        }
    }
}

fun Context.createDirectoryFromMedia(
    path: String,
    curMedia: ArrayList<Medium>,
    albumCovers: ArrayList<AlbumCover>,
    hiddenString: String,
    includedFolders: MutableSet<String>,
    getProperFileSize: Boolean,
    noMediaFolders: ArrayList<String>,
): Directory {
    val OTGPath = config.OTGPath
    val grouped = MediaFetcher(this).groupMedia(curMedia, path)
    var thumbnail: String? = null

    albumCovers.forEach {
        if (it.path == path && getDoesFilePathExist(it.tmb, OTGPath)) {
            thumbnail = it.tmb
        }
    }

    if (thumbnail == null) {
        val sortedMedia = grouped.filter { it is Medium }.toMutableList() as ArrayList<Medium>
        thumbnail = sortedMedia.firstOrNull { getDoesFilePathExist(it.path, OTGPath) }?.path ?: ""
    }

    if (config.OTGPath.isNotEmpty() && thumbnail!!.startsWith(config.OTGPath)) {
        thumbnail = thumbnail!!.getOTGPublicPath(applicationContext)
    }

    val isSortingAscending = config.directorySorting.isSortingAscending()
    val defaultMedium = Medium(0, "", "", "", 0L, 0L, 0L, 0, 0, false, 0L, 0L)
    val firstItem = curMedia.firstOrNull() ?: defaultMedium
    val lastItem = curMedia.lastOrNull() ?: defaultMedium
    val dirName = checkAppendingHidden(path, hiddenString, includedFolders, noMediaFolders)
    val lastModified = if (isSortingAscending) {
        min(firstItem.modified, lastItem.modified)
    } else {
        max(firstItem.modified, lastItem.modified)
    }

    val dateTaken = if (isSortingAscending) {
        min(firstItem.taken, lastItem.taken)
    } else {
        max(firstItem.taken, lastItem.taken)
    }

    val size = if (getProperFileSize) curMedia.sumByLong { it.size } else 0L
    val mediaTypes = curMedia.getDirMediaTypes()
    val count = curMedia.size
    val sortValue = getDirectorySortingValue(curMedia, path, dirName, size, count)
    return Directory(
        id = null,
        path = path,
        tmb = thumbnail!!,
        name = dirName,
        mediaCnt = curMedia.size,
        modified = lastModified,
        taken = dateTaken,
        size = size,
        location = getPathLocation(path),
        types = mediaTypes,
        sortValue = sortValue
    )
}

fun Context.getDirectorySortingValue(
    media: ArrayList<Medium>,
    path: String,
    name: String,
    size: Long,
    count: Int
): String {
    val sorting = config.directorySorting
    val sorted = when {
        sorting and SORT_BY_NAME != 0 -> return name
        sorting and SORT_BY_PATH != 0 -> return path
        sorting and SORT_BY_SIZE != 0 -> return size.toString()
        sorting and SORT_BY_COUNT != 0 -> return count.toString()
        sorting and SORT_BY_DATE_MODIFIED != 0 -> media.sortedBy { it.modified }
        sorting and SORT_BY_DATE_TAKEN != 0 -> media.sortedBy { it.taken }
        else -> media
    }

    val relevantMedium = if (sorting.isSortingAscending()) {
        sorted.firstOrNull() ?: return ""
    } else {
        sorted.lastOrNull() ?: return ""
    }

    val result: Any = when {
        sorting and SORT_BY_DATE_MODIFIED != 0 -> relevantMedium.modified
        sorting and SORT_BY_DATE_TAKEN != 0 -> relevantMedium.taken
        else -> 0
    }

    return result.toString()
}

fun Context.updateDirectoryPath(path: String) {
    val mediaFetcher = MediaFetcher(applicationContext)
    val getImagesOnly = false
    val getVideosOnly = false
    val hiddenString = getString(R.string.hidden)
    val albumCovers = config.parseAlbumCovers()
    val includedFolders = config.includedFolders
    val noMediaFolders = getNoMediaFoldersSync()

    val sorting = config.getFolderSorting(path)
    val grouping = config.getFolderGrouping(path)
    val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
        || sorting and SORT_BY_DATE_TAKEN != 0
        || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
        || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0
        || grouping and GROUP_BY_DATE_TAKEN_YEARLY != 0

    val getProperLastModified = config.directorySorting and SORT_BY_DATE_MODIFIED != 0
        || sorting and SORT_BY_DATE_MODIFIED != 0
        || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
        || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0
        || grouping and GROUP_BY_LAST_MODIFIED_YEARLY != 0

    val getProperFileSize = true  // sempre calcula: a opcao de mostrar tamanho depende disso

    val lastModifieds = if (getProperLastModified) {
        mediaFetcher.getFolderLastModifieds(path)
    } else {
        HashMap()
    }

    val dateTakens = mediaFetcher.getFolderDateTakens(path)
    val favoritePaths = getFavoritePaths()
    val curMedia = mediaFetcher.getFilesFrom(
        curPath = path,
        isPickImage = getImagesOnly,
        isPickVideo = getVideosOnly,
        getProperDateTaken = getProperDateTaken,
        getProperLastModified = getProperLastModified,
        getProperFileSize = getProperFileSize,
        favoritePaths = favoritePaths,
        getVideoDurations = false,
        lastModifieds = lastModifieds,
        dateTakens = dateTakens,
        android11Files = null
    )
    val directory = createDirectoryFromMedia(
        path = path,
        curMedia = curMedia,
        albumCovers = albumCovers,
        hiddenString = hiddenString,
        includedFolders = includedFolders,
        getProperFileSize = getProperFileSize,
        noMediaFolders = noMediaFolders
    )
    updateDBDirectory(directory)
}

fun Context.getFileDateTaken(path: String): Long {
    val projection = arrayOf(
        Images.Media.DATE_TAKEN
    )

    val uri = Files.getContentUri("external")
    val selection = "${Images.Media.DATA} = ?"
    val selectionArgs = arrayOf(path)

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getLongValue(Images.Media.DATE_TAKEN)
            }
        }
    } catch (ignored: Exception) {
    }

    return 0L
}

fun Context.getCompressionFormatFromUri(uri: Uri): CompressFormat {
    val type = getMimeTypeFromUri(uri)
    return when {
        type.equals("image/png", true) -> CompressFormat.PNG
        type.equals("image/webp", true) -> CompressFormat.WEBP
        else -> CompressFormat.JPEG
    }
}

fun Context.resolveUriScheme(
    uri: Uri,
    onPath: (String) -> Unit,
    onContentUri: (Uri) -> Unit,
    onUnknown: () -> Unit = { toast(R.string.unknown_file_location) }
) {
    when (uri.scheme) {
        "file" -> onPath(uri.path!!)
        "content" -> onContentUri(uri)
        else -> onUnknown()
    }
}

fun Context.getTextSizeDir(fontSizeDir: Int = config.fontSizeDir) = when (fontSizeDir) {
    FONT_SIZE_SMALL -> resources.getDimension(com.goodwy.commons.R.dimen.smaller_text_size)
    FONT_SIZE_MEDIUM -> resources.getDimension(com.goodwy.commons.R.dimen.normal_text_size)
    FONT_SIZE_LARGE -> resources.getDimension(com.goodwy.commons.R.dimen.bigger_text_size)
    else -> resources.getDimension(com.goodwy.commons.R.dimen.big_text_size)
}

fun Context.getTextSizeDirBig(fontSizeDir: Int = config.fontSizeDir) = when (fontSizeDir) {
    FONT_SIZE_SMALL -> resources.getDimension(com.goodwy.commons.R.dimen.normal_text_size)
    FONT_SIZE_MEDIUM -> resources.getDimension(com.goodwy.commons.R.dimen.bigger_text_size)
    FONT_SIZE_LARGE -> resources.getDimension(com.goodwy.commons.R.dimen.big_text_size)
    else -> resources.getDimension(com.goodwy.commons.R.dimen.actionbar_text_size)
}

fun Context.getTextSizeDirSmall(fontSizeDir: Int = config.fontSizeDir) = when (fontSizeDir) {
    FONT_SIZE_SMALL -> resources.getDimension(com.goodwy.commons.R.dimen.small_text_size)
    FONT_SIZE_MEDIUM -> resources.getDimension(com.goodwy.commons.R.dimen.smaller_text_size)
    FONT_SIZE_LARGE -> resources.getDimension(com.goodwy.commons.R.dimen.normal_text_size)
    else -> resources.getDimension(com.goodwy.commons.R.dimen.bigger_text_size)
}

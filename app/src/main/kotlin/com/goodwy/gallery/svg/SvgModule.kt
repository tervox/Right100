package com.goodwy.gallery.svg

import android.content.Context
import android.graphics.drawable.PictureDrawable

import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.DiskLruCacheWrapper
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG

import java.io.File
import java.io.InputStream

@GlideModule
class SvgModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // setMemoryCacheScreens/setBitmapPoolScreens estavam em 4f/4f — o padrão do Glide é
        // 2f/1f. Isso reservava, de cara, MUITO mais RAM pro cache de imagens e pro pool de
        // bitmaps do que o normal (numa tela de ~720×1600, 4f de cache de memória sozinho já
        // passa de 18MB reservados, e outros 18MB pro bitmap pool — quase 37MB só nisso).
        // Num aparelho com heap máximo de ~256MB (confirmado pelo log de memória do app), somado
        // a ~30 capas de pasta animadas + pastas com muita mídia + os caches próprios do app,
        // essa reserva agressiva de memória é uma causa bem provável dos fechamentos por falta
        // de memória. Reduzido pra bem abaixo do padrão do Glide, adequado a um aparelho de
        // pouca RAM.
        val calculator = MemorySizeCalculator.Builder(context)
            .setMemoryCacheScreens(2f)
            .setBitmapPoolScreens(0.75f)
            .build()
        builder.setMemoryCache(LruResourceCache(calculator.memoryCacheSize.toLong()))
        builder.setBitmapPool(LruBitmapPool(calculator.bitmapPoolSize.toLong()))
        // Aumenta disk cache de 250MB para 500MB — thumbnails ficam no disco mais tempo,
        // acelerando segunda visita mesmo se memória foi limpa pelo Android
        val diskCacheDir = File(context.cacheDir, "glide_cache")
        builder.setDiskCache { DiskLruCacheWrapper.create(diskCacheDir, 500L * 1024 * 1024) }
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.register(SVG::class.java, PictureDrawable::class.java, SvgDrawableTranscoder()).append(InputStream::class.java, SVG::class.java, SvgDecoder())
    }

    override fun isManifestParsingEnabled() = false
}

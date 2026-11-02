package com.goodwy.gallery.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Decodifica o primeiro quadro de um video sem consultar metadados.
 *
 * Existe por causa dos MP4 que nao declaram duracao (fragmentados, ou remuxados por
 * aplicativos de download). Nesses arquivos o MediaMetadataRetriever e o MediaStore
 * respondem 0, nao geram miniatura, e o selo de tempo fica em 00:00 - mas o video toca
 * normalmente, porque a decodificacao das amostras funciona. Este objeto faz exatamente
 * isso: le a primeira amostra da trilha de video e converte o quadro para Bitmap.
 */
object QuadroDeVideo {

    fun primeiroQuadro(caminho: String): Bitmap? {
        return quadroEm(caminho, -1L)
    }

    // Mesma decodificacao do primeiro quadro, mas podendo pedir um INSTANTE do video.
    // Existe por causa dos videos que comecam com quadro PRETO (fade-in, GIF convertido,
    // video do X/Twitter): o primeiro quadro existe e decodifica, so que e' preto.
    // microssegundos < 0 significa "sem seek" = primeiro quadro do arquivo.
    fun quadroEm(caminho: String, microssegundos: Long): Bitmap? {
        if (caminho.startsWith("content://")) return null
        val arquivo = if (caminho.startsWith("file://")) {
            android.net.Uri.parse(caminho).path ?: caminho
        } else {
            caminho
        }
        if (!File(arquivo).isFile) return null

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(arquivo)

            var trilha = -1
            var formato: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidato = extractor.getTrackFormat(i)
                val mime = candidato.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trilha = i
                    formato = candidato
                    break
                }
            }
            if (trilha < 0 || formato == null) return null
            val mime = formato.getString(MediaFormat.KEY_MIME) ?: return null

            extractor.selectTrack(trilha)

            if (microssegundos >= 0L) {
                try {
                    extractor.seekTo(microssegundos, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                } catch (_: Exception) {
                    // Arquivo sem indice para seek: segue de onde o extractor estiver.
                }
            }
            val decodificador = MediaCodec.createDecoderByType(mime)
            codec = decodificador
            decodificador.configure(formato, null, null, 0)
            decodificador.start()

            val info = MediaCodec.BufferInfo()
            var enviando = true
            var voltas = 0
            while (voltas < 300) {
                voltas++

                if (enviando) {
                    val entrada = decodificador.dequeueInputBuffer(10_000)
                    if (entrada >= 0) {
                        val buffer = decodificador.getInputBuffer(entrada)
                        val tamanho = if (buffer == null) {
                            -1
                        } else {
                            extractor.readSampleData(buffer, 0)
                        }
                        if (buffer == null || tamanho < 0) {
                            decodificador.queueInputBuffer(
                                entrada, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            enviando = false
                        } else {
                            decodificador.queueInputBuffer(
                                entrada, 0, tamanho, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val saida = decodificador.dequeueOutputBuffer(info, 10_000)
                if (saida >= 0) {
                    val imagem = try {
                        decodificador.getOutputImage(saida)
                    } catch (_: Exception) {
                        null
                    }
                    val bitmap = if (imagem == null) null else converter(imagem)
                    try { imagem?.close() } catch (_: Exception) {}
                    decodificador.releaseOutputBuffer(saida, false)
                    if (bitmap != null) return bitmap
                } else if (saida == MediaCodec.INFO_TRY_AGAIN_LATER && !enviando) {
                    break
                }
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun converter(imagem: Image): Bitmap? {
        try {
            if (imagem.format != ImageFormat.YUV_420_888) return null
            val largura = imagem.width
            val altura = imagem.height
            if (largura <= 0 || altura <= 0) return null
            val planos = imagem.planes
            if (planos.size < 3) return null

            val y = planos[0].buffer
            val u = planos[1].buffer
            val vMn = planos[2].buffer
            val nv21 = ByteArray(largura * altura * 3 / 2 + 16)
            var pos = 0

            var linha = 0
            while (linha < altura) {
                var deslocamento = linha * planos[0].rowStride
                var coluna = 0
                while (coluna < largura) {
                    nv21[pos++] = y.get(deslocamento)
                    deslocamento += planos[0].pixelStride
                    coluna++
                }
                linha++
            }

            val alturaCroma = altura / 2
            val larguraCroma = largura / 2
            linha = 0
            while (linha < alturaCroma) {
                var deslocamentoU = linha * planos[1].rowStride
                var deslocamentoV = linha * planos[2].rowStride
                var coluna = 0
                while (coluna < larguraCroma) {
                    nv21[pos++] = vMn.get(deslocamentoV)
                    nv21[pos++] = u.get(deslocamentoU)
                    deslocamentoU += planos[1].pixelStride
                    deslocamentoV += planos[2].pixelStride
                    coluna++
                }
                linha++
            }

            val yuv = YuvImage(nv21, ImageFormat.NV21, largura, altura, null)
            val saida = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, largura, altura), 88, saida)
            val bytes = saida.toByteArray()
            if (bytes.isEmpty()) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            return null
        }
    }

    // Amostra a luminancia media do bitmap (1/32 dos pixels em cada eixo). Um quadro
    // inteiro preto (fade-in) da media proxima de zero. Videos escuros "de verdade"
    // (luma media acima de 12) NAO sao considerados pretos.
    fun ehQuasePreto(bitmap: Bitmap): Boolean {
        val largura = bitmap.width
        val altura = bitmap.height
        if (largura <= 0 || altura <= 0) return false
        val passoX = maxOf(1, largura / 32)
        val passoY = maxOf(1, altura / 32)
        var somaLuma = 0L
        var amostras = 0
        var y = 0
        while (y < altura) {
            var x = 0
            while (x < largura) {
                val cor = bitmap.getPixel(x, y)
                val luma = (Color.red(cor) * 299 +
                    Color.green(cor) * 587 +
                    Color.blue(cor) * 114) / 1000
                somaLuma += luma
                amostras++
                x += passoX
            }
            y += passoY
        }
        if (amostras == 0) return false
        return (somaLuma / amostras) < 12L
    }

    // Quadro do MEIO do video (metade da duracao, no sync sample mais proximo). E' o
    // que acerta quando o primeiro quadro e' preto: o meio normalmente ja e' conteudo.
    fun quadroNoMeio(caminho: String, duracaoSegundos: Int): Bitmap? {
        if (duracaoSegundos <= 0) return null
        val meioMicros = duracaoSegundos * 1_000_000L / 2L
        return try {
            quadroEm(caminho, meioMicros)
        } catch (_: Exception) {
            null
        }
    }
}

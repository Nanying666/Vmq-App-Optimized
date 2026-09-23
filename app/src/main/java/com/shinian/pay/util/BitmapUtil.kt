package com.shinian.pay.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.IOException

/**
 * Bitmap 工具类（Kotlin 版，Java 调用点 API 保持不变）。
 *
 * 从 Uri 直接读取图片流，避免路径转换的适配问题。
 */
object BitmapUtil {

    /**
     * 读取一个缩放后的图片，限定图片大小，避免 OOM。
     *
     * @param uri 图片 uri，支持 "file://"、"content://"
     * @param maxWidth 最大允许宽度
     * @param maxHeight 最大允许高度
     * @return 返回一个缩放后的 Bitmap，失败则返回 null
     */
    @JvmStatic
    fun decodeUri(context: Context, uri: Uri?, maxWidth: Int, maxHeight: Int): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true // 只读取图片尺寸
        readBitmapScale(context, uri, options)

        // 计算实际缩放比例
        var scale = 1
        var i = 0
        while (i < Int.MAX_VALUE) {
            if ((options.outWidth / scale > maxWidth &&
                        options.outWidth / scale > maxWidth * 1.4) ||
                (options.outHeight / scale > maxHeight &&
                        options.outHeight / scale > maxHeight * 1.4)
            ) {
                scale++
                i++
            } else {
                break
            }
        }

        options.inSampleSize = scale
        options.inJustDecodeBounds = false // 读取图片内容
        options.inPreferredConfig = Bitmap.Config.RGB_565
        return try {
            readBitmapData(context, uri, options)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun readBitmapScale(context: Context, uri: Uri?, options: BitmapFactory.Options) {
        if (uri == null) {
            return
        }
        val scheme = uri.scheme
        if (ContentResolver.SCHEME_CONTENT == scheme || ContentResolver.SCHEME_FILE == scheme) {
            var stream: java.io.InputStream? = null
            try {
                stream = context.contentResolver.openInputStream(uri)
                BitmapFactory.decodeStream(stream, null, options)
            } catch (e: Exception) {
                Log.w("readBitmapScale", "Unable to open content: $uri", e)
            } finally {
                if (stream != null) {
                    try {
                        stream.close()
                    } catch (e: IOException) {
                        Log.e("readBitmapScale", "Unable to close content: $uri", e)
                    }
                }
            }
        } else {
            Log.e("readBitmapScale", "Unable to close content: $uri")
        }
    }

    private fun readBitmapData(context: Context, uri: Uri?, options: BitmapFactory.Options): Bitmap? {
        if (uri == null) {
            return null
        }
        var bitmap: Bitmap? = null
        val scheme = uri.scheme
        if (ContentResolver.SCHEME_CONTENT == scheme || ContentResolver.SCHEME_FILE == scheme) {
            var stream: java.io.InputStream? = null
            try {
                stream = context.contentResolver.openInputStream(uri)
                bitmap = BitmapFactory.decodeStream(stream, null, options)
            } catch (e: Exception) {
                Log.e("readBitmapData", "Unable to open content: $uri", e)
            } finally {
                if (stream != null) {
                    try {
                        stream.close()
                    } catch (e: IOException) {
                        Log.e("readBitmapData", "Unable to close content: $uri", e)
                    }
                }
            }
        } else {
            Log.e("readBitmapData", "Unable to close content: $uri")
        }
        return bitmap
    }
}
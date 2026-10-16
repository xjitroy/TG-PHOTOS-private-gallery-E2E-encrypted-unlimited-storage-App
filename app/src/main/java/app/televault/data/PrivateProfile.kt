package app.televault.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/** Bounded avatar bytes are carried only inside the existing authenticated profile event/row. */
data class PrivateProfile(val name: String = "", val username: String = "", val bio: String = "", val avatar: String = "") {
    fun json(): JSONObject {
        require(name.isNotBlank() && name.length <= 80 && username.length <= 64 && bio.length <= 500 && avatar.length <= 32768)
        if (avatar.isNotEmpty()) {
            val bytes = Base64.decode(avatar, Base64.NO_WRAP)
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..256 && bounds.outHeight in 1..256)
            } finally {
                bytes.fill(0)
            }
        }
        return JSONObject().put("name", name).put("username", username).put("bio", bio).put("avatar", avatar)
    }
    companion object {
        fun read(value: JSONObject) = PrivateProfile(value.getString("name"), value.optString("username"), value.optString("bio"), value.optString("avatar")).also { it.json() }
        fun avatar(context: Context, uri: Uri): String {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
            val bitmap = MediaTools.orientThumbnail(context, uri, checkNotNull(context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }))
            try {
                val scale = minOf(1f, 96f / maxOf(bitmap.width, bitmap.height))
                val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    val output = ByteArrayOutputStream()
                    check(small.compress(Bitmap.CompressFormat.JPEG, 85, output))
                    val bytes = output.toByteArray()
                    try {
                        return Base64.encodeToString(bytes, Base64.NO_WRAP).also { require(it.length <= 32768) }
                    } finally {
                        bytes.fill(0)
                    }
                } finally {
                    if (small !== bitmap) small.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}

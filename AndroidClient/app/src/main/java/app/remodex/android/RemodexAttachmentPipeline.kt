package app.remodex.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import app.remodex.android.core.model.CodexImageAttachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class RemodexComposerSendAvailability(
    val isSending: Boolean,
    val isConnected: Boolean,
    val trimmedInput: String,
    val hasReadyImages: Boolean,
    val hasBlockingAttachmentState: Boolean,
) {
    val isSendDisabled: Boolean
        get() = isSending ||
            !isConnected ||
            (trimmedInput.isEmpty() && !hasReadyImages) ||
            hasBlockingAttachmentState
}

data class RemodexComposerAttachmentIntakePlan(
    val acceptedCount: Int,
    val droppedCount: Int,
) {
    val hasOverflow: Boolean
        get() = droppedCount > 0

    companion object {
        fun make(requestedCount: Int, remainingSlots: Int): RemodexComposerAttachmentIntakePlan {
            val safeRequestedCount = max(0, requestedCount)
            val safeRemainingSlots = max(0, remainingSlots)
            val acceptedCount = min(safeRequestedCount, safeRemainingSlots)
            val droppedCount = safeRequestedCount - acceptedCount
            return RemodexComposerAttachmentIntakePlan(
                acceptedCount = acceptedCount,
                droppedCount = droppedCount,
            )
        }
    }
}

data class RemodexComposerImageAttachment(
    val id: String = UUID.randomUUID().toString(),
    val state: RemodexComposerImageAttachmentState,
)

sealed class RemodexComposerImageAttachmentState {
    data object Loading : RemodexComposerImageAttachmentState()

    data class Ready(
        val attachment: CodexImageAttachment,
    ) : RemodexComposerImageAttachmentState()

    data object Failed : RemodexComposerImageAttachmentState()
}

interface RemodexComposerAttachmentProcessor {
    fun makeAttachment(sourceData: ByteArray): CodexImageAttachment?
}

object RemodexAttachmentPipeline : RemodexComposerAttachmentProcessor {
    const val MaxComposerImages = 4
    const val ThumbnailSidePx = 70
    const val ThumbnailCornerRadiusDp = 12

    private const val MaxPayloadDimensionPx = 1600f
    private const val PayloadCompressionQuality = 80
    private const val ThumbnailCompressionQuality = 80
    private val thumbnailCache = object : LruCache<String, Bitmap>(32) {}

    override fun makeAttachment(sourceData: ByteArray): CodexImageAttachment? {
        val normalizedJpegData = normalizePayloadJpeg(sourceData) ?: return null
        val thumbnailBase64 = makeThumbnailBase64Jpeg(normalizedJpegData) ?: return null
        val payloadDataUrl = "data:image/jpeg;base64,${
            Base64.getEncoder().encodeToString(normalizedJpegData)
        }"

        return CodexImageAttachment(
            id = UUID.randomUUID().toString(),
            thumbnailBase64JPEG = thumbnailBase64,
            payloadDataURL = payloadDataUrl,
            sourceURL = null,
        )
    }

    fun thumbnailBitmap(thumbnailBase64: String): Bitmap? {
        if (thumbnailBase64.isBlank()) {
            return null
        }

        thumbnailCache.get(thumbnailBase64)?.let { cached ->
            return cached
        }

        val decodedBytes = runCatching {
            Base64.getDecoder().decode(thumbnailBase64)
        }.getOrNull() ?: return null
        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size) ?: return null
        thumbnailCache.put(thumbnailBase64, bitmap)
        return bitmap
    }

    private fun normalizePayloadJpeg(sourceData: ByteArray): ByteArray? {
        val normalizedBitmap = decodeNormalizedBitmap(sourceData) ?: return null
        val sourceWidth = normalizedBitmap.width
        val sourceHeight = normalizedBitmap.height
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            normalizedBitmap.recycle()
            return null
        }

        val longestSide = max(sourceWidth, sourceHeight).toFloat()
        val scale = min(1f, MaxPayloadDimensionPx / longestSide)
        val targetWidth = max(1, floor(sourceWidth * scale).toInt())
        val targetHeight = max(1, floor(sourceHeight * scale).toInt())
        val payloadBitmap = if (targetWidth == sourceWidth && targetHeight == sourceHeight) {
            normalizedBitmap
        } else {
            Bitmap.createScaledBitmap(normalizedBitmap, targetWidth, targetHeight, true).also {
                normalizedBitmap.recycle()
            }
        }

        return ByteArrayOutputStream().use { output ->
            if (!payloadBitmap.compress(Bitmap.CompressFormat.JPEG, PayloadCompressionQuality, output)) {
                payloadBitmap.recycle()
                return null
            }
            payloadBitmap.recycle()
            output.toByteArray()
        }
    }

    private fun makeThumbnailBase64Jpeg(imageData: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size) ?: return null
        val sourceWidth = bitmap.width.toFloat()
        val sourceHeight = bitmap.height.toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f) {
            bitmap.recycle()
            return null
        }

        val scale = max(ThumbnailSidePx / sourceWidth, ThumbnailSidePx / sourceHeight)
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        val left = (ThumbnailSidePx - scaledWidth) / 2f
        val top = (ThumbnailSidePx - scaledHeight) / 2f
        val outputBitmap = Bitmap.createBitmap(
            ThumbnailSidePx,
            ThumbnailSidePx,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(outputBitmap)
        canvas.drawBitmap(
            bitmap,
            null,
            android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        bitmap.recycle()

        return ByteArrayOutputStream().use { output ->
            if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, ThumbnailCompressionQuality, output)) {
                outputBitmap.recycle()
                return null
            }
            outputBitmap.recycle()
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
    }

    private fun decodeNormalizedBitmap(sourceData: ByteArray): Bitmap? {
        val decodedBitmap = BitmapFactory.decodeByteArray(sourceData, 0, sourceData.size) ?: return null
        val orientation = ByteArrayInputStream(sourceData).use { stream ->
            runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        }

        val matrix = Matrix().applyOrientation(orientation)
        if (matrix.isIdentity) {
            return decodedBitmap
        }

        return runCatching {
            Bitmap.createBitmap(
                decodedBitmap,
                0,
                0,
                decodedBitmap.width,
                decodedBitmap.height,
                matrix,
                true,
            )
        }.onSuccess {
            decodedBitmap.recycle()
        }.getOrNull()
    }

    private fun Matrix.applyOrientation(orientation: Int): Matrix {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(-90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
        }
        return this
    }
}

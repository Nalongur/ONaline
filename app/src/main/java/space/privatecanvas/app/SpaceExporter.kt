package space.privatecanvas.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

object SpaceExporter {
    private const val ARCHIVE_VERSION = 1
    private const val KDF_ITERATIONS = 210_000

    fun encryptedArchive(
        contact: TrustedContact,
        blocks: List<SharedTextBlock>,
        frames: List<HandDrawnFrame>,
        events: List<CanvasEvent>,
        password: CharArray,
    ): ByteArray {
        require(password.size >= 8) { "Password must contain at least 8 characters" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, KDF_ITERATIONS, 256)).encoded
        password.fill('\u0000')
        val plaintext = spaceJson(contact, blocks, frames, events).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        keyBytes.fill(0)
        val ciphertext = cipher.doFinal(plaintext)
        plaintext.fill(0)
        return JSONObject().apply {
            put("format", "private-canvas-archive")
            put("version", ARCHIVE_VERSION)
            put("kdf", "PBKDF2-HMAC-SHA256")
            put("iterations", KDF_ITERATIONS)
            put("cipher", "AES-256-GCM")
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    data class RestoredSpace(
        val originalName: String,
        val spaceId: String,
        val blocks: List<SharedTextBlock>,
        val frames: List<HandDrawnFrame>,
    )

    fun decryptArchive(bytes: ByteArray, password: CharArray): RestoredSpace {
        require(bytes.size <= 20 * 1024 * 1024) { "Archive is too large" }
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        require(envelope.getString("format") == "private-canvas-archive") { "Unsupported archive" }
        require(envelope.getInt("version") == ARCHIVE_VERSION) { "Unsupported archive version" }
        require(envelope.getString("kdf") == "PBKDF2-HMAC-SHA256") { "Unsupported KDF" }
        require(envelope.getString("cipher") == "AES-256-GCM") { "Unsupported cipher" }
        val iterations = envelope.getInt("iterations")
        require(iterations in 200_000..1_000_000) { "Unsafe KDF parameters" }
        val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
        require(salt.size == 16 && iv.size == 12) { "Invalid archive parameters" }
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded
        password.fill('\u0000')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        keyBytes.fill(0)
        val plaintext = cipher.doFinal(ciphertext)
        val root = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
        require(root.getString("format") == "private-canvas-space" && root.getInt("version") == 1) { "Unsupported space" }
        val blocksJson = root.optJSONArray("blocks") ?: JSONArray()
        require(blocksJson.length() <= 5_000) { "Too many blocks" }
        val blocks = buildList {
            repeat(blocksJson.length()) { index ->
                val item = blocksJson.getJSONObject(index)
                add(
                    SharedTextBlock(
                        id = java.util.UUID.randomUUID().toString(),
                        text = item.optString("text").take(100_000),
                        x = item.optDouble("x", 40.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-100_000f, 100_000f) ?: 40f,
                        y = item.optDouble("y", 140.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-100_000f, 100_000f) ?: 140f,
                        width = item.optDouble("width", 210.0).toFloat().takeIf { it.isFinite() }?.coerceIn(150f, 360f) ?: 210f,
                        height = item.optDouble("height", Double.NaN).toFloat().takeIf { it.isFinite() }?.coerceIn(96f, 520f),
                        pinned = item.optBoolean("pinned"),
                        isRecovered = true,
                    ),
                )
            }
        }
        val framesJson = root.optJSONArray("frames") ?: JSONArray()
        require(framesJson.length() <= 2_000) { "Too many frames" }
        val frames = buildList {
            repeat(framesJson.length()) { index ->
                val item = framesJson.getJSONObject(index)
                val pointsJson = item.optJSONArray("points") ?: JSONArray()
                val points = buildList {
                    repeat(min(pointsJson.length(), 2_000)) { pointIndex ->
                        val point = pointsJson.getJSONArray(pointIndex)
                        val x = point.optDouble(0).toFloat()
                        val y = point.optDouble(1).toFloat()
                        if (x.isFinite() && y.isFinite()) add(CanvasPoint(x.coerceIn(-100_000f, 100_000f), y.coerceIn(-100_000f, 100_000f)))
                    }
                }
                if (isUsefulFrame(points)) add(HandDrawnFrame(java.util.UUID.randomUUID().toString(), points))
            }
        }
        return RestoredSpace(
            originalName = normalizeDisplayName(root.optString("contactName", "Recovered")),
            spaceId = root.optString("spaceId").ifBlank { java.util.UUID.randomUUID().toString() },
            blocks = blocks,
            frames = frames,
        )
    }

    fun png(contact: TrustedContact, blocks: List<SharedTextBlock>, frames: List<HandDrawnFrame>): ByteArray {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        render(Canvas(bitmap), 1600f, 1200f, contact, blocks, frames)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    fun pdf(
        contact: TrustedContact,
        blocks: List<SharedTextBlock>,
        frames: List<HandDrawnFrame>,
        events: List<CanvasEvent> = emptyList(),
        includeTimestamps: Boolean = false,
    ): ByteArray {
        val pageWidth = 1240f
        val pageHeight = 1754f
        val contentLeft = 56f
        val contentTop = 118f
        val contentWidth = pageWidth - 112f
        val contentHeight = pageHeight - contentTop - 58f
        val timestamps = if (includeTimestamps) {
            events.groupBy { it.objectId }.mapValues { (_, values) -> values.maxOf { it.occurredAtEpochMs } }
        } else {
            emptyMap()
        }
        val bounds = contentBounds(blocks, frames, includeTimestamps)
        var scale = 1.15f
        fun grid(atScale: Float): Pair<Int, Int> =
            ceil(bounds.width() * atScale / contentWidth).toInt().coerceAtLeast(1) to
                ceil(bounds.height() * atScale / contentHeight).toInt().coerceAtLeast(1)
        var (columns, rows) = grid(scale)
        while (columns * rows > 64 && scale > .005f) {
            scale *= .8f
            val next = grid(scale)
            columns = next.first
            rows = next.second
        }
        val worldPageWidth = contentWidth / scale
        val worldPageHeight = contentHeight / scale
        val document = PdfDocument()
        val pageCount = columns * rows
        repeat(pageCount) { index ->
            val column = index % columns
            val row = index / columns
            val tile = RectF(
                bounds.left + column * worldPageWidth,
                bounds.top + row * worldPageHeight,
                bounds.left + (column + 1) * worldPageWidth,
                bounds.top + (row + 1) * worldPageHeight,
            )
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), index + 1).create())
            drawPdfPage(
                page.canvas,
                contact,
                blocks,
                frames,
                timestamps,
                includeTimestamps,
                tile,
                scale,
                index + 1,
                pageCount,
                contentLeft,
                contentTop,
                contentWidth,
                contentHeight,
            )
            document.finishPage(page)
        }
        return ByteArrayOutputStream().use { output ->
            document.writeTo(output)
            document.close()
            output.toByteArray()
        }
    }

    private fun render(
        canvas: Canvas,
        width: Float,
        height: Float,
        contact: TrustedContact,
        blocks: List<SharedTextBlock>,
        frames: List<HandDrawnFrame>,
    ) {
        canvas.drawColor(Color.rgb(246, 244, 238))
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 37, 34); textSize = 42f; isFakeBoldText = true }
        canvas.drawText(contact.displayName, 64f, 78f, titlePaint)
        if (blocks.isEmpty() && frames.isEmpty()) return
        val bounds = contentBounds(blocks, frames, includeTimestamps = false)
        val scale = min((width - 96f) / bounds.width(), (height - 160f) / bounds.height()).coerceAtMost(2f)
        val offsetX = 48f - bounds.left * scale
        val offsetY = 120f - bounds.top * scale

        drawWorld(canvas, blocks, frames, emptyMap(), false, scale, offsetX, offsetY, null)
    }

    private fun drawPdfPage(
        canvas: Canvas,
        contact: TrustedContact,
        blocks: List<SharedTextBlock>,
        frames: List<HandDrawnFrame>,
        timestamps: Map<String, Long>,
        includeTimestamps: Boolean,
        tile: RectF,
        scale: Float,
        pageNumber: Int,
        pageCount: Int,
        contentLeft: Float,
        contentTop: Float,
        contentWidth: Float,
        contentHeight: Float,
    ) {
        canvas.drawColor(Color.rgb(246, 244, 238))
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 37, 34)
            textSize = 38f
            isFakeBoldText = true
        }
        val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(113, 117, 108)
            textSize = 21f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(contact.displayName, contentLeft, 72f, titlePaint)
        canvas.drawText("$pageNumber / $pageCount", contentLeft + contentWidth, 70f, pagePaint)
        canvas.save()
        canvas.clipRect(contentLeft, contentTop, contentLeft + contentWidth, contentTop + contentHeight)
        drawWorld(
            canvas,
            blocks,
            frames,
            timestamps,
            includeTimestamps,
            scale,
            contentLeft - tile.left * scale,
            contentTop - tile.top * scale,
            tile,
        )
        canvas.restore()
    }

    private fun drawWorld(
        canvas: Canvas,
        blocks: List<SharedTextBlock>,
        frames: List<HandDrawnFrame>,
        timestamps: Map<String, Long>,
        includeTimestamps: Boolean,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        visibleWorld: RectF?,
    ) {

        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(166, 173, 145)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        frames.filter { frame ->
            val bounds = frameBounds(frame)
            visibleWorld == null || visibleWorld.intersects(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }.forEach { frame ->
            if (frame.points.size > 2) {
                val path = Path()
                frame.points.forEachIndexed { index, point ->
                    val x = offsetX + point.x * scale
                    val y = offsetY + point.y * scale
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, framePaint)
            }
        }

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 42, 39); textSize = 28f * scale.coerceIn(.75f, 1.25f) }
        val timestampFormatter = if (includeTimestamps) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) else null
        blocks.filter { block ->
            visibleWorld == null || visibleWorld.intersects(
                block.x,
                block.y,
                block.x + block.width,
                block.y + renderedBlockHeight(block, includeTimestamps),
            )
        }.forEach { block ->
            val left = offsetX + block.x * scale
            val top = offsetY + block.y * scale
            val blockWidth = block.width * scale
            val blockHeight = renderedBlockHeight(block, includeTimestamps) * scale
            canvas.drawRoundRect(left, top, left + blockWidth, top + blockHeight, 22f, 22f, bubblePaint)
            canvas.save()
            canvas.clipRect(left + 18f, top + 18f, left + blockWidth - 18f, top + blockHeight - 18f)
            drawWrappedText(canvas, block.text.ifBlank { " " }, left + 24f, top + 52f, blockWidth - 48f, textPaint)
            timestamps[block.id]?.let { timestamp ->
                val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(126, 130, 120)
                    textSize = 14f * scale.coerceIn(.75f, 1.25f)
                }
                canvas.drawText(
                    timestampFormatter?.format(Date(timestamp)).orEmpty(),
                    left + 24f,
                    top + blockHeight - 18f,
                    timePaint,
                )
            }
            canvas.restore()
        }
    }

    private fun renderedBlockHeight(block: SharedTextBlock, includeTimestamp: Boolean): Float =
        estimatedBlockHeight(block) + if (includeTimestamp) 28f else 0f

    private fun contentBounds(
        blocks: List<SharedTextBlock>,
        frames: List<HandDrawnFrame>,
        includeTimestamps: Boolean,
    ): RectF {
        val leftCandidates = blocks.map { it.x } + frames.flatMap { frame -> frame.points.map { it.x } }
        val topCandidates = blocks.map { it.y } + frames.flatMap { frame -> frame.points.map { it.y } }
        val rightCandidates = blocks.map { it.x + it.width } + frames.flatMap { frame -> frame.points.map { it.x } }
        val bottomCandidates = blocks.map { it.y + renderedBlockHeight(it, includeTimestamps) } +
            frames.flatMap { frame -> frame.points.map { it.y } }
        val left = leftCandidates.minOrNull() ?: 0f
        val top = topCandidates.minOrNull() ?: 0f
        val right = rightCandidates.maxOrNull() ?: 600f
        val bottom = bottomCandidates.maxOrNull() ?: 500f
        return RectF(left - 36f, top - 36f, max(right + 36f, left + 72f), max(bottom + 36f, top + 72f))
    }

    private fun frameBounds(frame: HandDrawnFrame): RectF {
        if (frame.points.isEmpty()) return RectF()
        return RectF(
            frame.points.minOf { it.x },
            frame.points.minOf { it.y },
            frame.points.maxOf { it.x },
            frame.points.maxOf { it.y },
        )
    }

    private fun drawWrappedText(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint) {
        var lineY = y
        text.lines().forEach { paragraph ->
            var remaining = paragraph
            if (remaining.isEmpty()) lineY += paint.textSize * 1.3f
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                canvas.drawText(remaining.take(count), x, lineY, paint)
                remaining = remaining.drop(count)
                lineY += paint.textSize * 1.3f
            }
        }
    }

    private fun spaceJson(
        contact: TrustedContact,
        blocks: List<SharedTextBlock>,
        frames: List<HandDrawnFrame>,
        events: List<CanvasEvent>,
    ): JSONObject = JSONObject().apply {
        put("format", "private-canvas-space")
        put("version", 1)
        put("exportedAt", System.currentTimeMillis())
        put("spaceId", contact.spaceId)
        put("contactName", contact.displayName)
        put("blocks", JSONArray().apply {
            blocks.forEach { block -> put(JSONObject().apply {
                put("id", block.id); put("text", block.text); put("x", block.x); put("y", block.y)
                put("width", block.width); put("height", block.height); put("pinned", block.pinned)
            }) }
        })
        put("frames", JSONArray().apply {
            frames.forEach { frame -> put(JSONObject().apply {
                put("id", frame.id)
                put("points", JSONArray().apply { frame.points.forEach { point -> put(JSONArray().put(point.x).put(point.y)) } })
            }) }
        })
        put("events", JSONArray().apply {
            events.forEach { event -> put(JSONObject().apply {
                put("id", event.id); put("sequence", event.sequence); put("type", event.type.name)
                put("objectId", event.objectId); put("payload", event.payload); put("occurredAt", event.occurredAtEpochMs)
            }) }
        })
    }
}

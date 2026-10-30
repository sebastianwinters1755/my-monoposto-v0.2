package com.raceengineer.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.TimeUnit

/** Lets the reading logic look at the pixels of a captured frame. */
class BitmapGrid(
    private val bmp: Bitmap,
    override val width: Int,
    override val height: Int
) : PixelGrid {
    override fun pixel(x: Int, y: Int): Int = bmp.getPixel(x, y)
}

/** Reads text from one part of the captured frame using Google ML Kit (works offline). */
class MlKitTextReader(
    private val frame: Bitmap,
    private val fw: Int,
    private val fh: Int,
    private val recognizer: TextRecognizer
) : TextReader {

    override fun read(region: Region, scale: Float): List<Word> {
        val x0 = (region.l * fw).toInt().coerceIn(0, fw - 2)
        val y0 = (region.t * fh).toInt().coerceIn(0, fh - 2)
        val x1 = (region.r * fw).toInt().coerceIn(x0 + 1, fw)
        val y1 = (region.b * fh).toInt().coerceIn(y0 + 1, fh)

        val out = ArrayList<Word>()
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            crop = Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0)
            scaled = if (scale != 1f) {
                Bitmap.createScaledBitmap(
                    crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true
                )
            } else {
                crop
            }
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(scaled, 0)),
                10,
                TimeUnit.SECONDS
            )
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (el in line.elements) {
                        val bb = el.boundingBox ?: continue
                        val cx = (x0 + bb.centerX() / scale) / fw
                        val cy = (y0 + bb.centerY() / scale) / fh
                        out.add(Word(el.text, cx, cy))
                    }
                }
            }
        } catch (e: Exception) {
            // Leave the list as it is; the report will show "not read" for this part.
        } finally {
            if (scaled != null && scaled !== crop) scaled.recycle()
            crop?.recycle()
        }
        return out
    }
}

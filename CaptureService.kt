package com.raceengineer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs in the background while you play: captures the screen about twice a second,
 * reads it, and shows a small box at the bottom-left of the screen.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val ACTION_STOP = "com.raceengineer.app.STOP"
        const val ACTION_SNAPSHOT = "com.raceengineer.app.SNAPSHOT"
        private const val CHANNEL = "race_engineer"
        private const val NOTIF_ID = 1
    }

    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var overlayView: TextView? = null
    private var recognizer: TextRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastRun = 0L
    private var snapshotDueAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_SNAPSHOT) {
            if (projection == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val delay = intent?.getLongExtra(EXTRA_DELAY_MS, 0L) ?: 0L
            snapshotDueAt = SystemClock.elapsedRealtime() + delay
            Shared.note = "Snapshot requested. It is saved on the next reading after ${delay / 1000} s."
            return START_NOT_STICKY
        }
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        if (data == null) {
            Shared.note = "No screen permission was passed to the service."
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startCapture(code, data)
        } catch (e: Exception) {
            Shared.note = "Could not start screen capture: $e"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = wm.maximumWindowMetrics.bounds
        // The game is always landscape, so use the long side as the width.
        val width = maxOf(b.width(), b.height())
        val height = minOf(b.width(), b.height())
        val dpi = resources.displayMetrics.densityDpi

        val t = HandlerThread("capture")
        t.start()
        thread = t
        val h = Handler(t.looper)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, h)
        projection = p

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
            override fun onImageAvailable(ir: ImageReader) {
                handleImage(ir)
            }
        }, h)

        vDisplay = p.createVirtualDisplay(
            "race-engineer", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
        )

        Shared.running = true
        Shared.note = "Started. Switch to Monoposto."
        main.post { addOverlay() }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val bmp = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        return bmp
    }

    private fun handleImage(ir: ImageReader) {
        val img: Image? = try {
            ir.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) return

        // While the Race Engineer screen is in front we do not read (it would read itself).
        val now = SystemClock.elapsedRealtime()
        if (Shared.paused || now - lastRun < Cfg.INTERVAL_MS) {
            img.close()
            return
        }
        lastRun = now

        val w = img.width
        val h = img.height
        val bmp = try {
            toBitmap(img)
        } finally {
            img.close()
        }

        val t0 = SystemClock.elapsedRealtime()
        try {
            val rec = recognizer ?: return
            val snap = RaceReader(MlKitTextReader(bmp, w, h, rec)).analyze(BitmapGrid(bmp, w, h))
            val ms = SystemClock.elapsedRealtime() - t0
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val full = "Screen ${w}x${h}   reading took $ms ms   at $stamp\n" + snap.full()

            // Keep the last reading that really looks like the game.
            val looksLikeGame = snap.rows.isNotEmpty() || snap.header.lap != null || snap.wheel.size >= 3
            if (looksLikeGame) Shared.full = full

            if (snapshotDueAt != 0L && now >= snapshotDueAt) {
                snapshotDueAt = 0L
                saveSnapshot(bmp, w, h, full)
            }
            val compact = if (looksLikeGame) snap.compact() else "Monoposto HUD not found"
            main.post { overlayView?.text = compact }
        } catch (e: Exception) {
            Shared.note = "Reading error: $e"
        } finally {
            bmp.recycle()
        }
    }

    /** Saves the current frame (PNG) and its reading (text) so they can be checked later. */
    private fun saveSnapshot(bmp: Bitmap, w: Int, h: Int, report: String) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        try {
            val pic = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "RaceEngineer_$stamp.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RaceEngineer")
            }
            val picUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pic)
            if (picUri != null) {
                val exact = Bitmap.createBitmap(bmp, 0, 0, w, h)
                contentResolver.openOutputStream(picUri)?.use { out ->
                    exact.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                exact.recycle()
            }
            val txt = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "RaceEngineer_$stamp.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/RaceEngineer")
            }
            val txtUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, txt)
            if (txtUri != null) {
                contentResolver.openOutputStream(txtUri)?.use { out ->
                    out.write(report.toByteArray())
                }
            }
            Shared.note = "Snapshot saved ($stamp): picture in Pictures/RaceEngineer, text in Download/RaceEngineer."
        } catch (e: Exception) {
            Shared.note = "Could not save the snapshot: $e"
        }
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Shared.note = "Allow 'Display over other apps' for Race Engineer to see the on-screen box."
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)

        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.setBackgroundColor(0xB0000000.toInt())
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.typeface = Typeface.MONOSPACE
        tv.setPadding(12, 6, 12, 6)
        tv.maxWidth = (longSide * 0.21f).toInt()
        tv.text = "Race Engineer: starting..."

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        // Bottom-left, above the gear pedal and clear of every part of the HUD we read.
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.x = 8
        lp.y = (shortSide * Cfg.OVERLAY_BOTTOM_FRACTION).toInt()
        wm.addView(tv, lp)
        overlayView = tv
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Race Engineer", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val snapshot = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java)
                .setAction(ACTION_SNAPSHOT)
                .putExtra(EXTRA_DELAY_MS, 2000L),
            flags
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            flags
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Race Engineer is reading the screen")
            .setContentText("Tap Save snapshot to keep a picture and reading to check")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_save),
                    "Save snapshot", snapshot
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", stop
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Shared.running = false
        try {
            vDisplay?.release()
        } catch (e: Exception) {
        }
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        try {
            recognizer?.close()
        } catch (e: Exception) {
        }
        thread?.quitSafely()
        try {
            val v = overlayView
            if (v != null) (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
        overlayView = null
        projection = null
        super.onDestroy()
    }
}

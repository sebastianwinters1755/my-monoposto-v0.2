package com.raceengineer.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqCapture = 100
    private lateinit var statusView: TextView
    private lateinit var noteView: TextView
    private lateinit var outView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Race Engineer  -  test version 0.2"
        title.textSize = 22f
        title.setTypeface(title.typeface, Typeface.BOLD)
        root.addView(title)

        val help = TextView(this)
        help.text = "1) Tap START and allow screen capture (choose \"Entire screen\").\n" +
            "2) Open Monoposto and start a race.\n" +
            "3) A small box at the bottom-left of the screen shows what the app reads.\n" +
            "4) To keep a picture and its reading: pull down the notification shade and tap " +
            "\"Save snapshot\", or tap the SNAPSHOT button here and switch to the game within 8 seconds.\n" +
            "5) Come back here to see the last reading from the game. Tap STOP when finished."
        help.textSize = 14f
        help.setPadding(0, pad / 2, 0, pad / 2)
        root.addView(help)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL

        val start = Button(this)
        start.text = "START"
        start.setOnClickListener { onStart() }
        buttons.addView(start)

        val stop = Button(this)
        stop.text = "STOP"
        stop.setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            Shared.running = false
            Shared.note = "Stopped. The last reading is kept below."
        }
        buttons.addView(stop)

        val snap = Button(this)
        snap.text = "SNAPSHOT IN 8 s"
        snap.setOnClickListener {
            if (Shared.running) {
                val i = Intent(this, CaptureService::class.java)
                i.action = CaptureService.ACTION_SNAPSHOT
                i.putExtra(CaptureService.EXTRA_DELAY_MS, 8000L)
                startService(i)
                Toast.makeText(this, "Switch to Monoposto now.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Tap START first.", Toast.LENGTH_SHORT).show()
            }
        }
        buttons.addView(snap)
        root.addView(buttons)

        statusView = TextView(this)
        statusView.textSize = 16f
        statusView.setTypeface(statusView.typeface, Typeface.BOLD)
        statusView.setPadding(0, pad / 2, 0, 0)
        root.addView(statusView)

        noteView = TextView(this)
        noteView.textSize = 14f
        noteView.setPadding(0, 0, 0, pad / 2)
        root.addView(noteView)

        outView = TextView(this)
        outView.typeface = Typeface.MONOSPACE
        outView.textSize = 12f
        outView.setTextIsSelectable(true)
        root.addView(outView)

        val scroll = ScrollView(this)
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(scroll)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun onStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Turn ON 'Allow display over other apps' for Race Engineer, then come back and tap START again.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
            i.putExtra(CaptureService.EXTRA_CODE, resultCode)
            i.putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
            Toast.makeText(this, "Started. Now open Monoposto.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Screen capture was not allowed.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        statusView.text = if (Shared.running) "STATUS: RUNNING (switch to Monoposto)" else "STATUS: stopped"
        noteView.text = Shared.note
        outView.text = Shared.full
    }

    override fun onResume() {
        super.onResume()
        // This screen is in front: the service must not read it.
        Shared.paused = true
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        Shared.paused = false
        ui.removeCallbacks(tick)
    }
}

package com.remotephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Foreground service: captures the screen (only after the user approved the system
 * prompt), streams JPEG frames to the relay while a viewer is connected, and passes
 * incoming input events to the accessibility service. A persistent notification is
 * always shown while it runs.
 */
class ScreenShareService : Service() {

    companion object {
        const val ACTION_START = "com.remotephone.START"
        const val ACTION_STOP = "com.remotephone.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_SERVER = "server"
        const val EXTRA_CODE = "code"
        private const val CHANNEL_ID = "remotephone"
        private const val NOTIF_ID = 1
        private const val MAX_SIDE = 1280      // longest side of streamed frames
        private const val MIN_FRAME_GAP_MS = 100L  // ~10 fps
        private const val JPEG_QUALITY = 55
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile private var viewerConnected = false
    @Volatile private var running = false
    private var lastFrame = 0L
    private var serverUrl = ""
    private var code = ""
    private var frameW = 0
    private var frameH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        serverUrl = intent.getStringExtra(EXTRA_SERVER) ?: return START_NOT_STICKY
        code = intent.getStringExtra(EXTRA_CODE) ?: return START_NOT_STICKY
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent = intent.getParcelableExtra(EXTRA_DATA) ?: return START_NOT_STICKY

        startForegroundCompat()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data)
        thread = HandlerThread("capture").also { it.start() }
        handler = Handler(thread!!.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        projection = mp

        running = true
        startCapture(mp)
        connectSocket()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RemotePhone is sharing this screen")
            .setContentText("Code $code. Tap Stop to end the session.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startCapture(mp: MediaProjection) {
        val metrics = resources.displayMetrics
        val scale = MAX_SIDE.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels)
        frameW = (metrics.widthPixels * minOf(scale, 1f)).toInt() and 1.inv()
        frameH = (metrics.heightPixels * minOf(scale, 1f)).toInt() and 1.inv()

        imageReader = ImageReader.newInstance(frameW, frameH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "RemotePhone", frameW, frameH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (!viewerConnected || now - lastFrame < MIN_FRAME_GAP_MS) return@setOnImageAvailableListener
                val socket = ws ?: return@setOnImageAvailableListener
                if (socket.queueSize() > 1_000_000) return@setOnImageAvailableListener // viewer too slow
                lastFrame = now

                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * frameW
                val bmp = Bitmap.createBitmap(frameW + rowPadding / pixelStride, frameH, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, frameW, frameH)
                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                bmp.recycle(); cropped.recycle()
                socket.send(out.toByteArray().toByteString())
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun connectSocket() {
        if (!running) return
        val url = "${serverUrl.trimEnd('/')}/ws?role=phone&code=$code"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: Exception) { return }
                if (msg.optString("type") == "viewer") {
                    viewerConnected = msg.optBoolean("connected")
                    if (viewerConnected) {
                        webSocket.send(JSONObject().put("type", "info").put("w", frameW).put("h", frameH).toString())
                    }
                } else {
                    RemoteAccessibilityService.instance?.handle(msg)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { retry() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                viewerConnected = false
                if (code != 1000) retry()
            }
        })
    }

    private fun retry() {
        viewerConnected = false
        if (running) handler?.postDelayed({ connectSocket() }, 3000)
    }

    override fun onDestroy() {
        running = false
        viewerConnected = false
        ws?.close(1000, "stopped")
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        thread?.quitSafely()
        super.onDestroy()
    }
}

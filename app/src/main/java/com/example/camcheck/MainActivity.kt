package com.example.camcheck

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 電視鏡頭檢查：列出系統看得到的鏡頭（Camera2 / 舊版 API / USB / /dev/video），
 * 並可開啟即時預覽、顯示收到的幀數。結果同時印到 logcat（標籤 CamCheck）。
 */
class MainActivity : Activity() {

    private val tag = "CamCheck"
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var tex: TextureView
    private lateinit var cm: CameraManager

    private val thread = HandlerThread("camcheck").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var selected = 0
    private var frames = 0
    private var ids: List<String> = emptyList()
    private val events = ArrayList<String>()

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) { addEvent("鏡頭 $cameraId 可用") }
        override fun onCameraUnavailable(cameraId: String) { addEvent("鏡頭 $cameraId 被占用/消失") }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val what = if (i?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) "插入" else "拔除"
            addEvent("USB 裝置$what")
        }
    }

    private val hasPerm: Boolean
        get() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        fun btn(label: String, f: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { f() }
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(btn("重新整理") { refresh() })
        bar.addView(btn("切換鏡頭") { nextCamera() })
        bar.addView(btn("開 / 關預覽") { togglePreview() })

        info = TextView(this).apply { textSize = 14f; setPadding(24, 12, 24, 12) }
        val scroll = ScrollView(this).apply {
            isFocusable = true
            addView(info)
        }

        tex = TextureView(this)
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                frames++
                if (frames % 30 == 0) status.text = "預覽中，已收到 $frames 幀"
            }
        }
        status = TextView(this).apply { textSize = 16f; text = "尚未開啟預覽"; setPadding(12, 12, 12, 12) }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tex, LinearLayout.LayoutParams(640, 480))
            addView(status)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(right, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        try { cm.registerAvailabilityCallback(availability, handler) } catch (_: Exception) {}
        if (!hasPerm) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, f)
        refresh()
    }

    override fun onPause() {
        stopPreview()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        try { cm.unregisterAvailabilityCallback(availability) } catch (_: Exception) {}
        thread.quitSafely()
        super.onDestroy()
    }

    // ================= 報告 =================

    private fun addEvent(s: String) {
        val t = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        runOnUiThread {
            events.add("$t $s")
            if (events.size > 12) events.removeAt(0)
            refresh()
        }
    }

    private fun refresh() {
        val text = buildReport()
        info.text = text
        Log.i(tag, text)
    }

    private fun facingName(v: Int?) = when (v) {
        CameraCharacteristics.LENS_FACING_FRONT -> "前"
        CameraCharacteristics.LENS_FACING_BACK -> "後"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接(EXTERNAL)"
        else -> "未知($v)"
    }

    private fun levelName(v: Int?) = when (v) {
        0 -> "LIMITED"
        1 -> "FULL"
        2 -> "LEGACY"
        3 -> "LEVEL_3"
        4 -> "EXTERNAL"
        else -> "未知($v)"
    }

    private fun buildReport(): String {
        val sb = StringBuilder()
        val pm = packageManager
        sb.append("【裝置】${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("【相機權限】${if (hasPerm) "✅ 已授權" else "❌ 未授權（按「重新整理」會再要求；或 adb shell pm grant com.example.camcheck android.permission.CAMERA）"}\n\n")

        sb.append("【系統功能宣告】\n")
        fun feat(name: String, label: String) {
            sb.append("  $label：${if (pm.hasSystemFeature(name)) "有" else "無"}\n")
        }
        feat(PackageManager.FEATURE_CAMERA_ANY, "任何鏡頭")
        feat(PackageManager.FEATURE_CAMERA_FRONT, "前鏡頭")
        feat(PackageManager.FEATURE_CAMERA_EXTERNAL, "外接鏡頭")
        feat(PackageManager.FEATURE_LEANBACK, "電視介面(leanback)")
        feat(PackageManager.FEATURE_TOUCHSCREEN, "觸控螢幕")

        sb.append("\n【Camera2 鏡頭清單】\n")
        ids = try { cm.cameraIdList.toList() } catch (e: CameraAccessException) {
            sb.append("  讀取失敗：$e\n"); emptyList()
        } catch (e: Exception) {
            sb.append("  讀取失敗：$e\n"); emptyList()
        }
        if (ids.isEmpty()) sb.append("  （沒有任何鏡頭）\n")
        for ((n, id) in ids.withIndex()) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
                val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
                val has640 = yuv.any { it.width == 640 && it.height == 480 }
                sb.append("  ${if (n == selected % ids.size) "▶" else "  "}ID $id｜朝向 ${facingName(c.get(CameraCharacteristics.LENS_FACING))}")
                sb.append("｜等級 ${levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
                sb.append("｜感測器角度 ${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}°\n")
                sb.append("      預覽解析度：${sizes.take(6).joinToString(" ") { "${it.width}x${it.height}" }}\n")
                sb.append("      YUV 640x480：${if (has640) "支援" else "不支援"}\n")
            } catch (e: Exception) {
                sb.append("  ID $id 讀取失敗：$e\n")
            }
        }

        @Suppress("DEPRECATION")
        val legacy = try { Camera.getNumberOfCameras() } catch (e: Exception) { -1 }
        sb.append("\n【舊版 Camera API 鏡頭數】$legacy\n")

        sb.append("\n【USB 裝置】\n")
        var videoUsb = 0
        try {
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            if (usb.deviceList.isEmpty()) sb.append("  （沒有偵測到任何 USB 裝置）\n")
            for (d in usb.deviceList.values) {
                val classes = (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }
                val isVideo = d.deviceClass == 14 || classes.contains(14)
                if (isVideo) videoUsb++
                sb.append("  ${if (isVideo) "📷" else "•"} ${d.productName ?: d.deviceName}")
                sb.append(" (VID %04X PID %04X)".format(d.vendorId, d.productId))
                sb.append(if (isVideo) " ← 視訊裝置(UVC)\n" else "\n")
            }
        } catch (e: Exception) {
            sb.append("  讀取失敗：$e\n")
        }

        sb.append("\n【/dev/video*】")
        val vids = try {
            File("/dev").list()?.filter { it.startsWith("video") }?.sorted()
        } catch (e: Exception) { null }
        sb.append(if (vids == null) " 無法列出（系統限制）\n" else if (vids.isEmpty()) " 無\n" else " ${vids.joinToString(" ")}\n")

        sb.append("\n【判斷】")
        sb.append(
            when {
                ids.isNotEmpty() -> "系統有 ${ids.size} 顆鏡頭可用 → 按「開 / 關預覽」測試能不能出畫面。"
                videoUsb > 0 -> "USB 鏡頭有被偵測到，但系統相機框架沒有開放它 → 需要改用 UVC 函式庫直接讀取。"
                else -> "完全沒有鏡頭 → 檢查 USB 接線、換另一個 USB 孔，或確認鏡頭是 UVC 免驅動款。"
            }
        )
        sb.append("\n\n【事件紀錄】\n")
        if (events.isEmpty()) sb.append("  （插拔 USB 鏡頭會顯示在這裡）\n")
        for (e in events) sb.append("  $e\n")
        return sb.toString()
    }

    // ================= 預覽 =================

    private fun post(msg: String) {
        Log.i(tag, msg)
        runOnUiThread { status.text = msg }
    }

    private fun nextCamera() {
        if (ids.isEmpty()) { post("沒有鏡頭可切換"); return }
        selected = (selected + 1) % ids.size
        refresh()
        if (device != null) startPreview() else post("已選鏡頭 ID ${ids[selected]}")
    }

    private fun togglePreview() {
        if (device != null || session != null) { stopPreview(); post("已停止預覽") } else startPreview()
    }

    private fun startPreview() {
        if (!hasPerm) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            post("需要相機權限，請允許後再按一次")
            return
        }
        if (ids.isEmpty()) { post("系統沒有任何鏡頭可開"); return }
        stopPreview()
        val id = ids[selected % ids.size]
        frames = 0
        post("正在開啟鏡頭 $id …")
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { device = c; createSession(c, id) }
                override fun onDisconnected(c: CameraDevice) { c.close(); device = null; post("鏡頭 $id 斷線") }
                override fun onError(c: CameraDevice, error: Int) {
                    c.close(); device = null; post("鏡頭 $id 開啟錯誤，code=$error")
                }
            }, handler)
        } catch (e: Exception) {
            post("開啟失敗：$e")
        }
    }

    private fun createSession(c: CameraDevice, id: String) {
        try {
            val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val size = sizes?.minByOrNull { Math.abs(it.width - 640) + Math.abs(it.height - 480) } ?: Size(640, 480)
            val st = tex.surfaceTexture
            if (st == null) { post("預覽畫面元件尚未就緒，請再按一次"); return }
            st.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(st)
            val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            @Suppress("DEPRECATION")
            c.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(req.build(), null, handler)
                        post("預覽中：鏡頭 $id ${size.width}x${size.height}（等待畫面…）")
                    } catch (e: Exception) {
                        post("預覽失敗：$e")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    post("建立預覽失敗（onConfigureFailed）")
                }
            }, handler)
        } catch (e: Exception) {
            post("建立預覽例外：$e")
        }
    }

    private fun stopPreview() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null
        device = null
    }
}

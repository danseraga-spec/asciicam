package com.example.asciicam

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var asciiView: AsciiView
    private lateinit var flash: View
    private lateinit var focusRing: View
    private lateinit var message: TextView
    private lateinit var recLabel: TextView
    private lateinit var aspectChip: TextView
    private lateinit var densityChip: TextView
    private lateinit var settingsButton: View
    private lateinit var shutter: ShutterButton
    private lateinit var thumbnail: ImageView
    private lateinit var flipButton: View
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var sunButton: View
    private lateinit var sensitivityPanel: View
    private lateinit var sensitivitySlider: SeekBar
    private lateinit var sensitivityLabel: TextView
    private val modeTabs = arrayOfNulls<TextView>(Settings.MODE_LABELS.size)

    private val converter = AsciiConverter()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var useFront = false
    private var zoomPresets = emptyList<Float>()
    private var pinchZoom = 1f

    // Set and cleared only on analysisExecutor, so it never changes in the middle of a frame.
    private var recorder: Recorder? = null
    // UI-side recording state (main thread).
    private var recording = false
    private var recordingGif = false
    private var recordStart = 0L
    private var recordFrame: AsciiFrame? = null

    private var lastUri: Uri? = null
    private var lastMime = "image/png"
    private var pendingStorageAction: (() -> Unit)? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showMessage("Нужен доступ к камере")
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted) action?.invoke() else showMessage("Нужен доступ к памяти для сохранения")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi())
        applySettings()
        setMode(settings.mode)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val root = FrameLayout(this)

        asciiView = AsciiView(this)
        asciiView.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
        root.addView(asciiView, FrameLayout.LayoutParams(MATCH, MATCH))

        flash = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
        }
        root.addView(flash, FrameLayout.LayoutParams(MATCH, MATCH))

        focusRing = View(this).apply {
            background = oval(Color.TRANSPARENT, Color.WHITE, dp(2))
            visibility = View.INVISIBLE
        }
        root.addView(focusRing, FrameLayout.LayoutParams(dp(FOCUS_RING_DP), dp(FOCUS_RING_DP), Gravity.TOP or Gravity.START))

        // Top bar: settings | aspect, density (or REC timer) | copy.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
        }
        settingsButton = iconButton(R.drawable.ic_settings) { openSettings() }
        aspectChip = topChip { cycleAspect() }
        densityChip = topChip { cycleDensity() }
        recLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Ui.MONO_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = roundRect(Ui.RED, dp(16).toFloat())
            visibility = View.GONE
        }
        sunButton = iconButton(R.drawable.ic_sun) { toggleSensitivityPanel() }
        // Equal-weight side groups keep the chips in the middle exactly centered.
        val leftGroup = LinearLayout(this).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
            addView(sunButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        val rightGroup = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(iconButton(R.drawable.ic_copy) { copyAscii() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        topBar.addView(leftGroup, LinearLayout.LayoutParams(0, WRAP, 1f))
        topBar.addView(aspectChip, LinearLayout.LayoutParams(WRAP, dp(34)).apply { marginEnd = dp(8) })
        topBar.addView(densityChip, LinearLayout.LayoutParams(WRAP, dp(34)))
        topBar.addView(recLabel, LinearLayout.LayoutParams(WRAP, dp(34)))
        topBar.addView(rightGroup, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Ui.MONO_BOLD
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = roundRect(0xE6202020.toInt(), dp(18).toFloat())
            visibility = View.GONE
        }
        root.addView(message, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // Bottom panel: zoom presets, mode tabs, then thumbnail | shutter | flip.
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0))
        }
        sensitivityLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Ui.MONO_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        sensitivitySlider = slider(settings.sensitivity) {
            settings.sensitivity = it
            applySensitivity()
        }
        sensitivityPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = roundRect(Ui.SCRIM, dp(22).toFloat())
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_sun) }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(sensitivitySlider, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(sensitivityLabel, LinearLayout.LayoutParams(dp(44), WRAP))
            visibility = View.GONE
        }
        bottomPanel.addView(sensitivityPanel, LinearLayout.LayoutParams(MATCH, dp(44)).apply {
            leftMargin = dp(32)
            rightMargin = dp(32)
            bottomMargin = dp(12)
        })

        zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = roundRect(Ui.SCRIM, dp(23).toFloat())
            visibility = View.GONE
        }
        bottomPanel.addView(zoomRow, LinearLayout.LayoutParams(WRAP, WRAP))

        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        Settings.MODE_LABELS.forEachIndexed { mode, label ->
            val tab = TextView(this).apply {
                text = label
                typeface = Ui.MONO_BOLD
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { setMode(mode) }
                if (mode == Settings.MODE_VIDEO && Build.VERSION.SDK_INT < 23) visibility = View.GONE
            }
            modeTabs[mode] = tab
            modeRow.addView(tab)
        }
        bottomPanel.addView(modeRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = oval(0x33FFFFFF, 0x80FFFFFF.toInt(), dp(2))
            clipToOutline = true
            setOnClickListener { openLastCapture() }
        }
        shutter = ShutterButton(this).apply {
            setOnClickListener { onShutter() }
        }
        flipButton = iconButton(R.drawable.ic_flip) { flipCamera() }.apply { setPadding(dp(13), dp(13), dp(13), dp(13)) }
        controls.addView(cell(thumbnail, dp(52)))
        controls.addView(cell(shutter, dp(78)))
        controls.addView(cell(flipButton, dp(52)))
        bottomPanel.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        root.addView(bottomPanel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // Draw edge-to-edge but keep controls clear of system bars and display cutouts.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(bars.left + dp(12), bars.top + dp(10), bars.right + dp(12), dp(28))
            bottomPanel.setPadding(bars.left, dp(40), bars.right, bars.bottom + dp(20))
            (message.layoutParams as ViewGroup.MarginLayoutParams).topMargin = bars.top + dp(70)
            message.requestLayout()
            insets
        }
        updateRecordUi()
        return root
    }

    private fun cell(child: View, size: Int) = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        addView(child, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
    }

    private fun iconButton(icon: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = ripple(oval(Ui.SCRIM))
        setOnClickListener { onClick() }
    }

    private fun topChip(onClick: () -> Unit) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Ui.MONO_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = ripple(roundRect(Ui.SCRIM, dp(17).toFloat()))
        setOnClickListener { onClick() }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.animate().cancel()
        message.alpha = 0f
        message.visibility = View.VISIBLE
        message.animate().alpha(1f).setStartDelay(0).setDuration(150).withEndAction {
            message.animate().alpha(0f).setStartDelay(1600).setDuration(300)
                .withEndAction { message.visibility = View.GONE }.start()
        }.start()
    }

    private fun updateRecordUi() {
        shutter.recording = recording
        recLabel.visibility = if (recording) View.VISIBLE else View.GONE
        aspectChip.visibility = if (recording) View.GONE else View.VISIBLE
        densityChip.visibility = if (recording) View.GONE else View.VISIBLE
        settingsButton.isEnabled = !recording
        settingsButton.animate().alpha(if (recording) 0.3f else 1f).setDuration(150).start()
        thumbnail.isEnabled = !recording
        thumbnail.animate().alpha(if (recording) 0.3f else 1f).setDuration(150).start()
        modeRow.animate().alpha(if (recording) 0f else 1f).setDuration(150).start()
        modeTabs.forEach { it?.isEnabled = !recording }
    }

    private fun applySettings() {
        converter.charset = settings.charsetChars
        converter.autoContrast = settings.autoContrast
        converter.invert = settings.inverted
        asciiView.setColors(settings.foreground, settings.background)
        asciiView.columns = settings.columns
        asciiView.aspect = settings.aspectRatio
        aspectChip.text = Settings.ASPECT_LABELS[settings.aspect.coerceIn(0, Settings.ASPECT_LABELS.size - 1)]
        densityChip.text = "Aa ${settings.columns}"
        sensitivitySlider.progress = settings.sensitivity
        applySensitivity()
    }

    private fun applySensitivity() {
        converter.sensitivity = settings.sensitivity / 100f
        sensitivityLabel.text = "${settings.sensitivity}%"
    }

    private fun toggleSensitivityPanel() {
        val show = sensitivityPanel.visibility != View.VISIBLE
        sensitivityPanel.visibility = if (show) View.VISIBLE else View.GONE
        sunButton.background = ripple(oval(if (show) 0xCC000000.toInt() else Ui.SCRIM, Ui.ACCENT, if (show) dp(2) else 0))
    }

    private fun openSettings() {
        if (recording) return
        SettingsSheet(this, settings) { cameraChanged ->
            applySettings()
            if (cameraChanged) bindCamera(resetZoom = false)
        }.show()
    }

    private fun cycleAspect() {
        if (recording) return
        settings.aspect = (settings.aspect + 1) % Settings.ASPECT_LABELS.size
        applySettings()
    }

    private fun cycleDensity() {
        if (recording) return
        settings.density = (settings.density + 1) % Settings.DENSITIES.size
        applySettings()
    }

    private val availableModes: List<Int>
        get() = if (Build.VERSION.SDK_INT >= 23) listOf(Settings.MODE_PHOTO, Settings.MODE_VIDEO, Settings.MODE_GIF)
        else listOf(Settings.MODE_PHOTO, Settings.MODE_GIF)

    private fun setMode(requested: Int) {
        if (recording) return
        val mode = if (requested in availableModes) requested else Settings.MODE_PHOTO
        settings.mode = mode
        shutter.mode = mode
        modeTabs.forEachIndexed { i, tab -> tab?.setTextColor(if (i == mode) Ui.ACCENT else Ui.TEXT_DIM) }
    }

    private fun switchMode(delta: Int) {
        val modes = availableModes
        val next = modes.indexOf(settings.mode) + delta
        if (next in modes.indices) setMode(modes[next])
    }

    // ---------- Gestures: pinch to zoom, tap to focus, swipe to change mode ----------

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zs = camera?.cameraInfo?.zoomState?.value ?: return false
                pinchZoom = (pinchZoom * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(pinchZoom)
                return true
            }
        })
    }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityX) < abs(velocityY) * 1.5f || abs(velocityX) < dp(400)) return false
                switchMode(if (velocityX < 0) 1 else -1)
                return true
            }
        })
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val useCase = analysis ?: return
        val u = (x - asciiView.contentLeft) / asciiView.contentWidth
        val v = (y - asciiView.contentTop) / asciiView.contentHeight
        if (u !in 0f..1f || v !in 0f..1f) return
        val (sx, sy) = converter.pictureToSensor(u, v) ?: return
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f, useCase).createPoint(sx, sy)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) return
        cam.cameraControl.startFocusAndMetering(action)

        val half = dp(FOCUS_RING_DP) / 2f
        focusRing.animate().cancel()
        focusRing.translationX = x - half
        focusRing.translationY = y - half
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
        }.start()
    }

    // ---------- Zoom ----------

    private fun onZoomState(zs: ZoomState) {
        val presets = ArrayList<Float>()
        if (zs.minZoomRatio < 0.95f) presets.add(zs.minZoomRatio)
        presets.add(1f)
        for (p in ZOOM_STEPS) if (p <= zs.maxZoomRatio + 0.01f) presets.add(p)

        if (presets != zoomPresets) {
            zoomPresets = presets
            zoomRow.removeAllViews()
            for (p in presets) {
                zoomRow.addView(TextView(this).apply {
                    typeface = Ui.MONO_BOLD
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setOnClickListener { camera?.cameraControl?.setZoomRatio(p) }
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
        }
        zoomRow.visibility = if (presets.size > 1) View.VISIBLE else View.GONE

        // The chip of the nearest lower preset shows the exact current zoom, like stock cameras.
        val active = presets.indexOfLast { zs.zoomRatio >= it - 0.05f }.coerceAtLeast(0)
        presets.forEachIndexed { i, p ->
            val chip = zoomRow.getChildAt(i) as TextView
            if (i == active) {
                chip.text = formatZoom(zs.zoomRatio) + "×"
                chip.setTextColor(Ui.ACCENT)
                chip.background = oval(0xCC000000.toInt())
            } else {
                chip.text = formatZoom(p)
                chip.setTextColor(Color.WHITE)
                chip.background = null
            }
        }
    }

    private fun formatZoom(v: Float): String =
        if (abs(v - v.roundToInt()) < 0.05f) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

    // ---------- Camera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = try {
                future.get()
            } catch (e: Exception) {
                showMessage("Не удалось открыть камеру")
                return@addListener
            }
            bindCamera(resetZoom = true)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(resetZoom: Boolean) {
        val provider = cameraProvider ?: return
        val hasBack = hasCamera(provider, CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFront = hasCamera(provider, CameraSelector.DEFAULT_FRONT_CAMERA)
        if (!hasBack && !hasFront) {
            showMessage("Камера не найдена")
            return
        }
        if (useFront && !hasFront) useFront = false
        if (!useFront && !hasBack) useFront = true
        flipButton.visibility = if (hasBack && hasFront) View.VISIBLE else View.INVISIBLE
        converter.mirror = useFront

        // The requested size is only a hint: CameraX falls back to the closest size the device has.
        val selector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(settings.cameraSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        val newAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(displayRotation())
            .build()
        newAnalysis.setAnalyzer(analysisExecutor, ::onFrame)

        val keepZoom = if (resetZoom) null else camera?.cameraInfo?.zoomState?.value?.zoomRatio
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        val lens = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(this, lens, newAnalysis)
            camera = cam
            analysis = newAnalysis
            cam.cameraInfo.zoomState.observe(this, Observer { onZoomState(it) })
            if (keepZoom != null) cam.cameraControl.setZoomRatio(keepZoom)
        } catch (e: Exception) {
            camera = null
            showMessage("Камера недоступна")
        }
    }

    private fun hasCamera(provider: ProcessCameraProvider, selector: CameraSelector) =
        try { provider.hasCamera(selector) } catch (e: Exception) { false }

    private fun flipCamera() {
        useFront = !useFront
        bindCamera(resetZoom = true)
        flipButton.animate().rotationBy(180f).setDuration(250).start()
    }

    private fun displayRotation() = asciiView.display?.rotation ?: Surface.ROTATION_0

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        analysis?.targetRotation = displayRotation()
    }

    /** Runs on analysisExecutor. */
    private fun onFrame(image: ImageProxy) {
        val frame = try {
            val cols = asciiView.columns
            val rows = asciiView.rows
            val w = asciiView.contentWidth
            val h = asciiView.contentHeight
            if (rows == 0 || w == 0 || h == 0) return
            converter.convert(image, cols, rows, w.toFloat() / h)
        } finally {
            image.close()
        }
        asciiView.setFrame(frame)

        val rec = recorder ?: return
        try {
            rec.offer(frame)
        } catch (e: Exception) {
            recorder = null
            runOnUiThread { onRecordingFailed(rec) }
        }
    }

    // ---------- Photo, clipboard, gallery ----------

    private fun onShutter() {
        if (settings.mode == Settings.MODE_PHOTO) takePhoto() else toggleRecording()
    }

    private fun takePhoto() {
        val frame = asciiView.frame ?: return
        flash.alpha = 0.7f
        flash.animate().alpha(0f).setDuration(250).start()
        val fg = settings.foreground
        val bg = settings.background
        val width = settings.outputWidth
        withStoragePermission {
            ioExecutor.execute {
                val uri = try {
                    val bitmap = AsciiRenderer().renderBitmap(frame, width, fg, bg)
                    val saved = GallerySaver.saveImage(this, bitmap, "ascii_${timestamp()}.png")
                    bitmap.recycle()
                    saved
                } catch (e: Throwable) {
                    runOnUiThread { showMessage("Не удалось сохранить фото") }
                    return@execute
                }
                onCaptured(frame, uri, "image/png", "Фото сохранено")
            }
        }
    }

    /** Runs on ioExecutor after something was saved: update the thumbnail. */
    private fun onCaptured(frame: AsciiFrame, uri: Uri?, mime: String, text: String) {
        val thumb: Bitmap? = try {
            AsciiRenderer().renderBitmap(frame, dp(52) * 2, settings.foreground, settings.background)
        } catch (e: Throwable) {
            null
        }
        runOnUiThread {
            lastUri = uri
            lastMime = mime
            if (thumb != null) thumbnail.setImageBitmap(thumb)
            thumbnail.scaleX = 0.6f
            thumbnail.scaleY = 0.6f
            thumbnail.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            showMessage(text)
        }
    }

    private fun openLastCapture() {
        val uri = lastUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, lastMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            showMessage("Нет приложения для просмотра")
        }
    }

    private fun copyAscii() {
        val frame = asciiView.frame ?: return
        val preset = settings.copyPresetValue
        val result = CopyFormatter.format(frame, preset, settings.copyWidthValue)
        val clip = if (result.html != null) ClipData.newHtmlText("ASCII art", result.text, result.html)
        else ClipData.newPlainText("ASCII art", result.text)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        showMessage("Скопировано: ${preset.label}, ${result.cols}×${result.rows}")
    }

    // ---------- Recording ----------

    private fun toggleRecording() {
        if (recording) stopRecording() else withStoragePermission { startRecording() }
    }

    private fun startRecording() {
        if (recording) return
        val frame = asciiView.frame ?: return
        val fg = settings.foreground
        val bg = settings.background
        val renderer = AsciiRenderer()
        val gif = settings.mode == Settings.MODE_GIF
        val rec: Recorder = try {
            if (gif || Build.VERSION.SDK_INT < 23) {
                // Small text turns to mush in a GIF, so give each character ~5px but cap the file size.
                val w = maxOf(480, frame.cols * 5).coerceAtMost(settings.outputWidth * 2 / 3) / 2 * 2
                val h = renderer.heightFor(frame, w) / 2 * 2
                GifRecorder(File(cacheDir, "rec_${timestamp()}.gif"), w, h, fg, bg)
            } else {
                val aspect = renderer.heightFor(frame, 1000) / 1000f
                Mp4Recorder.create(File(cacheDir, "rec_${timestamp()}.mp4"), settings.outputWidth, aspect, fg, bg)
            }
        } catch (e: Throwable) {
            showMessage("Не удалось начать запись")
            return
        }
        // Queued after any pending stop, so an old recorder can't swallow the new one.
        analysisExecutor.execute { recorder = rec }
        recording = true
        recordingGif = gif
        recordFrame = frame
        recordStart = SystemClock.elapsedRealtime()
        updateRecordUi()
        handler.post(tick)
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        handler.removeCallbacks(tick)
        updateRecordUi()
        val thumbFrame = asciiView.frame ?: recordFrame
        if (recordingGif) showMessage("Сохранение GIF…")
        analysisExecutor.execute {
            val rec = recorder ?: return@execute
            recorder = null
            ioExecutor.execute { finishAndSave(rec, thumbFrame) }
        }
    }

    private fun finishAndSave(rec: Recorder, thumbFrame: AsciiFrame?) {
        val what = if (rec.isVideo) "Видео" else "GIF"
        val recorded = try { rec.finish() } catch (e: Throwable) { false }
        if (!recorded) {
            rec.file.delete()
            runOnUiThread { showMessage("Запись слишком короткая") }
            return
        }
        val uri = try {
            val ext = if (rec.isVideo) "mp4" else "gif"
            GallerySaver.saveFile(this, rec.file, "ascii_${timestamp()}.$ext", rec.mimeType, rec.isVideo)
        } catch (e: Throwable) {
            rec.file.delete()
            runOnUiThread { showMessage("Не удалось сохранить $what") }
            return
        }
        rec.file.delete()
        if (thumbFrame != null) onCaptured(thumbFrame, uri, rec.mimeType, "$what сохранено")
        else runOnUiThread { showMessage("$what сохранено") }
    }

    private fun onRecordingFailed(rec: Recorder) {
        if (recording) {
            recording = false
            handler.removeCallbacks(tick)
            updateRecordUi()
        }
        ioExecutor.execute {
            try { rec.finish() } catch (_: Throwable) {}
            rec.file.delete()
        }
        showMessage(if (rec.isVideo) "Ошибка видео. Попробуйте GIF" else "Ошибка записи GIF")
    }

    private val tick = object : Runnable {
        override fun run() {
            val sec = (SystemClock.elapsedRealtime() - recordStart) / 1000
            val limit = if (recordingGif) String.format(Locale.US, " / 0:%02d", GIF_MAX_SECONDS) else ""
            recLabel.text = String.format(Locale.US, "● %d:%02d%s", sec / 60, sec % 60, limit)
            if (recordingGif && sec >= GIF_MAX_SECONDS) {
                stopRecording()
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    // ---------- Helpers ----------

    /** Android 9 and older need a runtime permission to write into the gallery folders. */
    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingStorageAction = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    override fun onPause() {
        super.onPause()
        stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val GIF_MAX_SECONDS = 20
        const val FOCUS_RING_DP = 72
        val ZOOM_STEPS = floatArrayOf(2f, 5f, 10f)
    }
}

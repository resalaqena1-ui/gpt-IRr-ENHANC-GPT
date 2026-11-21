package com.irstudiocam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.ConsumerIrManager
import android.media.Image
import android.media.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), TextureView.SurfaceTextureListener {

    private data class CameraChoice(
        val logicalId: String,
        val physicalId: String?,
        val label: String
    )

    private data class WhiteBalanceChoice(val label: String, val value: Int)

    private lateinit var cameraManager: CameraManager
    private lateinit var irManager: ConsumerIrManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler()

    private lateinit var root: LinearLayout
    private lateinit var previewView: TextureView
    private lateinit var statusText: TextView
    private lateinit var isoLabel: TextView
    private lateinit var shutterLabel: TextView
    private lateinit var focusLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var delayLabel: TextView
    private lateinit var pulseLabel: TextView
    private lateinit var pulseSeek: SeekBar
    private lateinit var cameraSpinner: Spinner
    private lateinit var wbSpinner: Spinner
    private lateinit var isoSeek: SeekBar
    private lateinit var shutterSeek: SeekBar
    private lateinit var focusSeek: SeekBar
    private lateinit var zoomSeek: SeekBar
    private lateinit var delaySeek: SeekBar
    private lateinit var timingRadio: RadioGroup
    private lateinit var beforeRadio: RadioButton
    private lateinit var afterRadio: RadioButton
    private lateinit var captureButton: Button
    private lateinit var testIrButton: Button

    private val cameraChoices = mutableListOf<CameraChoice>()
    private val whiteBalanceChoices = mutableListOf<WhiteBalanceChoice>()
    private var selectedChoice: CameraChoice? = null
    private var characteristics: CameraCharacteristics? = null
    private var previewSize = Size(1280, 720)
    private var jpegSize = Size(4000, 3000)
    private var sensorMinIso = 100
    private var sensorMaxIso = 3200
    private var sensorMinExposureNs = 1_000_000L
    private var sensorMaxExposureNs = 1_000_000_000L
    private var maxFocusDiopters = 0f
    private var maxDigitalZoom = 1f
    private var manualSensorSupported = false
    private var focusSupported = false
    private var zoomRect: Rect? = null
    private var currentIso = 100
    private var currentExposureNs = 8_000_000L
    private var currentFocus = 0f
    private var currentZoom = 1f
    private var currentWbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var supportedIrFrequency = 38_000
    private var irPulseDurationMs = 9
    private var isOpeningCamera = false
    private var isClosing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        irManager = getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager
        buildInterface()
        populateCameraChoices()
        configureIrStatus()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(13, 20, 27))
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "IR STUDIO CAM"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(title)
        root.addView(label("كاميرا يدوية + تزامن فلاش IR", 13f, Color.LTGRAY))

        previewView = TextureView(this).apply {
            surfaceTextureListener = this@MainActivity
            setBackgroundColor(Color.rgb(35, 43, 51))
        }
        root.addView(previewView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
        ).apply { topMargin = dp(12) })

        statusText = label("جاري فحص الكاميرا والـ IR...", 12f, Color.rgb(140, 210, 170))
        root.addView(statusText)

        section("الكاميرا والعدسة")
        cameraSpinner = spinner()
        root.addView(cameraSpinner)
        cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val choice = cameraChoices.getOrNull(position) ?: return
                if (choice != selectedChoice) {
                    selectedChoice = choice
                    if (previewView.isAvailable) reopenCamera()
                }
            }
        }

        section("إعدادات التعريض اليدوي")
        isoLabel = label("ISO: 100", 14f, Color.WHITE)
        root.addView(isoLabel)
        isoSeek = seekBar(1000)
        root.addView(isoSeek)
        isoSeek.setOnSeekBarChangeListener(listener { progress ->
            currentIso = mapProgress(progress, sensorMinIso, sensorMaxIso)
            isoLabel.text = "ISO: $currentIso"
            updatePreview()
        })

        shutterLabel = label("Shutter: 1/125 s", 14f, Color.WHITE)
        root.addView(shutterLabel)
        shutterSeek = seekBar(1000)
        root.addView(shutterSeek)
        shutterSeek.setOnSeekBarChangeListener(listener { progress ->
            currentExposureNs = logMap(progress, sensorMinExposureNs, sensorMaxExposureNs)
            shutterLabel.text = "Shutter: ${formatExposure(currentExposureNs)}"
            updatePreview()
        })

        focusLabel = label("Manual Focus: Infinity", 14f, Color.WHITE)
        root.addView(focusLabel)
        focusSeek = seekBar(1000)
        root.addView(focusSeek)
        focusSeek.setOnSeekBarChangeListener(listener { progress ->
            currentFocus = maxFocusDiopters * progress / 1000f
            focusLabel.text = "Manual Focus: ${String.format(Locale.US, "%.2f", currentFocus)} D"
            updatePreview()
        })

        root.addView(label("White Balance", 14f, Color.WHITE))
        wbSpinner = spinner()
        root.addView(wbSpinner)
        wbSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentWbMode = whiteBalanceChoices.getOrNull(position)?.value
                    ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
                updatePreview()
            }
        }

        zoomLabel = label("Zoom: 1.0x", 14f, Color.WHITE)
        root.addView(zoomLabel)
        zoomSeek = seekBar(1000)
        root.addView(zoomSeek)
        zoomSeek.setOnSeekBarChangeListener(listener { progress ->
            currentZoom = 1f + (maxDigitalZoom - 1f) * progress / 1000f
            zoomLabel.text = "Zoom: ${String.format(Locale.US, "%.1f", currentZoom)}x"
            updatePreview()
        })

        section("تزامن الفلاش Godox")
        root.addView(label("ضع الفلاش على S1 أو S2 ووجّه أعلى الهاتف ناحية حساس الفلاش.", 12f, Color.LTGRAY))
        timingRadio = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        beforeRadio = RadioButton(this).apply {
            text = "إشارة IR قبل طلب التصوير"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        afterRadio = RadioButton(this).apply {
            text = "طلب التصوير ثم إشارة IR"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        timingRadio.addView(beforeRadio)
        timingRadio.addView(afterRadio)
        beforeRadio.isChecked = true
        root.addView(timingRadio)

        delayLabel = label("التأخير: 50 ms", 14f, Color.WHITE)
        root.addView(delayLabel)
        delaySeek = seekBar(1000).apply { progress = 50 }
        root.addView(delaySeek)
        delaySeek.setOnSeekBarChangeListener(listener { progress ->
            delayLabel.text = "التأخير: $progress ms"
        })
        pulseLabel = label("مدة نبضة IR: 9 ms", 13f, Color.WHITE)
        root.addView(pulseLabel)
        pulseSeek = seekBar(19).apply { progress = 8 }
        root.addView(pulseSeek)
        pulseSeek.setOnSeekBarChangeListener(listener { progress ->
            irPulseDurationMs = progress + 1
            pulseLabel.text = "مدة نبضة IR: $irPulseDurationMs ms"
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        testIrButton = Button(this).apply {
            text = "اختبار IR"
            setOnClickListener { testIr() }
        }
        captureButton = Button(this).apply {
            text = "التقاط صورة + تشغيل الفلاش"
            setOnClickListener { takePhotoWithFlash() }
        }
        buttons.addView(testIrButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        buttons.addView(captureButton, LinearLayout.LayoutParams(0, dp(52), 1.5f).apply { marginStart = dp(6) })
        root.addView(buttons)

        root.addView(label(
            "ملاحظة: قيمة التأخير تُقاس بين إرسال أمر IR وطلب التصوير في التطبيق، وليست قياسًا مباشرًا لزمن فتح حساس الكاميرا. اضبطها بالتجربة للحصول على أفضل تزامن.",
            11f, Color.rgb(220, 185, 110)
        ))
    }

    private fun configureIrStatus() {
        val hasEmitter = try { irManager.hasIrEmitter() } catch (_: Exception) { false }
        if (!hasEmitter) {
            statusText.text = "لم يتم اكتشاف IR Blaster في النظام."
            testIrButton.isEnabled = false
            captureButton.isEnabled = false
            return
        }
        val ranges = try { irManager.carrierFrequencies } catch (_: Exception) { null }
        if (!ranges.isNullOrEmpty()) {
            val all = ranges.map { it.minFrequency..it.maxFrequency }
            val range38 = all.firstOrNull { 38_000 in it }
            supportedIrFrequency = if (range38 != null) 38_000 else {
                ranges.minByOrNull { range ->
                    when {
                        38_000 < range.minFrequency -> range.minFrequency - 38_000
                        38_000 > range.maxFrequency -> 38_000 - range.maxFrequency
                        else -> 0
                    }
                }?.let { ((it.minFrequency + it.maxFrequency) / 2) } ?: 38_000
            }
        }
        statusText.text = "IR جاهز • التردد المستخدم: ${supportedIrFrequency / 1000} kHz"
    }

    private fun populateCameraChoices() {
        cameraChoices.clear()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraChoices.add(CameraChoice(id, null, "الكاميرا الخلفية • $id"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val physical = chars.physicalCameraIds.toList().sorted()
                        physical.forEachIndexed { index, physicalId ->
                            cameraChoices.add(CameraChoice(id, physicalId, "مسار عدسة ${index + 1} • $physicalId"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            statusText.text = "خطأ في قراءة الكاميرات: ${e.message}"
        }
        if (cameraChoices.isEmpty()) {
            statusText.text = "لم يتم العثور على كاميرا خلفية متاحة عبر Camera2."
            return
        }
        selectedChoice = cameraChoices.first()
        cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            cameraChoices.map { it.label })
    }

    private fun openSelectedCamera() {
        if (!previewView.isAvailable || isOpeningCamera) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val choice = selectedChoice ?: return
        isOpeningCamera = true
        try {
            characteristics = cameraManager.getCameraCharacteristics(choice.logicalId)
            configureCharacteristics(characteristics!!)
            val map = characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("Camera2 output map unavailable")
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            if (previewSizes.isNotEmpty()) {
                previewSize = previewSizes.filter { it.width <= 1920 && it.height <= 1080 }
                    .maxByOrNull { it.width * it.height } ?: previewSizes.first()
            }
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            if (jpegSizes.isNotEmpty()) {
                val practicalSizes = jpegSizes.filter { it.width.toLong() * it.height.toLong() <= 16_000_000L }
                jpegSize = (practicalSizes.ifEmpty { jpegSizes }).maxByOrNull { it.width * it.height }!!
            }
            val texture = previewView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            imageReader?.close()
            imageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                saveImage(image)
            }, backgroundHandler)
            cameraManager.openCamera(choice.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    isOpeningCamera = false
                    cameraDevice = camera
                    createPreviewSession(choice.physicalId)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    isOpeningCamera = false
                    camera.close()
                    cameraDevice = null
                    mainHandler.post { statusText.text = "الكاميرا انفصلت." }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    isOpeningCamera = false
                    camera.close()
                    cameraDevice = null
                    mainHandler.post { statusText.text = "تعذر فتح الكاميرا (خطأ $error)." }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            isOpeningCamera = false
            statusText.text = "فشل فتح الكاميرا: ${e.message}"
        }
    }

    private fun configureCharacteristics(chars: CameraCharacteristics) {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        manualSensorSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (isoRange != null) {
            sensorMinIso = isoRange.lower
            sensorMaxIso = isoRange.upper
        }
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (exposureRange != null) {
            sensorMinExposureNs = max(100_000L, exposureRange.lower)
            sensorMaxExposureNs = min(2_000_000_000L, exposureRange.upper)
            if (sensorMaxExposureNs < sensorMinExposureNs) sensorMaxExposureNs = sensorMinExposureNs
        }
        maxFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        focusSupported = maxFocusDiopters > 0f
        maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        maxDigitalZoom = max(1f, min(10f, maxDigitalZoom))
        zoomRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        currentIso = min(sensorMaxIso, max(sensorMinIso, 100))
        currentExposureNs = min(sensorMaxExposureNs, max(sensorMinExposureNs, 8_000_000L))
        currentFocus = 0f
        currentZoom = 1f
        runOnUiThread {
            isoSeek.isEnabled = manualSensorSupported
            shutterSeek.isEnabled = manualSensorSupported
            focusSeek.isEnabled = focusSupported
            isoSeek.progress = ((currentIso - sensorMinIso).toFloat() / max(1, sensorMaxIso - sensorMinIso) * 1000f).roundToInt().coerceIn(0, 1000)
            shutterSeek.progress = logProgress(currentExposureNs, sensorMinExposureNs, sensorMaxExposureNs)
            focusSeek.progress = 0
            zoomSeek.isEnabled = maxDigitalZoom > 1f
            val manualText = if (manualSensorSupported) "Manual ISO/Shutter متاح" else "الكاميرا لا تعرض تحكم Manual Sensor كامل"
            statusText.text = "$manualText • IR ${supportedIrFrequency / 1000} kHz"
            isoLabel.text = "ISO: $currentIso"
            shutterLabel.text = "Shutter: ${formatExposure(currentExposureNs)}"
            focusLabel.text = if (focusSupported) "Manual Focus: 0.00 D" else "Focus: غير متاح يدويًا لهذه العدسة"
            zoomLabel.text = "Zoom: 1.0x"
            populateWhiteBalance(chars)
        }
    }

    private fun populateWhiteBalance(chars: CameraCharacteristics) {
        whiteBalanceChoices.clear()
        val available = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
        val candidates = listOf(
            WhiteBalanceChoice("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
            WhiteBalanceChoice("Incandescent", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
            WhiteBalanceChoice("Fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
            WhiteBalanceChoice("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            WhiteBalanceChoice("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
            WhiteBalanceChoice("Shade", CameraMetadata.CONTROL_AWB_MODE_SHADE)
        )
        whiteBalanceChoices.addAll(candidates.filter { available.contains(it.value) })
        if (whiteBalanceChoices.isEmpty()) whiteBalanceChoices.add(candidates.first())
        wbSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            whiteBalanceChoices.map { it.label })
        wbSpinner.setSelection(0)
    }

    private fun createPreviewSession(physicalId: String?) {
        val device = cameraDevice ?: return
        val texture = previewView.surfaceTexture ?: return
        val previewSurface = Surface(texture)
        val readerSurface = imageReader?.surface ?: return
        try {
            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                        }
                        applyManualSettings(previewBuilder!!)
                        session.setRepeatingRequest(previewBuilder!!.build(), null, backgroundHandler)
                        mainHandler.post {
                            statusText.text = "الكاميرا جاهزة • ${jpegSize.width}×${jpegSize.height} • IR ${supportedIrFrequency / 1000} kHz"
                        }
                    } catch (e: Exception) {
                        mainHandler.post { statusText.text = "خطأ في بدء المعاينة: ${e.message}" }
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (physicalId != null) {
                        selectedChoice = CameraChoice(selectedChoice!!.logicalId, null, "الكاميرا الخلفية")
                        mainHandler.post { statusText.text = "مسار العدسة المختار غير مدعوم؛ تم الرجوع للكاميرا الرئيسية." }
                        createPreviewSession(null)
                    } else {
                        mainHandler.post { statusText.text = "فشل إعداد جلسة الكاميرا." }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputs = arrayListOf(OutputConfiguration(previewSurface), OutputConfiguration(readerSurface))
                if (physicalId != null) outputs.forEach { it.setPhysicalCameraId(physicalId) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    Executor { command -> command.run() },
                    stateCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(previewSurface, readerSurface), stateCallback, backgroundHandler)
            }
        } catch (e: Exception) {
            mainHandler.post { statusText.text = "فشل تجهيز المعاينة: ${e.message}" }
        }
    }

    private fun applyManualSettings(builder: CaptureRequest.Builder) {
        val chars = characteristics ?: return
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        if (manualSensorSupported) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        if (focusSupported) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, currentWbMode)
        applyZoom(builder, chars)
    }

    private fun applyZoom(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        val active = zoomRect ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val factor = max(1f, currentZoom)
        val width = (active.width() / factor).toInt()
        val height = (active.height() / factor).toInt()
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + width, top + height))
    }

    private fun updatePreview() {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        try {
            applyManualSettings(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (_: Exception) {
            // The UI remains usable; the status line will show camera errors elsewhere.
        }
    }

    private fun testIr() {
        if (!irManager.hasIrEmitter()) {
            toast("لا يوجد IR Blaster متاح.")
            return
        }
        backgroundHandler?.post {
            try {
                transmitIrBurst()
                mainHandler.post { toast("تم إرسال نبضة IR. راقب الفلاش.") }
            } catch (e: Exception) {
                mainHandler.post { toast("فشل إرسال IR: ${e.message}") }
            }
        }
    }

    private fun takePhotoWithFlash() {
        if (captureSession == null || imageReader == null) {
            toast("الكاميرا لم تجهز بعد.")
            return
        }
        captureButton.isEnabled = false
        val delay = delaySeek.progress.toLong()
        val irBefore = beforeRadio.isChecked
        backgroundHandler?.post {
            try {
                if (irBefore) {
                    transmitIrBurst()
                    if (delay > 0) Thread.sleep(delay)
                    captureStill()
                } else {
                    captureStill()
                    if (delay > 0) Thread.sleep(delay)
                    transmitIrBurst()
                }
            } catch (e: Exception) {
                mainHandler.post { statusText.text = "خطأ أثناء التصوير/إرسال IR: ${e.message}" }
            } finally {
                mainHandler.post { captureButton.isEnabled = true }
            }
        }
    }

    private fun captureStill() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
        }
        applyManualSettings(builder)
        builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult
            ) {
                mainHandler.post { statusText.text = "تم التقاط الصورة • جاري حفظها..." }
            }
        }, backgroundHandler)
    }

    private fun transmitIrBurst() {
        // Generic modulated IR burst. The pulse width can be tuned if the optical sensor needs more/less IR energy.
        // Frequency is selected from the handset's advertised range, preferring 38 kHz.
        val pattern = intArrayOf(irPulseDurationMs * 1_000, 4_500)
        irManager.transmit(supportedIrFrequency, pattern)
    }

    private fun jpegOrientation(): Int {
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rotation = windowManager.defaultDisplay.rotation
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - deviceDegrees + 360) % 360
    }

    private fun saveImage(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "IRStudio_$timestamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/IRStudioCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Cannot open output stream")
            val completed = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            contentResolver.update(uri, completed, null, null)
            mainHandler.post { statusText.text = "تم حفظ الصورة في DCIM/IRStudioCam" }
        } catch (e: Exception) {
            mainHandler.post { statusText.text = "فشل حفظ الصورة: ${e.message}" }
        }
    }

    private fun reopenCamera() {
        closeCamera()
        if (previewView.isAvailable) openSelectedCamera()
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        previewBuilder = null
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("IRStudioCamera").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        backgroundThread = null
        backgroundHandler = null
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (previewView.isAvailable) openSelectedCamera()
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (previewView.isAvailable) openSelectedCamera()
        } else if (requestCode == REQUEST_CAMERA) {
            statusText.text = "لازم تسمح للتطبيق باستخدام الكاميرا."
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (backgroundThread == null) startBackgroundThread()
        openSelectedCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun listener(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) action(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun logProgress(value: Long, minValue: Long, maxValue: Long): Int {
        if (maxValue <= minValue || value <= minValue) return 0
        if (value >= maxValue) return 1000
        val fraction = ln(value.toDouble() / minValue.toDouble()) / ln(maxValue.toDouble() / minValue.toDouble())
        return (fraction * 1000.0).roundToInt().coerceIn(0, 1000)
    }

    private fun logMap(progress: Int, minValue: Long, maxValue: Long): Long {
        if (maxValue <= minValue) return minValue
        val fraction = progress / 1000.0
        return (minValue * exp(ln(maxValue.toDouble() / minValue.toDouble()) * fraction)).toLong()
            .coerceIn(minValue, maxValue)
    }

    private fun mapProgress(progress: Int, minValue: Int, maxValue: Int): Int {
        if (maxValue <= minValue) return minValue
        return minValue + ((maxValue - minValue) * progress / 1000f).roundToInt()
    }

    private fun formatExposure(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) "${String.format(Locale.US, "%.2f", seconds)} s"
        else "1/${(1.0 / seconds).roundToInt()} s"
    }

    private fun spinner(): Spinner = Spinner(this).apply {
        setBackgroundColor(Color.rgb(35, 45, 54))
    }

    private fun seekBar(maximum: Int): SeekBar = SeekBar(this).apply {
        max = maximum
        progress = 0
    }

    private fun section(text: String) {
        val view = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.rgb(100, 185, 235))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(5))
        }
        root.addView(view)
    }

    private fun label(text: String, size: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(3))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_CAMERA = 1001
    }
}

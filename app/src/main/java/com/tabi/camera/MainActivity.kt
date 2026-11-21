package com.tabi.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var cameraPane: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var frozen: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var resultScroll: ScrollView
    private lateinit var results: LinearLayout
    private lateinit var btnShutter: Button
    private lateinit var btnGallery: Button
    private lateinit var btnFlash: Button
    private lateinit var web: WebView
    private lateinit var tabCamera: Button
    private lateinit var tabTools: Button

    // 일본어 글자 인식 (모델이 앱 안에 들어 있어 오프라인 동작)
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    // 일본어 → 한국어, 한국어 → 일본어 (모델은 처음 한 번 내려받은 뒤 오프라인)
    private val jaKo: Translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )
    }
    private val koJa: Translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(TranslateLanguage.JAPANESE)
                .build()
        )
    }

    private val cache = LruCache<String, String>(600)
    private val pending = HashSet<String>()
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var live = true
    @Volatile private var toolsShown = false
    @Volatile private var busy = false
    private var lastTs = 0L
    private var camera: Camera? = null
    private var torchOn = false
    private var modelReady = false
    private var modelError = false

    private var liveBlocks: List<Pair<String, Rect>> = emptyList()
    private var liveW = 1
    private var liveH = 1
    private var stillBlocks: List<Pair<String, RectF>> = emptyList()
    private var frozenBmp: Bitmap? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val jaChars = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]")
    private val letters = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}A-Za-z]")

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera()
            else setStatus("카메라 권한이 필요해요. 설정 > 애플리케이션 > 여행 번역 카메라 > 권한에서 카메라를 허용해 주세요.")
        }

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) translateUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPane = findViewById(R.id.cameraPane)
        previewView = findViewById(R.id.preview)
        frozen = findViewById(R.id.frozen)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        resultScroll = findViewById(R.id.resultScroll)
        results = findViewById(R.id.results)
        btnShutter = findViewById(R.id.btnShutter)
        btnGallery = findViewById(R.id.btnGallery)
        btnFlash = findViewById(R.id.btnFlash)
        web = findViewById(R.id.web)
        tabCamera = findViewById(R.id.tabCamera)
        tabTools = findViewById(R.id.tabTools)

        btnShutter.setOnClickListener { if (live) capture() else resumeLive() }
        btnGallery.setOnClickListener { pickLauncher.launch("image/*") }
        btnFlash.setOnClickListener { if (live) toggleTorch() else saveStill() }
        frozen.setOnClickListener {
            if (stillBlocks.isNotEmpty()) {
                resultScroll.visibility =
                    if (resultScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        status.setOnClickListener { if (modelError) prepareModels() }
        tabCamera.setOnClickListener { showTools(false) }
        tabTools.setOnClickListener { showTools(true) }
        showTools(false)

        setupWeb()

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.JAPANESE)
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    toolsShown -> showTools(false)
                    !live -> resumeLive()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        prepareModels()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------------- 번역 모델 ----------------

    private fun prepareModels() {
        modelError = false
        setStatus("번역 모델 준비 중… 처음 한 번만 인터넷이 필요해요 (약 60MB, Wi-Fi 권장)")
        val cond = DownloadConditions.Builder().build()
        jaKo.downloadModelIfNeeded(cond)
            .continueWithTask { koJa.downloadModelIfNeeded(cond) }
            .addOnSuccessListener {
                modelReady = true
                setStatus(if (live) "일본어 글자에 카메라를 비춰 보세요. 이제 인터넷 없이도 돼요." else "")
                renderLive()
                retranslateStill()
            }
            .addOnFailureListener {
                modelError = true
                setStatus("번역 모델을 받지 못했어요. 인터넷에 연결한 뒤 여기를 눌러 다시 시도하세요.")
            }
    }

    private fun tr(src: String, done: () -> Unit) {
        if (cache.get(src) != null) return
        if (!modelReady || !pending.add(src)) return
        jaKo.translate(src)
            .addOnSuccessListener { k ->
                cache.put(src, k)
                pending.remove(src)
                done()
            }
            .addOnFailureListener { pending.remove(src) }
    }

    private fun blocksOf(text: Text): List<Pair<String, Rect>> =
        text.textBlocks.mapNotNull { b ->
            val box = b.boundingBox ?: return@mapNotNull null
            val sep = if (jaChars.containsMatchIn(b.text)) "" else " "
            val s = b.lines.joinToString(sep) { it.text }.trim()
            if (s.isEmpty() || !letters.containsMatchIn(s)) null else s to box
        }.take(30)

    // ---------------- 실시간 카메라 ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
                val preview = Preview.Builder().setResolutionSelector(selector).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                setStatus("카메라를 켜지 못했어요: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val media = proxy.image
        if (!live || toolsShown || busy || media == null || now - lastTs < 600) {
            proxy.close()
            return
        }
        busy = true
        lastTs = now
        val rot = proxy.imageInfo.rotationDegrees
        val w = if (rot % 180 == 0) proxy.width else proxy.height
        val h = if (rot % 180 == 0) proxy.height else proxy.width
        recognizer.process(InputImage.fromMediaImage(media, rot))
            .addOnSuccessListener { text ->
                if (live) {
                    liveW = w
                    liveH = h
                    liveBlocks = blocksOf(text)
                    liveBlocks.forEach { tr(it.first) { renderLive() } }
                    renderLive()
                }
            }
            .addOnCompleteListener {
                proxy.close()
                busy = false
            }
    }

    private fun renderLive() {
        if (!live) return
        val vw = overlay.width.toFloat()
        val vh = overlay.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val s = max(vw / liveW, vh / liveH)
        val dx = (vw - liveW * s) / 2f
        val dy = (vh - liveH * s) / 2f
        overlay.setLabels(liveBlocks.mapNotNull { (src, r) ->
            cache.get(src)?.let {
                OverlayView.Label(
                    RectF(r.left * s + dx, r.top * s + dy, r.right * s + dx, r.bottom * s + dy), it
                )
            }
        })
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return toast("이 기기는 플래시가 없어요")
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btnFlash.text = if (torchOn) "플래시 끄기" else "플래시"
    }

    // ---------------- 사진 번역 ----------------

    private fun capture() {
        val b = previewView.bitmap ?: return toast("화면을 가져오지 못했어요. 다시 눌러 주세요.")
        processStill(b, b, 1f, 0f, 0f)
    }

    private fun translateUri(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val raw = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return toast("사진을 열 수 없어요")
            val rot = contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            val img = if (rot != 0) {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            } else raw

            val vw = cameraPane.width
            val vh = cameraPane.height
            if (vw == 0 || vh == 0) return
            val display = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            val c = Canvas(display)
            c.drawColor(Color.BLACK)
            val s = min(vw.toFloat() / img.width, vh.toFloat() / img.height)
            val dw = img.width * s
            val dh = img.height * s
            val ox = (vw - dw) / 2f
            val oy = (vh - dh) / 2f
            c.drawBitmap(img, null, RectF(ox, oy, ox + dw, oy + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            // 글자 인식은 원본 해상도로, 결과 좌표만 화면에 맞춰 줄임
            processStill(display, img, s, ox, oy)
        } catch (e: Exception) {
            toast("사진을 열 수 없어요")
        }
    }

    private fun processStill(display: Bitmap, source: Bitmap, scale: Float, ox: Float, oy: Float) {
        live = false
        if (torchOn) toggleTorch()
        frozenBmp = display
        frozen.setImageBitmap(display)
        frozen.visibility = View.VISIBLE
        stillBlocks = emptyList()
        overlay.setLabels(emptyList())
        results.removeAllViews()
        resultScroll.visibility = View.GONE
        btnShutter.text = "다시 비추기"
        btnFlash.text = "저장"
        setStatus("글자를 읽는 중…")

        recognizer.process(InputImage.fromBitmap(source, 0))
            .addOnSuccessListener { t ->
                if (live) return@addOnSuccessListener
                stillBlocks = blocksOf(t).map { (s, r) ->
                    s to RectF(r.left * scale + ox, r.top * scale + oy, r.right * scale + ox, r.bottom * scale + oy)
                }
                setStatus(
                    when {
                        stillBlocks.isEmpty() -> "글자를 찾지 못했어요. 더 가까이, 밝은 곳에서 찍어 보세요."
                        !modelReady -> "번역 모델이 아직 없어요. 인터넷에 연결하면 바로 번역돼요."
                        else -> "목록을 누르면 일본어 발음을 들려줘요. 사진을 누르면 목록을 숨겨요."
                    }
                )
                retranslateStill()
            }
            .addOnFailureListener { setStatus("글자 인식에 실패했어요. 다시 찍어 주세요.") }
    }

    private fun retranslateStill() {
        if (live) return
        stillBlocks.forEach { tr(it.first) { renderStill() } }
        renderStill()
    }

    private fun renderStill() {
        if (live) return
        overlay.setLabels(stillBlocks.mapNotNull { (s, r) ->
            cache.get(s)?.let { OverlayView.Label(RectF(r), it) }
        })
        results.removeAllViews()
        for ((s, _) in stillBlocks) {
            val tv = TextView(this).apply {
                text = "$s\n→ ${cache.get(s) ?: "번역 중…"}"
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundResource(R.drawable.row_bg)
                setOnClickListener { speakJa(s) }
            }
            results.addView(
                tv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(6) }
            )
        }
        resultScroll.visibility = if (stillBlocks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun resumeLive() {
        live = true
        frozen.visibility = View.GONE
        frozen.setImageDrawable(null)
        frozenBmp = null
        stillBlocks = emptyList()
        results.removeAllViews()
        resultScroll.visibility = View.GONE
        overlay.setLabels(emptyList())
        btnShutter.text = "촬영"
        btnFlash.text = "플래시"
        setStatus(if (modelReady) "" else "번역 모델 준비 중…")
    }

    private fun saveStill() {
        val b = frozenBmp ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return toast("Android 10 이상에서 저장할 수 있어요")
        try {
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(b, 0f, 0f, null)
            overlay.draw(c)
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "tabi_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TabiCamera")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return toast("저장하지 못했어요")
            contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            toast("사진첩에 저장했어요 (Pictures/TabiCamera)")
        } catch (e: Exception) {
            toast("저장하지 못했어요")
        }
    }

    // ---------------- 여행 도구 (오프라인 웹 페이지) ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return when (u.scheme) {
                    "tel" -> { open(Intent(Intent.ACTION_DIAL, u)); true }
                    "http", "https" -> { open(Intent(Intent.ACTION_VIEW, u)); true }
                    else -> false
                }
            }
        }
        web.loadUrl("file:///android_asset/tools.html")
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast("열 수 없어요") }
    }

    inner class Bridge {
        @JavascriptInterface
        fun speak(text: String) { runOnUiThread { speakJa(text) } }

        @JavascriptInterface
        fun canSpeak(): Boolean = true

        @JavascriptInterface
        fun translate(text: String, id: String) {
            runOnUiThread {
                koJa.translate(text)
                    .addOnSuccessListener { r ->
                        js("window.__trDone(${JSONObject.quote(id)},${JSONObject.quote(r)},null)")
                    }
                    .addOnFailureListener { e ->
                        js("window.__trDone(${JSONObject.quote(id)},null,${JSONObject.quote(e.message ?: "error")})")
                    }
            }
        }
    }

    private fun js(s: String) = web.evaluateJavascript(s, null)

    private fun showTools(on: Boolean) {
        toolsShown = on
        if (on && torchOn) toggleTorch()
        cameraPane.visibility = if (on) View.GONE else View.VISIBLE
        web.visibility = if (on) View.VISIBLE else View.GONE
        tabCamera.alpha = if (on) 0.55f else 1f
        tabTools.alpha = if (on) 1f else 0.55f
    }

    // ---------------- 공통 ----------------

    private fun speakJa(t: String) {
        if (!ttsReady) return toast("일본어 음성이 없어요. 설정 > 텍스트 음성 변환에서 일본어를 설치해 주세요.")
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "tabi")
    }

    private fun setStatus(m: String) {
        status.text = m
        status.visibility = if (m.isBlank()) View.GONE else View.VISIBLE
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        recognizer.close()
        jaKo.close()
        koJa.close()
        tts?.shutdown()
    }
}

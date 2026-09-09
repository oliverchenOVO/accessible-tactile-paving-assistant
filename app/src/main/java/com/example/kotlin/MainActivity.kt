package com.example.kotlin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.kotlin.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), YoloV8Detector.DetectorListener, TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var yoloDetector: YoloV8Detector
    private var llmHelper: LlmHelper? = null
    private var tts: TextToSpeech? = null
    private lateinit var vibrator: Vibrator
    
    private var lastLlmTimestamp = 0L
    private var lastAnalysisTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        yoloDetector = YoloV8Detector(this, "yolo_model.tflite", this)
        
        // --- 雲端 LLM 初始化 ---
        llmHelper = LlmHelper()
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 設定為繁體中文
            val result = tts?.setLanguage(Locale.TRADITIONAL_CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported, falling back to CHINESE")
                tts?.setLanguage(Locale.CHINESE)
            }
        } else {
            Log.e("TTS", "Initialization failed")
        }
    }

    private fun speak(text: String) {
        if (tts == null) return
        
        Log.d("TTS", "播報內容: $text")
        // 使用 QUEUE_FLUSH 確保最新的警示訊息最優先，但我們已在 processLlmTask 限制了頻率
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LLM_ADVICE_ID")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastAnalysisTime >= 500) {
                            val bitmap = imageProxy.toBitmap()
                            
                            // 1. 執行 YOLO 偵測
                            val detections = yoloDetector.detect(bitmap, imageProxy.imageInfo.rotationDegrees)
                            
                            // 2. 顯示偵測畫面 (畫框)
                            drawDetectionResults(detections)
                            
                            // 3. 處理震動回饋
                            handleDetectionsFeedback(detections)
                            
                            // 4. 將座標與偵測結果傳給雲端 API (Gemini)
                            processLlmTask(detections)
                            
                            lastAnalysisTime = currentTime
                        }
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("MainActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 在這裡將偵測到的座標傳給雲端 API (DeepSeek)
     */
    private fun processLlmTask(detections: List<Detection>) {
        val currentTime = System.currentTimeMillis()
        
        // 1. 如果正在說話中，先不進行新的 API 請求，避免語音重疊與過於頻繁
        if (tts?.isSpeaking == true) return

        // 2. 限制 API 調用頻率，確保使用者有足夠時間聽完建議
        if (currentTime - lastLlmTimestamp > 5000) {
            lastLlmTimestamp = currentTime
            
            lifecycleScope.launch {
                val advice = llmHelper?.generateRiskWarning(this@MainActivity, detections) ?: return@launch
                
                runOnUiThread {
                    // 3. 更新螢幕文字
                    binding.tvStatus.text = advice
                    // 4. 只有在文字有意義時才播報
                    if (advice.isNotEmpty()) {
                        speak(advice)
                    }
                }
            }
        }
    }

    private fun drawDetectionResults(detections: List<Detection>) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (binding.overlay.width <= 0 || binding.overlay.height <= 0) return@runOnUiThread

            val bitmap = Bitmap.createBitmap(binding.overlay.width, binding.overlay.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            val paint = Paint().apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 10f
            }
            
            val textPaint = Paint().apply {
                color = Color.YELLOW
                textSize = 60f
                typeface = Typeface.DEFAULT_BOLD
                style = Paint.Style.FILL
            }
            
            val textBackgroundPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
                alpha = 160
            }

            detections.forEach { detection ->
                val mappedBox = RectF(
                    detection.box.left * canvas.width / 640f,
                    detection.box.top * canvas.height / 640f,
                    detection.box.right * canvas.width / 640f,
                    detection.box.bottom * canvas.height / 640f
                )
                
                canvas.drawRect(mappedBox, paint)
                
                val label = when(detection.classId) {
                    0 -> "橫向導引磚"
                    1 -> "縱向導引磚"
                    2 -> "警告磚!"
                    else -> "ID:${detection.classId}"
                }
                val text = "$label ${(detection.confidence * 100).toInt()}%"
                
                val textBounds = Rect()
                textPaint.getTextBounds(text, 0, text.length, textBounds)
                canvas.drawRect(
                    mappedBox.left, 
                    mappedBox.top - textBounds.height() - 10, 
                    mappedBox.left + textBounds.width() + 10, 
                    mappedBox.top, 
                    textBackgroundPaint
                )
                canvas.drawText(text, mappedBox.left + 5, mappedBox.top - 5, textPaint)
            }
            
            binding.overlay.setImageBitmap(bitmap)
        }
    }

    private fun handleDetectionsFeedback(detections: List<Detection>) {
        val mostDangerous = detections.maxByOrNull { 
            if (it.classId == 2) 2 else 1 
        } ?: return

        if (mostDangerous.confidence < 0.65f) return

        when (mostDangerous.classId) {
            0, 1 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
            2 -> {
                val pattern = longArrayOf(0, 200, 100, 200)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        }
    }

    override fun onResults(results: List<String>, imageHeight: Int, imageWidth: Int) { }

    override fun onError(error: String) {
        Log.e("MainActivity", error)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "未授予相機權限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

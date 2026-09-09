package com.example.kotlin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage

data class Detection(val classId: Int, val confidence: Float, val box: RectF)

class YoloV8Detector(context: Context, modelPath: String, private val listener: DetectorListener) {
    private var interpreter: org.tensorflow.lite.InterpreterApi? = null

    init {
        try {
            val model = FileUtil.loadMappedFile(context, modelPath)
            val options = org.tensorflow.lite.InterpreterApi.Options().apply {
                setNumThreads(4)
            }
            interpreter = org.tensorflow.lite.InterpreterApi.create(model, options)
            Log.d("YoloV8Detector", "Interpreter initialized successfully")
        } catch (e: Exception) {
            Log.e("YoloV8Detector", "Error initializing interpreter: ${e.message}")
            listener.onError("Model initialization failed: ${e.message}")
        }
    }

    /**
     * 執行推論並傳回偵測到的物件列表
     */
    fun detect(bitmap: Bitmap, imageRotation: Int): List<Detection> {
        if (interpreter == null) return emptyList()

        val detections = mutableListOf<Detection>()
        try {
            // 1. 預處理影像：旋轉並調整大小
            val matrix = android.graphics.Matrix().apply {
                postRotate(imageRotation.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, 640, 640, true)
            
            val imageProcessor = ImageProcessor.Builder()
                .add(NormalizeOp(0f, 255f))
                .build()

            var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(scaledBitmap)
            tensorImage = imageProcessor.process(tensorImage)

            // 2. 準備輸出容器 (YOLOv8-seg 輸出維度為 [1, 39, 8400])
            // 0,1,2,3: 座標; 4,5,6: 類別分數; 7-38: Mask 係數
            val output = Array(1) { Array(39) { FloatArray(8400) } }

            // 3. 執行推論
            interpreter?.run(tensorImage.buffer, output)

            // 4. 解析結果
            for (i in 0 until 8400) {
                // 強制只鎖定前 3 個類別 (索引 4, 5, 6)
                val score0 = output[0][4][i] // horizontal
                val score1 = output[0][5][i] // vertical
                val score2 = output[0][6][i] // warning
                
                var maxScore = score0
                var classId = 0
                
                if (score1 > maxScore) {
                    maxScore = score1
                    classId = 1
                }
                if (score2 > maxScore) {
                    maxScore = score2
                    classId = 2
                }
                
                // 提高信心門檻至 0.65f
                if (maxScore > 0.65f) {
                    val cx = output[0][0][i]
                    val cy = output[0][1][i]
                    val w = output[0][2][i]
                    val h = output[0][3][i]
                    
                    // 確保座標在 0-640 範圍內，若是 normalized 則自動縮放
                    val x = if (cx <= 1.0f && w <= 1.0f) cx * 640 else cx
                    val y = if (cy <= 1.0f && h <= 1.0f) cy * 640 else cy
                    val width = if (w <= 1.0f) w * 640 else w
                    val height = if (h <= 1.0f) h * 640 else h

                    val rect = RectF(x - width/2, y - height/2, x + width/2, y + height/2)
                    detections.add(Detection(classId, maxScore, rect))
                }
            }
            
            // 通知 listener 偵測結果
            val topDetections = detections.sortedByDescending { it.confidence }.take(5)
            val labels = topDetections.map { 
                when(it.classId) {
                    0 -> "橫向導引磚"
                    1 -> "縱向導引磚"
                    2 -> "⚠️警告磚"
                    else -> "未知"
                }
            }.distinct()
            
            if (labels.isNotEmpty()) {
                listener.onResults(labels, 640, 640)
            }
            
            return topDetections
            
        } catch (e: Exception) {
            Log.e("YoloV8Detector", "Detection failed: ${e.message}")
        }
        return emptyList()
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(results: List<String>, imageHeight: Int, imageWidth: Int)
    }
}

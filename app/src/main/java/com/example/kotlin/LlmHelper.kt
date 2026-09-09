package com.example.kotlin

import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// --- DeepSeek API Data Structure ---
data class ChatRequest(
    val model: String = "deepseek-v4-flash", 
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

data class ChatMessage(val role: String, val content: String)

data class ChatResponse(val choices: List<Choice>)
data class Choice(val message: ChatMessage)

// --- Retrofit Interface ---
interface DeepSeekApi {
    @POST("v1/chat/completions")
    suspend fun generateContent(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): ChatResponse
}

class LlmHelper {
    // Injected from the untracked local.properties file at build time.
    // A production mobile app should call the LLM through a secured backend instead.
    private val apiKey = BuildConfig.DEEPSEEK_API_KEY
    
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.deepseek.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(DeepSeekApi::class.java)

    /**
     * 根據偵測到的物件及其座標產生建議 (呼叫 DeepSeek 雲端 API)
     */
    suspend fun generateRiskWarning(context: android.content.Context, detections: List<Detection>): String = withContext(Dispatchers.IO) {
        if (detections.isEmpty()) return@withContext "路面清空，請放心前行。"
        if (apiKey.isBlank()) return@withContext "尚未設定雲端服務，請留意：${detections.size} 個地磚辨識結果。"
        
        // 將偵測結果轉換為描述性文字
        val descriptions = detections.map { det ->
            val label = when(det.classId) {
                0 -> "橫向導引磚"
                1 -> "縱向導引磚"
                2 -> "警告磚"
                else -> "未知障礙物"
            }
            val position = getPositionDescription(det.box)
            "$label 在您的 $position"
        }.joinToString("，")

        // 檢查網路狀態
        if (!NetworkUtils.isNetworkAvailable(context)) {
            return@withContext "網路斷開，請留意：$descriptions"
        }
        
        val promptText = """
            你是一個專業的視覺障礙輔助助手。
            目前畫面上偵測到：$descriptions。
            
            請根據這些地磚的位置，提供一句非常簡短（20字內）且直觀的安全建議。
            注意：
            1. 如果有「警告磚」，告知大約還有多少公尺會遇到，並請優先提醒停下。
            2. 如果是「導引磚」，請說明它是橫向還是縱向，如果是橫向，請告知下一個要左轉還是右轉，或是兩邊都可以。
            3. 請直接輸出建議內容，不要有贅字。
            語言：繁體中文。
        """.trimIndent()
        
        return@withContext try {
            val request = ChatRequest(
                messages = listOf(
                    ChatMessage("system", "你是一個專業的視覺障礙輔助助手。"),
                    ChatMessage("user", promptText)
                )
            )
            val response = api.generateContent("Bearer $apiKey", request)
            response.choices.firstOrNull()?.message?.content ?: "注意：$descriptions，請小心行走。"
        } catch (e: retrofit2.HttpException) {
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("DeepSeek", "HTTP Error $code: $errorBody")
            "DeepSeek API 錯誤 ($code)，請檢查金鑰餘額或伺服器狀態。"
        } catch (e: java.net.UnknownHostException) {
            "網路連線失敗，請檢查您的網路是否可連外網。"
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMessage = e.message ?: "未知錯誤"
            "無法連線 DeepSeek：$errorMessage"
        }
    }

    /**
     * 將 640x640 的座標轉換為描述性方位
     */
    private fun getPositionDescription(box: RectF): String {
        val centerX = box.centerX()
        val centerY = box.centerY()
        
        val horizontal = when {
            centerX < 213 -> "左側"
            centerX > 426 -> "右側"
            else -> "中央"
        }
        
        val vertical = when {
            centerY < 213 -> "遠處"
            centerY > 426 -> "近處"
            else -> "前方"
        }
        
        return "$vertical$horizontal"
    }
}

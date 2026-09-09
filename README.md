# Accessible Tactile Paving Assistant

這是一個 Android 課程原型：使用相機即時偵測導盲磚，以畫面框選、震動、繁體中文語音及簡短的 LLM 提示，探索視覺障礙輔助介面的可行性。

> 此 App 是教學原型，不是醫療器材、導航設備或安全認證產品。辨識錯誤可能導致危險，不可依賴它進行實際行走決策。

## 功能

- Kotlin Android App 與 CameraX 即時影像分析。
- TensorFlow Lite YOLO 推論與 bounding-box 繪製。
- 橫向導引磚、縱向導引磚與警告磚三類別提示。
- 依偵測結果提供震動回饋。
- Android TextToSpeech 繁體中文播報。
- 有網路時可呼叫 DeepSeek-compatible Chat Completions API 產生短安全提示；未設 API key 時提供本機 fallback。

## 未包含的檔案

準備包刻意不包含：

- `yolo_model.tflite`：原檔約 13 MB，但訓練資料與模型再散布條件尚未確認。
- `gemma-1.1-2b-it-gpu-int4.bin`：原檔約 1.35 GB，而且目前程式並未使用它。
- `local.properties`：可能包含 Android SDK 路徑和 API key。
- `.gradle/`、`.idea/`、`app/build/` 與 APK 等建置產物。

模型放置方式見 [app/src/main/assets/README.md](app/src/main/assets/README.md)。

## 本機設定

1. 以 Android Studio 開啟倉庫。
2. 將可合法使用的相容模型命名為 `yolo_model.tflite`，放入 `app/src/main/assets/`。
3. 複製 `local.properties.example` 為不受 Git 追蹤的 `local.properties`。
4. 保留 Android Studio 產生的 `sdk.dir`，並填入自己的測試用 API key：

```properties
DEEPSEEK_API_KEY=your_key_here
```

API key 會透過 `BuildConfig` 注入。由於客戶端 App 仍可能被反編譯，正式產品不應把長期有效的服務金鑰放在 App 內，而應使用有驗證、限流與憑證保護的後端代理。

## 目前限制

- 尚未找到可公開引用的訓練資料卡與模型評估報告，因此不宣稱偵測準確率。
- 距離提示由 bounding-box 相對位置推估，不是真實公尺距離。
- LLM 文字不能取代確定性安全規則。
- 需在真實裝置上進一步測試延遲、耗電、誤報、漏報、無網路模式與無障礙體驗。

公開前請閱讀 [PUBLICATION_CHECKLIST.md](docs/PUBLICATION_CHECKLIST.md) 與 [SECURITY.md](SECURITY.md)。

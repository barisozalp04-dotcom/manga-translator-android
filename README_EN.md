# Manga Translator 📖

[中文](./README.md) | English

An Android manga translation app with local speech bubble detection and OCR, combined with an OpenAI-compatible API for translation. Translated text is rendered back onto the original image as draggable text bubbles. It also supports screen translation with a floating overlay, so you can recognize and translate manga text directly from any app or from the home screen.

Tutorial: [English Tutorial](./Tutorial/Tutorial_EN.md) | [Simplified Chinese Tutorial](./Tutorial/简中教程.md)

| Original | Translated |
|------|----------|
| ![Original](./Tutorial/FirePunch.webp) | ![Translated](./Tutorial/translated.webp) |

## Key Features ✨
- Translate Japanese, English, Korean, French, Spanish, Portuguese, German, Italian, Russian and more into Chinese, or translate Chinese into English/Russian
- Screen translation: supports floating-window translation to recognize and translate manga text from any screen
- Manga library management: create folders, import images in batch, import manga folders, and support CBZ, ZIP, and PDF import/export
- Translation pipeline: speech bubble detection + local or OpenAI-compatible API OCR + LLM translation, with both standard mode and full-text fast translation
- Reading experience: translation overlay, draggable translated bubbles, and automatic reading progress saving; new cancel button and +/- fine-tune controls, synchronized zoom between webtoon and normal reading
- Font settings: custom bubble fonts and bold style are supported; normal bubbles and floating-window bubbles share the same font configuration
- Glossary and cache: maintain `glossary.json` per folder and automatically accumulate consistent name translations
- Background translation notification: sends an audible high-priority system notification when folder/batch translation finishes; tapping it returns to the library
- Updates and logs: check for updates on launch, foreground service during translation, and in-app log viewing
- Multi-provider load balancing: configure multiple translation providers and balance requests automatically by weight
- Webtoon/long-image support: automatically detect whether a work is closer to webtoon layout and switch reading mode; cross-page bubble merging is supported in webtoon/long-image mode

## Supported Translation Languages 🌐
- Target language is determined by the app UI language:
  - Simplified Chinese UI -> Simplified Chinese
  - Traditional Chinese UI -> Traditional Chinese
  - English UI -> English
  - Russian UI -> Russian
- Source language for each folder can be set independently in the library and supports: Japanese, English, Korean, Simplified Chinese, Traditional Chinese, Chinese-English mixed, French, Spanish, Portuguese, German, Italian, Russian
- When the app UI is switched to Traditional Chinese, it will prioritize Traditional Chinese prompts

## Quick Start 🚀
1. Create a folder in the manga library and import images
2. Make sure image filenames match the reading order, such as `1.jpg`, `2.jpg`
3. In Settings > OCR Settings, choose local OCR or enter the URL, key, and model for an OpenAI-compatible OCR API
4. Return to the library, choose a folder, and tap "Translate Folder"
5. After translation finishes, tap "Start Reading" and drag bubble positions on the reader page as needed

*For full-text fast translation, it is recommended to upload and translate in batches for large folders, or increase the API timeout in Settings.*

## FAQ ❓
- Translation fails or returns empty results: make sure the API URL is the OpenAI-compatible base URL provided by the service (for example, `https://api.deepseek.com/v1` or `https://open.bigmodel.cn/api/paas/v4`); the app will auto-append `/chat/completions`. The model name must match the provider and the network must be reachable
- Translation order is incorrect: rename images first so they match the reading order
- How do I get an AI API: please search for a suitable provider based on your needs

## Community
Join the QQ group for questions and discussion: `1080302768`

## Star History
** If you like this project, please consider giving it a star **
[![Star History Chart](https://star-history.dera.page/svg?repos=jedzqer/manga-translator-android&type=date&legend=top-left)](https://star-history.dera.page/#jedzqer/manga-translator-android&type=date&legend=top-left)

## Data and File Layout 🗂️
- Manga library storage: `/Android/data/<package>/files/manga_library/`
- Each image generates a same-name `*.json` translation result, and OCR cache is stored as `*.ocr.json`
- Glossary: each folder maintains its own `glossary.json`
- Reading progress, full-text fast translation switches, and related settings are stored in SharedPreferences

## Build from Source 🧩

### Requirements
- JDK 17.0.17+
- Kotlin 2.0.0+
- Gradle 8.11.1+
- Android SDK: platform 36, build-tools 36.0.0

### Build Commands
```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### Models and Assets
Model files are not included in the source repository to keep it lightweight. Download them and place them in the matching subdirectories under `assets/`:

- `models/detection/manga-bubble-seg-yolo26n-1472.onnx`: YOLO26n-seg speech-bubble segmentation model with 1472x1472 ONNX input and bubble-contour output.
- `models/detection/PP-OCRv6_det_mobile_infer.onnx`: PaddleOCR PP-OCRv6 mobile text-line detector.
- `models/ocr/PP-OCRv6_small_rec.onnx` and `models/ocr/ppocr_keys_v6_small.txt`: Japanese, English, Chinese, and mixed-text recognizer plus character dictionary.
- `models/ocr/korean_PP-OCRv5_mobile_rec.onnx` and `models/ocr/korean_PP-OCRv5_mobile_rec_dict.txt`: Korean recognizer plus character dictionary.

Model sources:

- Text detection and general recognition: [PaddleOCR ONNX models](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
- Korean recognition: [PaddleOCR Korean PP-OCRv5 ONNX model](https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx)
- Speech-bubble segmentation: use a YOLO26n-seg bubble model whose filename matches `manga-bubble-seg-yolo26n-1472.onnx` for this version.

Prompts, fonts, and OCR configuration files are also stored under `assets/`; their names must remain consistent with the code.

### Local signing configuration

This repository contains no signing key or passwords. To build a signed release, create an untracked local `signing.properties` and pass its values as Gradle properties:

```properties
STORE_FILE=/absolute/path/to/your-release-key.jks
STORE_PASSWORD=your-store-password
KEY_ALIAS=your-key-alias
KEY_PASSWORD=your-key-password
```

```bash
./gradlew :app:assembleRelease \
  -PSTORE_FILE="$(grep '^STORE_FILE=' signing.properties | cut -d= -f2-)" \
  -PSTORE_PASSWORD="$(grep '^STORE_PASSWORD=' signing.properties | cut -d= -f2-)" \
  -PKEY_ALIAS="$(grep '^KEY_ALIAS=' signing.properties | cut -d= -f2-)" \
  -PKEY_PASSWORD="$(grep '^KEY_PASSWORD=' signing.properties | cut -d= -f2-)"
```

### Release Version Sync
Update all of the following files at the same time:
- `app/src/main/java/com/manga/translate/VersionInfo.kt`
- `app/build.gradle.kts`
- `update.json`

## Acknowledgements 🙏

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) - OCR model support
- [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr) - MangaOCR model support
- [bluolightning/manga-ocr-mobile](https://huggingface.co/bluolightning/manga-ocr-mobile) - MangaOCR-mobile model support
- Support from all users

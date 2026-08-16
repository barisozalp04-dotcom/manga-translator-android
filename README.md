中文 | [English](./README_EN.md)

# Manga Translator 📖

面向安卓的漫画翻译 App：本地气泡检测与 OCR，结合 OpenAI 兼容接口完成翻译，并在原图上覆盖显示可拖动的翻译气泡。并支持屏幕翻译/悬浮窗翻译，可在任意 App 或桌面上直接识别并翻译当前屏幕中的漫画文本。

使用教程：[简中教程](./Tutorial/简中教程.md)

| 原图 | 翻译结果 |
|------|----------|
| ![原图](./Tutorial/FirePunch.webp) | ![翻译结果](./Tutorial/translated.webp) |


## 主要功能 ✨
- 支持日、英、韩、法、西、葡、德、意、俄等多种源语言翻译为中文，以及中文翻译为英文或俄文
- 屏幕翻译：支持悬浮窗翻译，在任意界面识别并翻译当前屏幕内容
- 漫画库管理：新建文件夹、批量导入图片、漫画文件夹导入，支持CBZ、ZIP、PDF导入导出
- 翻译流程：气泡检测 + 本地或 OpenAI 兼容 API OCR + LLM 翻译，支持标准模式与全文速译
- 阅读体验：翻译覆盖层、翻译气泡位置可拖动、阅读进度自动保存；新增取消键与加减号微调，条漫与普通阅读缩放同步
- 字体设置：支持自定义气泡字体、字体加粗，普通气泡框与悬浮窗气泡框共用一套字体配置
- 译名表与缓存：按文件夹维护 glossary.json，自动累积固定译名
- 后台翻译通知：文件夹/批量翻译完成后发送带声音的高优先级系统通知，点击回到漫画库
- 更新与日志：启动检查更新，翻译期间前台服务与日志查看
- 多供应商负载：支持配置多个翻译供应商，按权重自动均衡负载
- 条漫/长图：自动判断作品是否更接近条漫并切换阅读方式，长图/条漫模式下支持跨页气泡合并

## 支持的翻译语言 🌐
- 目标语言由软件界面语言决定：
  - 简体中文界面 → 简体中文
  - 繁体中文界面 → 繁体中文
  - 英文界面 → 英文
  - 俄文界面 → 俄文
- 当前文件夹的源语言可在漫画库中单独设置，支持：日文、英文、韩文、简体中文、繁体中文、中英混合、法文、西班牙文、葡萄牙文、德文、意大利文、俄文
- 软件界面切换为繁体中文时，会优先使用繁体提示词

## 快速使用 🚀
1. 在漫画库中新建文件夹并导入图片
2. 确保图片文件名顺序与阅读顺序一致（例如 1.jpg, 2.jpg）
3. 在设置页 OCR 设置中选择本地 OCR，或填写 OpenAI 兼容 OCR API 的地址、Key 和模型
4. 回到漫画库，选择文件夹并点击“翻译文件夹”
5. 翻译完成后点击“开始阅读”，在阅读页可拖动气泡位置

*全文速译建议：页数较多时分批上传翻译，或在设置中提高 API 超时。*

## 常见问题 ❓
- 翻译失败或结果为空：确认 API 地址填写的是服务商给出的 OpenAI 兼容上级地址（例如 `https://api.deepseek.com/v1`、`https://open.bigmodel.cn/api/paas/v4`），软件会自动补全 `/chat/completions`；模型名须与供应商一致且网络可达
- 翻译顺序错乱：请先对图片按阅读顺序重命名
- 怎么获取AI：具体获取方法可以去搜索一下

## 交流
可以进QQ群提问交流：1080302768

## Star History
** 喜欢的话可以点个Star哦 **
[![Star History Chart](https://api.star-history.com/svg?repos=jedzqer/manga-translator-android&type=date&legend=top-left)](https://www.star-history.com/#jedzqer/manga-translator-android&type=date&legend=top-left)


## 数据与文件说明 🗂️
- 漫画库存储：`/Android/data/<package>/files/manga_library/`
- 每张图片生成同名 `*.json` 翻译结果，OCR 缓存为 `*.ocr.json`
- 译名表：每个文件夹维护 `glossary.json`
- 阅读进度、全文速译开关等存储在 SharedPreferences

## 从源码构建 🧩

### 环境要求
- JDK 17.0.17+
- Kotlin 2.0.0+
- Gradle 8.11.1+
- Android SDK: platform 36, build-tools 36.0.0

### 构建命令
```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### 模型与资源
为避免仓库体积过大，模型文件不随源码仓库提供。请下载后放入 `assets/` 的对应子目录：

- `models/detection/manga-bubble-seg-yolo26n-1472.onnx`：YOLO26n-seg 气泡分割模型，1472×1472 ONNX 输入，输出气泡轮廓。
- `models/detection/PP-OCRv6_det_mobile_infer.onnx`：PaddleOCR PP-OCRv6 mobile 文字行检测模型。
- `models/ocr/PP-OCRv6_small_rec.onnx` 与 `models/ocr/ppocr_keys_v6_small.txt`：日文、英文、中文及中英混合识别模型与字符表。
- `models/ocr/korean_PP-OCRv5_mobile_rec.onnx` 与 `models/ocr/korean_PP-OCRv5_mobile_rec_dict.txt`：韩文识别模型与字符表。

模型来源：

- 文字检测与通用识别模型：[PaddleOCR ONNX 模型](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
- 韩文识别模型：[PaddleOCR Korean PP-OCRv5 ONNX 模型](https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx)
- 气泡分割模型：请使用与当前版本 `manga-bubble-seg-yolo26n-1472.onnx` 文件名匹配的 YOLO26n-seg 气泡模型。

提示词、字体与 OCR 配置同样位于 `assets/` 子目录，文件名必须与代码保持一致。

### 本地签名配置

仓库不包含签名密钥或密码。需要构建已签名 release 时，请在本机创建未提交的 `signing.properties`，再通过 Gradle 参数传入：

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

### 发布版本号同步
需同时修改：
- `app/src/main/java/com/manga/translate/app/VersionInfo.kt`
- `app/build.gradle.kts`
- `update.json`

## 🙏 致谢

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) - 提供 OCR 模型支持
- [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr) - MangaOCR 模型支持
- [bluolightning/manga-ocr-mobile](https://huggingface.co/bluolightning/manga-ocr-mobile) - MangaOCR-mobile 模型支持
- 所有用户的支持

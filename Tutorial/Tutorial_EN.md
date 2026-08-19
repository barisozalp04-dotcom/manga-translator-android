# User Guide

For feedback and discussion, join the [Discord server](https://discord.gg/Zf5bpay5G).

---

## Main Translation Workflow

### 1. Import a manga

<img src="./mangaku-screenshot.jpg" alt="Manga library screen" width="45%" />

On the **Library** screen:

- Tap **+** in the bottom-right corner to create a folder, then add manga images to it.
- Tap **Import Manga Folder** to import a complete folder.
  - A folder containing chapter subfolders is imported as a collection with child chapters.
  - A folder containing images directly is imported as one manga.
- Tap **Import CBZ/ZIP/PDF** to import an archive.
- Ensure that image filenames match their intended reading order, such as `1.jpg`, `2.jpg`, and `3.jpg`.

### 2. Configure an API

<img src="./setting-screenshot.jpg" alt="Settings screen" width="45%" />

The app requires an API from a supported AI provider. See the [API Setup Guide](./API_Guide_EN.md) if you do not have one yet.

> The app UI language determines the translation language: Simplified Chinese and Traditional Chinese UIs translate into Chinese, the English UI translates into English, and the Russian UI translates into Russian.

In **Settings**, enter the API details for your provider:

1. **API Format**: select the format specified in the provider section of the API guide.
2. **API URL**: enter the provider's base URL. The app appends `/chat/completions` for **OpenAI compatible** and `/responses` for **OpenAI Responses** when needed.
3. **API Key**: paste the key generated in the provider dashboard. Keep it private.
4. **Model name**: enter the exact model ID shown by the provider, or select one with **Get Model List** when available.

Return to the library after saving the settings.

### 3. Translate

<img src="./translator-screenshot.jpg" alt="Translation screen" width="45%" />

1. Open a folder and tap **Translate Folder**. Select the source language as needed; Japanese, English, Korean, French, Spanish, Portuguese, German, Italian, Russian, and Chinese are supported.
2. Wait for the translation to finish, or tap **Start Reading** after preprocessing to read while pages continue translating.
3. A high-priority notification is sent when background translation succeeds, fails, or is canceled. Tap it to return to the library.

> The app automatically switches works that resemble webtoons to **Webtoon Scroll**. Change this in the folder's reading settings when necessary.
>
> For folders with more than 50 pages, full-text fast translation may need a longer API timeout. You can also translate in batches or disable full-text fast translation.

### 4. Read and adjust bubbles

- Drag a translated bubble to move it. Changes are saved automatically.
- Double-tap the page to enter or leave zoom mode. In zoom mode, use the cancel control or the `+` and `-` controls to fine-tune bubbles.
- To jump to a page, tap its image filename in the library folder.
- To retranslate one page, return to the library, long-press the image, then tap **Retranslate**.
- To retranslate a whole work, disable full-text fast translation and retranslate all pages, or import the manga again with a stronger model.
- To add a missing text area, tap the edit button in the top-right corner, tap **+**, place an empty bubble over the text, then confirm with the green checkmark. Webtoon mode allows bubbles to span pages.
- If full-text fast translation is too slow, turn it off to use concurrent page-by-page translation instead.
- Adjust free-text bubble opacity in Settings when bubbles cover too much artwork.
- Customize translation style in Settings to guide the model, for example to ignore unwanted symbols.
- Upload a custom font or enable bold text under **Font Settings**. Normal and floating-window bubbles share this font setting.

---

## Floating-Window Translation

On the Library screen, tap **Floating Translation**. Grant overlay and screen-capture permissions, then choose the translation language for the session. Once the floating button appears:

| Action | Result |
| --- | --- |
| Tap the floating button | Recognize and translate text currently on screen |
| Double-tap the floating button | Clear all bubbles |
| Long-press the floating button | Open the menu |

The long-press menu includes:

- **Edit mode**: the overlay receives touch input. Drag bubbles, long-press to delete one, or tap **Add Bubble** and draw a rectangle for text the detector missed. New bubbles are translated after confirmation.
- **Screen-area translation**: enter edit mode directly and select regions manually without automatic detection.
- **Exit**: close the floating window.

Floating-window results exist only for the current session. They reset after a new recognition pass or when the floating window closes. If no results appear, check screen-capture permission and API settings.

---

## Other Settings

### OCR settings

Open **OCR Settings** from Settings:

- **Use local OCR** runs the bundled offline recognition engine.
- Disabling local OCR uses an online OCR API and requires its URL, key, and model.
- Online OCR can support multiple languages when the selected model supports them.
- Increase **OCR API timeout** when needed. The allowed range is 30 to 1200 seconds.

### Multi-provider scheduling

Configure additional translation providers to distribute requests by weight. The concurrency setting remains the overall limit across all providers.

### Custom request parameters

Parameters apply to the main provider by default and may be assigned to a particular additional provider. A provider cannot have duplicate parameter names.

### Floating translation settings

Configure a separate API URL, key, and model for floating translation. Leave these blank to use the main API settings.

- **Vision-language direct translation** sends bubble images to a vision-capable model. Set its concurrent translation count separately.
- **Proofreading mode** enters edit mode after every recognition pass.
- **Auto-clear bubbles** clears bubbles automatically when the page changes.

---

## Tips

- Export a folder as CBZ, PDF, or images. The default export directory is `Documents/manga-translate`.
- Clear app cache to remove the floating-translation cache and other cached translation data.
- When full-text fast translation is disabled, turning off **Glossary Processing** reuses the existing glossary without extracting or saving additional terms, which can reduce processing time.
- Open a collection and tap **Import Chapters** to import child chapters. You can select several chapters at once; empty or duplicate folders are skipped.

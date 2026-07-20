# Package 架构

项目暂时保持单一 `:app` Gradle 模块，源码通过 package 建立领域边界，为后续按需抽取 Android Library 或 Kotlin Library 做准备。

## Package 职责

| Package | 职责 |
|---|---|
| `app` | Application、Activity、主导航和更新入口 |
| `background` | 后台翻译 Service 及其 UI 回调适配 |
| `library` | 漫画库、导入导出、目录管理与 Library UI |
| `reader` | 阅读会话、图片展示、手势和阅读页覆盖层 |
| `floating` | 系统悬浮窗、投屏抓取和悬浮 UI |
| `settings` | 设置模型与持久化门面 |
| `settings.ui` | 设置页面与设置对话框编排 |
| `translation` | 翻译管线、文本翻译、供应商调度与任务协调 |
| `network` | LLM/OCR 网络协议与客户端 |
| `ocr` | 本地 OCR 引擎、OCR 编排和文本清洗 |
| `detection` | 页面区域、气泡和游离文字检测 |
| `rendering` | 气泡形状、字体、颜色和文本布局 |
| `storage` | 翻译、OCR、进度、任务和缓存文件存储 |
| `model` | 跨领域数据类型、枚举和共享常量 |
| `platform` | Android/文件/位图/对话框等通用基础设施 |
| `di` | 应用级组合根，只负责创建和连接依赖 |

## 依赖方向

新增代码优先遵循下面的方向：

```text
app / background / di
          |
          v
library / reader / floating / settings.ui
          |
          v
translation / rendering / storage / settings
          |
          v
network / ocr / detection
          |
          v
model / platform
```

- `model` 不依赖功能 UI、后台 Service 或业务协调器。
- `platform` 不依赖功能 UI；功能专用适配器应留在对应功能 package。
- `network`、`ocr`、`detection` 不直接操作 Fragment、Activity 或 Service。
- `library`、`reader`、`floating` 之间避免新增直接依赖；共享能力下沉到 `translation`、`rendering`、`storage` 或 `model`。
- `di` 是允许认识具体实现的组合根，其他 package 不通过 `di` 获取依赖；Android 入口类是当前例外，由入口访问 `appContainer`。
- 当前 `FolderTranslationCoordinator` 仍连接 Library 回调和后台进度通知，属于后续拆 Gradle 模块前需要继续接口化的边界。

## 后续模块化

只有当依赖方向稳定、构建时间或复用需求值得时再抽 Gradle 模块。优先候选是 `model`、`rendering`、`detection/ocr` 和 `translation`；UI Feature 最后拆，避免先处理资源、Manifest 和导航迁移。

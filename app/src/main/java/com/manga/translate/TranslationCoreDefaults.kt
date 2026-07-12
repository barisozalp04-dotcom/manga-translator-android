package com.manga.translate

object TranslationCoreDefaults {
    const val DefaultDetectionInputSize = 640
    const val DefaultLineDetectionInputSize = 960

    const val BubbleDetectorNmsIouThreshold = 0.5f
    const val FreeTextOutputExpandRatio = 0.08f
    const val FreeTextOutputExpandMin = 1.0f

    const val PageRegionTextIouThreshold = 0.2f
    const val TinyBubbleShortSideMinPx = 26f
    const val TinyBubbleLongSideMinPx = 56f
    const val TinyBubbleShortSideRatio = 0.032f
    const val TinyBubbleLongSideRatio = 0.075f
    const val TinyBubbleMaxAreaRatio = 0.0022f
    const val BubbleDedupIouThreshold = 0.65f

    const val VlBubbleExpandRatio = 0.1f
    const val VlBubbleExpandMin = 4f
}

package com.manga.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Size
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemReadingWebtoonPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class WebtoonReadingAdapter(
    private val scope: CoroutineScope,
    private val loadTranslation: (File) -> TranslationResult?
) : RecyclerView.Adapter<WebtoonReadingAdapter.WebtoonPageViewHolder>() {
    data class BoundPageSnapshot(
        val imageFile: File,
        val translation: TranslationResult?,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    private data class PresentationConfig(
        val verticalLayoutEnabled: Boolean,
        val bubbleRenderSettings: NormalBubbleRenderSettings
    )

    data class WebtoonDisplayItem(
        val imageFile: File,
        val imageIndex: Int
    ) {
        val path: String
            get() = imageFile.absolutePath

        val stableKey: String
            get() = path
    }

    private companion object {
        const val PAYLOAD_PRESENTATION_ONLY = "presentation_only"
        const val PAYLOAD_TRANSLATION_ONLY = "translation_only"
        const val PAYLOAD_PLACEHOLDER_ONLY = "placeholder_only"
        const val DEFAULT_PLACEHOLDER_HEIGHT_RATIO = 1.4f
        const val SOURCE_SIZE_BATCH_SIZE = 12
    }

    private var items: List<File> = emptyList()
    private var displayItems: List<WebtoonDisplayItem> = emptyList()
    private var verticalLayoutEnabled: Boolean = true
    private var bubbleRenderSettings = NormalBubbleRenderSettings(
        shrinkPercent = 0,
        opacityPercent = 100,
        freeBubbleShrinkPercent = 0,
        freeBubbleOpacityPercent = 100,
        minAreaPerCharSp = 48f,
        useHorizontalText = true
    )
    private val runtimeCacheLimit = computeRuntimeCacheLimit()
    private val rememberedPageHeights = LruMap<String, Int>(runtimeCacheLimit)
    private val sourceSizeCache = LruMap<String, Size>(runtimeCacheLimit)
    private val translationCache = LruMap<String, TranslationResult?>(runtimeCacheLimit)
    private val boundHolders = mutableMapOf<String, MutableSet<WebtoonPageViewHolder>>()
    private val translationLoadLock = Any()
    private val translationLoadJobs = mutableMapOf<String, Deferred<TranslationResult?>>()

    private class LruMap<K, V>(private val maxSize: Int) :
        LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    private var editModeEnabled = false
    private var lockedPagePath: String? = null
    private var lockedPageTranslation: TranslationResult? = null
    private var lockedPageOffsets: Map<Int, Pair<Float, Float>> = emptyMap()
    private var sourceSizePrefetchJob: Job? = null

    var onLockedBubbleOffsetChanged: ((Int, Float, Float) -> Unit)? = null
    var onLockedBubbleRemove: ((Int) -> Unit)? = null
    var onLockedBubbleTap: ((Int) -> Unit)? = null
    var onLockedBubbleResizeTap: ((Int) -> Unit)? = null
    var onLockedBubbleLongPress: ((Int) -> Unit)? = null
    var onDisplayStructureChanging: (() -> Unit)? = null
    var onDisplayStructureChanged: (() -> Unit)? = null

    fun submit(
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        bubbleRenderSettings: NormalBubbleRenderSettings
    ) {
        val previousDisplayItems = displayItems
        val newDisplayItems = buildDisplayItems(images)
        val previousConfig = PresentationConfig(
            verticalLayoutEnabled = this.verticalLayoutEnabled,
            bubbleRenderSettings = this.bubbleRenderSettings
        )
        val newConfig = PresentationConfig(
            verticalLayoutEnabled = verticalLayoutEnabled,
            bubbleRenderSettings = bubbleRenderSettings
        )
        val diffResult = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousDisplayItems.size

                override fun getNewListSize(): Int = newDisplayItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return previousDisplayItems[oldItemPosition].stableKey ==
                        newDisplayItems[newItemPosition].stableKey
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return areItemsTheSame(oldItemPosition, newItemPosition) && previousConfig == newConfig
                }

                override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                    if (!areItemsTheSame(oldItemPosition, newItemPosition)) return null
                    return if (
                        previousConfig.verticalLayoutEnabled != newConfig.verticalLayoutEnabled ||
                        previousConfig.bubbleRenderSettings != newConfig.bubbleRenderSettings
                    ) {
                        PAYLOAD_PRESENTATION_ONLY
                    } else {
                        null
                    }
                }
            }
        )
        items = images
        displayItems = newDisplayItems
        pruneTranslationCache(images)
        pruneSourceSizeCache(images)
        this.verticalLayoutEnabled = verticalLayoutEnabled
        this.bubbleRenderSettings = bubbleRenderSettings
        diffResult.dispatchUpdatesTo(this)
        prefetchSourceSizes(images)
    }

    fun updateEditSession(
        enabled: Boolean,
        lockedImagePath: String?,
        translation: TranslationResult?,
        offsets: Map<Int, Pair<Float, Float>>
    ) {
        val affectedPaths = linkedSetOf<String>()
        lockedPagePath?.let(affectedPaths::add)
        lockedImagePath?.let(affectedPaths::add)
        editModeEnabled = enabled
        lockedPagePath = lockedImagePath
        lockedPageTranslation = translation
        lockedPageOffsets = offsets.toMap()
        for (path in affectedPaths) {
            refreshPath(path)
        }
    }

    fun findBoundPageSnapshot(imagePath: String): BoundPageSnapshot? {
        return boundHolders[imagePath]?.firstOrNull()?.buildSnapshot()
    }

    fun setEditSessionGestureInteracting(active: Boolean) {
        val path = lockedPagePath ?: return
        boundHolders[path]?.forEach { it.applyGestureInteracting(active) }
    }

    fun adapterPositionForImageIndex(imageIndex: Int): Int {
        if (imageIndex < 0) return RecyclerView.NO_POSITION
        return displayItems.indexOfFirst { it.imageIndex == imageIndex }
    }

    fun adapterPositionRangeForImageIndex(imageIndex: Int): IntRange? {
        if (imageIndex < 0) return null
        var first = RecyclerView.NO_POSITION
        var last = RecyclerView.NO_POSITION
        displayItems.forEachIndexed { index, item ->
            if (item.imageIndex != imageIndex) return@forEachIndexed
            if (first == RecyclerView.NO_POSITION) first = index
            last = index
        }
        return if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            null
        } else {
            first..last
        }
    }

    fun imageIndexForAdapterPosition(adapterPosition: Int): Int {
        return displayItems.getOrNull(adapterPosition)?.imageIndex ?: RecyclerView.NO_POSITION
    }

    fun imagePathsForAdapterRange(startPosition: Int, endPosition: Int): Set<String> {
        if (displayItems.isEmpty()) return emptySet()
        val start = startPosition.coerceAtLeast(0)
        val end = endPosition.coerceAtMost(displayItems.lastIndex)
        if (start > end) return emptySet()
        return (start..end).mapTo(linkedSetOf()) { displayItems[it].path }
    }

    fun clearRuntimeCaches() {
        sourceSizePrefetchJob?.cancel()
        synchronized(translationLoadLock) {
            translationLoadJobs.values.forEach { it.cancel() }
            translationLoadJobs.clear()
        }
        boundHolders.clear()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WebtoonPageViewHolder {
        val binding = ItemReadingWebtoonPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WebtoonPageViewHolder(binding)
    }

    override fun getItemCount(): Int = displayItems.size

    override fun onBindViewHolder(holder: WebtoonPageViewHolder, position: Int) {
        holder.bind(
            item = displayItems[position],
            verticalLayoutEnabled = verticalLayoutEnabled,
            bubbleRenderSettings = bubbleRenderSettings
        )
    }

    override fun onBindViewHolder(
        holder: WebtoonPageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_TRANSLATION_ONLY)) {
            holder.reloadTranslationOverlay()
            return
        }
        if (payloads.contains(PAYLOAD_PLACEHOLDER_ONLY)) {
            holder.refreshPlaceholderHeight()
            return
        }
        if (payloads.contains(PAYLOAD_PRESENTATION_ONLY)) {
            holder.updatePresentation(
                verticalLayoutEnabled = verticalLayoutEnabled,
                bubbleRenderSettings = bubbleRenderSettings
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: WebtoonPageViewHolder) {
        holder.recycle()
    }

    fun notifyTranslationChanged(imagePath: String) {
        translationCache.remove(imagePath)
        synchronized(translationLoadLock) {
            translationLoadJobs.remove(imagePath)?.cancel()
        }
        displayItems.forEachIndexed { index, item ->
            if (item.path != imagePath) return@forEachIndexed
            notifyItemChanged(index, PAYLOAD_TRANSLATION_ONLY)
        }
    }

    private fun pruneTranslationCache(images: List<File>) {
        val activePaths = images.mapTo(hashSetOf()) { it.absolutePath }
        if (translationCache.isNotEmpty()) {
            translationCache.keys.retainAll(activePaths)
        }
        synchronized(translationLoadLock) {
            val iterator = translationLoadJobs.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in activePaths) {
                    entry.value.cancel()
                    iterator.remove()
                }
            }
        }
    }

    private fun pruneSourceSizeCache(images: List<File>) {
        if (sourceSizeCache.isEmpty()) return
        val activePaths = images.mapTo(hashSetOf()) { it.absolutePath }
        val iterator = sourceSizeCache.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() !in activePaths) {
                iterator.remove()
            }
        }
    }

    private suspend fun loadTranslationShared(imageFile: File): TranslationResult? {
        val imagePath = imageFile.absolutePath
        if (translationCache.containsKey(imagePath)) {
            return translationCache[imagePath]
        }
        val deferred = synchronized(translationLoadLock) {
            if (translationCache.containsKey(imagePath)) {
                return translationCache[imagePath]
            }
            translationLoadJobs[imagePath] ?: scope.async(Dispatchers.IO) {
                loadTranslation(imageFile)
            }.also { translationLoadJobs[imagePath] = it }
        }
        return try {
            deferred.await().also { translation ->
                translationCache[imagePath] = translation
            }
        } finally {
            synchronized(translationLoadLock) {
                if (translationLoadJobs[imagePath] === deferred) {
                    translationLoadJobs.remove(imagePath)
                }
            }
        }
    }

    private fun prefetchSourceSizes(images: List<File>) {
        sourceSizePrefetchJob?.cancel()
        val uncached = images.filterNot { sourceSizeCache.containsKey(it.absolutePath) }
        if (uncached.isEmpty()) return
        sourceSizePrefetchJob = scope.launch {
            val activePathsSnapshot = items.mapTo(hashSetOf()) { it.absolutePath }
            for (batch in uncached.chunked(SOURCE_SIZE_BATCH_SIZE)) {
                ensureActive()
                val sizes = withContext(Dispatchers.IO) {
                    batch.mapNotNull { imageFile ->
                        if (imageFile.absolutePath !in activePathsSnapshot) {
                            null
                        } else {
                            readImageSize(imageFile)?.let { imageFile.absolutePath to it }
                        }
                    }
                }
                if (sizes.isEmpty()) continue
                val updatedPositions = ArrayList<Int>(sizes.size)
                sizes.forEach { (path, size) ->
                    sourceSizeCache.put(path, size)
                    val position = displayItems.indexOfFirst { it.path == path }
                    if (position >= 0) {
                        updatedPositions.add(position)
                    }
                }
                if (updatedPositions.isNotEmpty()) {
                    val minPos = updatedPositions.min()
                    val maxPos = updatedPositions.max()
                    notifyItemRangeChanged(minPos, maxPos - minPos + 1, PAYLOAD_PLACEHOLDER_ONLY)
                }
            }
        }
    }

    private fun readImageSize(imageFile: File): Size? {
        return if (ImageFileSupport.isAvifFile(imageFile.name)) {
            AvifBitmapDecoder.getSize(imageFile)
        } else {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imageFile.absolutePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                Size(options.outWidth, options.outHeight)
            } else {
                null
            }
        }
    }

    private fun refreshPath(path: String) {
        val holders = boundHolders[path]
        if (!holders.isNullOrEmpty()) {
            holders.forEach { it.refreshOverlayPresentation() }
            return
        }
        displayItems.forEachIndexed { index, item ->
            if (item.path != path) return@forEachIndexed
            notifyItemChanged(index, PAYLOAD_PRESENTATION_ONLY)
        }
    }

    private fun buildDisplayItems(images: List<File>): List<WebtoonDisplayItem> {
        val result = ArrayList<WebtoonDisplayItem>(images.size)
        images.forEachIndexed { index, imageFile ->
            result += WebtoonDisplayItem(imageFile, index)
        }
        return result
    }

    private fun computeRuntimeCacheLimit(): Int {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        return when {
            maxMemoryMb >= 768 -> 80
            maxMemoryMb >= 512 -> 60
            maxMemoryMb >= 256 -> 40
            else -> 25
        }
    }

    inner class WebtoonPageViewHolder(
        private val binding: ItemReadingWebtoonPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val imageTransformController = ReadingImageTransformController(
            context = binding.root.context,
            imageView = binding.readingPageImage,
            hasBubbleAt = { x, y -> binding.readingPageOverlay.hasBubbleAt(x, y) },
            onMatrixUpdated = { updateOverlayDisplayRect() },
            allowPanWhenOverflowing = false
        )
        private val touchSlop = ViewConfiguration.get(binding.root.context).scaledTouchSlop.toFloat()
        private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        private val doubleTapSlop = ViewConfiguration.get(binding.root.context).scaledDoubleTapSlop.toFloat()
        private var bindJob: Job? = null
        private var overlayReloadJob: Job? = null
        private var boundPath: String? = null
        private var boundFile: File? = null
        private var boundItem: WebtoonDisplayItem? = null
        private var currentDecodedImage: DecodedReadingBitmap? = null
        private var currentBitmap: Bitmap? = null
        private var currentImageWidth: Int = 0
        private var currentImageHeight: Int = 0
        private var currentTranslation: TranslationResult? = null
        private var downX = 0f
        private var downY = 0f
        private var touchMoved = false
        private var lastTapTime = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f

        fun bind(
            item: WebtoonDisplayItem,
            verticalLayoutEnabled: Boolean,
            bubbleRenderSettings: NormalBubbleRenderSettings
        ) {
            val imageFile = item.imageFile
            val previousPath = boundPath
            if (previousPath != null && previousPath != imageFile.absolutePath) {
                unregisterBoundHolder(previousPath)
            }
            boundPath = imageFile.absolutePath
            boundFile = imageFile
            boundItem = item
            currentDecodedImage = null
            currentBitmap = null
            currentImageWidth = 0
            currentImageHeight = 0
            currentTranslation = null
            downX = 0f
            downY = 0f
            touchMoved = false
            lastTapTime = 0L
            lastTapX = 0f
            lastTapY = 0f
            bindJob?.cancel()
            overlayReloadJob?.cancel()
            registerBoundHolder(imageFile.absolutePath)
            binding.readingPageOverlay.setEditMode(false)
            binding.readingPageOverlay.setTouchPassthroughEnabled(true)
            binding.readingPageOverlay.setEditScrollThroughEnabled(false)
            binding.readingPageOverlay.setVerticalLayoutEnabled(verticalLayoutEnabled)
            binding.readingPageOverlay.setNormalBubbleRenderSettings(bubbleRenderSettings)
            binding.readingPageOverlay.onOffsetChanged = null
            binding.readingPageOverlay.onBubbleRemove = null
            binding.readingPageOverlay.onBubbleTap = null
            binding.readingPageOverlay.onBubbleResizeTap = null
            binding.readingPageOverlay.onBubbleLongPress = null
            binding.readingPageOverlay.visibility = View.GONE
            applyPlaceholder(item)
            primeLayoutFromKnownSize(item)
            binding.readingPageImage.setRegionSource(null)
            binding.readingPageImage.setImageDrawable(null)
            imageTransformController.setCurrentBitmap(null)
            loadPage(item)
        }

        fun updatePresentation(
            verticalLayoutEnabled: Boolean,
            bubbleRenderSettings: NormalBubbleRenderSettings
        ) {
            binding.readingPageOverlay.setVerticalLayoutEnabled(verticalLayoutEnabled)
            binding.readingPageOverlay.setNormalBubbleRenderSettings(bubbleRenderSettings)
            refreshOverlayPresentation()
        }

        fun refreshOverlayPresentation() {
            if (!hasCurrentContent()) return
            bindOverlay(currentTranslation)
        }

        fun buildSnapshot(): BoundPageSnapshot? {
            val imageFile = boundFile ?: return null
            return BoundPageSnapshot(
                imageFile = imageFile,
                translation = currentTranslation,
                sourceWidth = currentImageWidth,
                sourceHeight = currentImageHeight
            )
        }

        fun isZoomed(): Boolean = imageTransformController.isZoomed()

        fun resetZoom() {
            imageTransformController.resetZoom()
        }

        fun applyGestureInteracting(active: Boolean) {
            if (binding.readingPageOverlay.visibility == View.GONE) return
            binding.readingPageOverlay.setGestureInteracting(active)
        }

        fun handleTouchEvent(event: MotionEvent): Boolean {
            val transformHandled = imageTransformController.handleTouch(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    touchMoved = false
                    return transformHandled || isZoomed()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!touchMoved &&
                        (kotlin.math.abs(event.x - downX) > touchSlop ||
                            kotlin.math.abs(event.y - downY) > touchSlop)
                    ) {
                        touchMoved = true
                    }
                    return transformHandled || isZoomed()
                }

                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    return transformHandled || isZoomed()
                }

                MotionEvent.ACTION_UP -> {
                    if (!touchMoved && !isLockedEditPage()) {
                        val now = event.eventTime
                        val isDoubleTap = now - lastTapTime <= doubleTapTimeout &&
                            kotlin.math.abs(event.x - lastTapX) <= doubleTapSlop &&
                            kotlin.math.abs(event.y - lastTapY) <= doubleTapSlop
                        if (isDoubleTap) {
                            lastTapTime = 0L
                            touchMoved = false
                            return toggleDoubleTapZoom(event.x, event.y)
                        }
                        lastTapTime = now
                        lastTapX = event.x
                        lastTapY = event.y
                    }
                    touchMoved = false
                    return transformHandled || isZoomed()
                }

                MotionEvent.ACTION_CANCEL -> {
                    touchMoved = false
                    return transformHandled || isZoomed()
                }
            }
            return transformHandled || isZoomed()
        }

        private fun loadPage(item: WebtoonDisplayItem) {
            bindJob?.cancel()
            bindJob = scope.launch {
                val imageFile = item.imageFile
                val imagePath = imageFile.absolutePath
                val targetWidth = resolveTargetWidth()
                val targetHeight = resolveTargetHeight()
                val decodedDeferred = async(Dispatchers.IO) {
                    ReadingBitmapDecoder.decode(imageFile, targetWidth, targetHeight)
                }
                val earlyTranslationJob = launch {
                    val translation = loadTranslationShared(imageFile)
                    if (boundItem?.stableKey != item.stableKey) return@launch
                    currentTranslation = translation
                    if (currentImageWidth > 0 && currentImageHeight > 0) {
                        currentTranslation = normalizeTranslation(translation)
                        bindOverlay(currentTranslation)
                    }
                }
                val decoded = decodedDeferred.await()
                if (boundItem?.stableKey != item.stableKey) return@launch
                if (decoded == null) {
                    earlyTranslationJob.cancel()
                    binding.readingPageImage.setRegionSource(null)
                    binding.readingPageImage.setImageDrawable(null)
                    binding.readingPageOverlay.visibility = View.GONE
                    showPlaceholder(item)
                    return@launch
                }
                currentDecodedImage = decoded
                currentBitmap = decoded.bitmap
                currentImageWidth = decoded.sourceWidth
                currentImageHeight = decoded.sourceHeight
                currentTranslation = normalizeTranslation(currentTranslation)
                updatePageHeightForImage(decoded.sourceWidth, decoded.sourceHeight)
                binding.readingPageImage.setRegionSource(decoded.regionSource)
                binding.readingPageImage.setImageDrawable(decoded.drawable)
                binding.root.post {
                    if (boundItem?.stableKey != item.stableKey) return@post
                    imageTransformController.resetContent(
                        decoded.displayWidth,
                        decoded.displayHeight,
                        ReadingDisplayMode.FIT_WIDTH
                    )
                    rememberedPageHeights[item.stableKey] = binding.readingPageImage.height
                    binding.readingPagePlaceholder.visibility = View.GONE
                    bindOverlay(currentTranslation)
                }
            }
        }

        fun recycle() {
            bindJob?.cancel()
            overlayReloadJob?.cancel()
            boundPath?.let(::unregisterBoundHolder)
            boundPath = null
            boundFile = null
            boundItem = null
            binding.readingPageImage.setRegionSource(null)
            binding.readingPageImage.setImageDrawable(null)
            imageTransformController.setCurrentBitmap(null)
            currentBitmap?.recycleSafely()
            currentDecodedImage = null
            currentBitmap = null
            currentImageWidth = 0
            currentImageHeight = 0
            currentTranslation = null
            binding.readingPageOverlay.onOffsetChanged = null
            binding.readingPageOverlay.onBubbleRemove = null
            binding.readingPageOverlay.onBubbleTap = null
            binding.readingPageOverlay.onBubbleResizeTap = null
            binding.readingPageOverlay.onBubbleLongPress = null
            binding.readingPageOverlay.visibility = View.GONE
            binding.readingPageOverlay.setSourceBitmap(null)
            binding.readingPagePlaceholder.visibility = View.VISIBLE
            updateViewHeight(binding.root, ViewGroup.LayoutParams.WRAP_CONTENT)
            updateViewHeight(binding.readingPageImage, ViewGroup.LayoutParams.WRAP_CONTENT)
            updateViewHeight(binding.readingPageOverlay, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        private fun applyPlaceholder(item: WebtoonDisplayItem) {
            showPlaceholder(item)
        }

        fun refreshPlaceholderHeight() {
            val item = boundItem ?: return
            if (currentDecodedImage != null) return
            showPlaceholder(item)
        }

        private fun showPlaceholder(item: WebtoonDisplayItem) {
            val targetHeight = rememberedPageHeights[item.stableKey]
                ?: estimatePlaceholderHeight(item)
            updatePlaceholderHeight(targetHeight)
            binding.readingPagePlaceholder.visibility = View.VISIBLE
        }

        private fun estimatePlaceholderHeight(item: WebtoonDisplayItem): Int {
            val metrics = binding.root.resources.displayMetrics
            val width = binding.root.width.takeIf { it > 0 } ?: metrics.widthPixels
            val size = sourceSizeCache[item.path]
            val displaySourceHeight = size?.height ?: 0
            val estimated = size
                ?.takeIf { it.width > 0 && it.height > 0 }
                ?.takeIf { displaySourceHeight > 0 }
                ?.let {
                    (width.toFloat() * displaySourceHeight / it.width).roundToInt()
                }
                ?: (width * DEFAULT_PLACEHOLDER_HEIGHT_RATIO).toInt()
            val minHeight = (metrics.density * 240f).toInt()
            return estimated.coerceAtLeast(minHeight)
        }

        private fun updatePlaceholderHeight(height: Int) {
            val params = binding.readingPagePlaceholder.layoutParams
            if (params.height == height) return
            params.height = height
            binding.readingPagePlaceholder.layoutParams = params
        }

        private fun primeLayoutFromKnownSize(item: WebtoonDisplayItem) {
            val size = sourceSizeCache[item.path] ?: return
            if (size.width <= 0 || size.height <= 0) return
            currentImageWidth = size.width
            currentImageHeight = size.height
            updatePageHeightForImage(size.width, size.height)
        }

        private fun updatePageHeightForImage(sourceWidth: Int, sourceHeight: Int) {
            if (sourceWidth <= 0 || sourceHeight <= 0) return
            val targetWidth = resolveTargetWidth()
            val targetHeight = (targetWidth.toFloat() * sourceHeight / sourceWidth)
                .roundToInt()
                .coerceAtLeast(1)
            updateViewHeight(binding.root, targetHeight)
            updateViewHeight(binding.readingPageImage, targetHeight)
            updateViewHeight(binding.readingPageOverlay, targetHeight)
            updatePlaceholderHeight(targetHeight)
        }

        private fun updateViewHeight(view: View, height: Int) {
            val params = view.layoutParams ?: return
            if (params.height == height) return
            params.height = height
            view.layoutParams = params
        }

        private fun bindOverlay(translation: TranslationResult?) {
            val width = binding.readingPageImage.width.toFloat()
            val height = binding.readingPageImage.height.toFloat()
            if (width <= 0f || height <= 0f) {
                binding.readingPageOverlay.visibility = View.GONE
                binding.readingPageOverlay.setSourceBitmap(null)
                return
            }
            val resolved = resolveOverlayTranslation(translation)
            val lockedForEdit = isLockedEditPage()
            updateOverlayDisplayRect(width, height)
            binding.readingPageOverlay.setContentZoomScale(imageTransformController.currentContentZoomScale())
            binding.readingPageOverlay.setSourceBitmap(currentBitmap)
            binding.readingPageOverlay.setTranslations(resolved)
            binding.readingPageOverlay.setOffsets(if (lockedForEdit) lockedPageOffsets else emptyMap())
            binding.readingPageOverlay.setTouchPassthroughEnabled(!lockedForEdit)
            binding.readingPageOverlay.setEditScrollThroughEnabled(lockedForEdit)
            binding.readingPageOverlay.onOffsetChanged = if (lockedForEdit) { bubbleId, offsetX, offsetY ->
                onLockedBubbleOffsetChanged?.invoke(bubbleId, offsetX, offsetY)
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleRemove = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleRemove?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleTap = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleTap?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleResizeTap = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleResizeTap?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleLongPress = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleLongPress?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.setEditMode(lockedForEdit)
            binding.readingPageOverlay.visibility = if (resolved.bubbles.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun toggleDoubleTapZoom(x: Float, y: Float): Boolean {
            return imageTransformController.toggleDoubleTapZoom(x, y)
        }

        private fun updateOverlayDisplayRect(
            fallbackWidth: Float = binding.readingPageImage.width.toFloat(),
            fallbackHeight: Float = binding.readingPageImage.height.toFloat()
        ) {
            val rect = imageTransformController.computeImageDisplayRect()
                ?: RectF(0f, 0f, fallbackWidth, fallbackHeight)
            binding.readingPageOverlay.setDisplayRect(rect)
        }

        fun reloadTranslationOverlay() {
            val imageFile = boundFile ?: return
            if (!hasCurrentContent()) return
            overlayReloadJob?.cancel()
            overlayReloadJob = scope.launch {
                val imagePath = imageFile.absolutePath
                val translation = loadTranslationShared(imageFile)
                if (boundPath != imagePath) return@launch
                currentTranslation = normalizeTranslation(translation)
                bindOverlay(currentTranslation)
            }
        }

        private fun resolveOverlayTranslation(base: TranslationResult?): TranslationResult {
            val preferred = if (isLockedEditPage()) lockedPageTranslation ?: base else base
            return normalizeTranslation(preferred)
                ?: TranslationResult("", currentImageWidth, currentImageHeight, emptyList())
        }

        private fun normalizeTranslation(translation: TranslationResult?): TranslationResult? {
            if (translation == null) return null
            return if (translation.width == currentImageWidth && translation.height == currentImageHeight) {
                translation
            } else {
                translation.copy(width = currentImageWidth, height = currentImageHeight)
            }
        }

        private fun isLockedEditPage(): Boolean {
            return editModeEnabled &&
                boundPath != null &&
                boundPath == lockedPagePath
        }

        private fun resolveTargetWidth(): Int {
            return binding.readingPageImage.width
                .takeIf { it > 0 }
                ?: binding.root.width.takeIf { it > 0 }
                ?: binding.root.resources.displayMetrics.widthPixels
        }

        private fun resolveTargetHeight(): Int {
            return binding.readingPageImage.height
                .takeIf { it > 0 }
                ?: binding.root.height.takeIf { it > 0 }
                ?: binding.root.resources.displayMetrics.heightPixels
        }

        private fun hasCurrentContent(): Boolean {
            return currentDecodedImage != null || currentBitmap != null
        }

        private fun registerBoundHolder(path: String) {
            boundHolders.getOrPut(path) { linkedSetOf() }.add(this)
        }

        private fun unregisterBoundHolder(path: String) {
            val holders = boundHolders[path] ?: return
            holders.remove(this)
            if (holders.isEmpty()) {
                boundHolders.remove(path)
            }
        }
    }
}

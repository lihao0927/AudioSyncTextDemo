package com.lh.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.view.GestureDetectorCompat
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 高性能音频文本同步控件 (AudioSyncTextView)
 *
 * 主要功能：
 * 1. 支持超长文本显示 (10MB+)，采用“分块虚拟化渲染”技术，避免 OOM。
 * 2. 支持音频播放进度同步，高亮显示当前播放的文本段落。
 * 3. 支持自动跟随滚动，并确保高亮行居中显示。
 * 4. 支持自由滑动交互，用户操作时暂停自动滚动，体验接近原生 ScrollView。
 * 5. 支持点击文本跳转播放进度。
 */
class AudioSyncTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 画笔设置
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 50f // 默认字体大小
        color = Color.BLACK // 默认字体颜色
    }

    // 高亮颜色配置 (红色字体)
    private val highlightColor = Color.RED
    
    // 原始数据
    private var fullText: String = ""
    private var asrSegments: List<AsrSegment> = emptyList()
    
    // --- 分块 (Chunking) 核心逻辑 ---
    // 为了处理超大文本，将其切分为多个小块 (Chunk)，每个块单独计算布局。
    // 这样只有屏幕可见的块才会被渲染，极大降低内存占用。
    private val CHUNK_SIZE = 3000 // 每个块的大致字符数
    
    private data class TextChunk(
        val text: String,       // 当前块的文本内容
        val startOffset: Int,   // 当前块在全文中的起始索引
        val endOffset: Int,     // 当前块在全文中的结束索引
        var startY: Int = 0,    // 当前块在视图中的 Y 轴起始坐标
        var height: Int = 0,    // 当前块的高度
        var layout: StaticLayout? = null // 布局对象 (可按需缓存)
    )
    private val chunks = Collections.synchronizedList(mutableListOf<TextChunk>())
    
    // --- 滚动与交互控制 ---
    private val scroller = OverScroller(context) // 处理平滑滚动和惯性滑动
    private var isUserInteracting = false // 标记：用户是否正在触摸屏幕 (按下状态)
    private var isFlinging = false        // 标记：是否处于惯性滑动状态
    
    // 手势检测器：处理点击、滑动、惯性滑动
    private val gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // 用户按下时，立即停止当前的自动滚动动画
            scroller.forceFinished(true)
            isFlinging = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // 处理手指拖动滚动
            val maxY = max(0, contentHeight - height)
            // 计算新位置并限制在内容范围内 (0 ~ contentHeight)
            val newY = (scrollY + distanceY.toInt()).coerceIn(0, maxY)
            super@AudioSyncTextView.scrollTo(0, newY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            // 处理惯性滑动
            val maxY = max(0, contentHeight - height)
            scroller.fling(0, scrollY, 0, -velocityY.toInt(), 0, 0, 0, maxY)
            isFlinging = true
            postInvalidateOnAnimation()
            return true
        }
        
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 处理点击事件
            handleTap(e.x, e.y + scrollY)
            return true
        }
    })

    private var contentHeight = 0 // 内容总高度
    private var viewWidth = 0     // 视图实际宽度
    private val executor = Executors.newSingleThreadExecutor() // 后台线程池，用于计算布局
    
    // --- 状态记录 ---
    private var currentHighlightStart = -1 // 当前高亮段落的起始索引
    private var currentHighlightEnd = -1   // 当前高亮段落的结束索引
    private var lastHighlightSegmentIndex = -1 // 上一次高亮的段落下标 (用于去重更新)

    // --- 回调接口 ---
    // 点击文本时的回调，返回对应的音频时间戳 (毫秒)
    var onTextClickListener: ((Long) -> Unit)? = null

    init {
        // 启用垂直滚动条
        isVerticalScrollBarEnabled = true
        // 设置滚动条样式，显示在内容上方，不占用 Padding
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    }

    /**
     * 设置数据
     * @param text 完整的文本内容
     * @param segments 分词/分段数据列表
     */
    fun setData(text: String, segments: List<AsrSegment>) {
        this.fullText = text
        this.asrSegments = segments
        
        // 如果视图已经测量过宽度，立即计算分块；否则等待 onSizeChanged
        if (viewWidth > 0) {
            recalculateChunks()
        } else {
            requestLayout()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0) {
            viewWidth = w - paddingLeft - paddingRight
            // 宽度变化时，需要在后台重新计算所有分块的高度
            recalculateChunks()
        }
    }

    /**
     * 核心逻辑：后台计算分块
     * 将长文本切割成小块，测量每一块的高度，计算总高度。
     */
    private fun recalculateChunks() {
        if (fullText.isEmpty() || viewWidth <= 0) return

        chunks.clear()
        contentHeight = 0
        
        executor.submit {
            val length = fullText.length
            var currentOffset = 0
            
            val tempChunks = mutableListOf<TextChunk>()
            var tempY = 0

            while (currentOffset < length) {
                // 尝试切分 CHUNK_SIZE 长度
                var end = min(currentOffset + CHUNK_SIZE, length)
                
                // 避免截断单词：向后查找最近的空格
                if (end < length) {
                    val lastSpace = fullText.lastIndexOf(' ', end)
                    if (lastSpace > currentOffset) {
                        end = lastSpace + 1
                    }
                }

                val chunkText = fullText.substring(currentOffset, end)
                
                // 创建 StaticLayout 测量高度 (这里只用于测量，不保存 layout 对象以省内存)
                val layout = StaticLayout.Builder.obtain(chunkText, 0, chunkText.length, textPaint, viewWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.0f)
                    .setIncludePad(true)
                    .build()
                
                val chunkHeight = layout.height
                // 记录块信息：文本、偏移范围、Y坐标、高度
                tempChunks.add(TextChunk(chunkText, currentOffset, end, tempY, chunkHeight))
                
                tempY += chunkHeight
                currentOffset = end
            }

            chunks.addAll(tempChunks)
            contentHeight = tempY
            
            // 计算完成后刷新界面
            post {
                requestLayout()
                invalidate()
                // 唤醒滚动条，使其在数据加载后显示
                awakenScrollBars()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    // 处理 Scroller 的滚动动画
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            super.scrollTo(0, scroller.currY)
            postInvalidateOnAnimation()
        } else {
            // 动画结束，重置 fling 状态
            if (isFlinging) {
                isFlinging = false
            }
        }
    }

    /**
     * 核心逻辑：绘制
     * 仅绘制当前可视区域内的 Chunk
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (chunks.isEmpty()) return

        val scrollY = scrollY
        val viewHeight = height
        val visibleTop = scrollY
        val visibleBottom = scrollY + viewHeight

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), 0f)

        // 遍历查找可见块 (对于超大数据量，此处可用二分查找优化，目前线性遍历足够快)
        for (chunk in chunks) {
            val chunkTop = chunk.startY
            val chunkBottom = chunkTop + chunk.height

            // 跳过不可见的块
            if (chunkBottom < visibleTop) continue
            if (chunkTop > visibleBottom) break

            // 绘制可见块
            drawChunk(canvas, chunk, chunkTop)
        }

        canvas.restore()
    }

    /**
     * 绘制单个块
     * 如果该块包含高亮文本，则创建 SpannableString 应用颜色样式
     */
    private fun drawChunk(canvas: Canvas, chunk: TextChunk, topY: Int) {
        // 判断当前高亮范围是否与该块相交
        val isHighlighted = (currentHighlightStart < chunk.endOffset && currentHighlightEnd > chunk.startOffset)
        
        val charSequence: CharSequence = if (isHighlighted) {
            val spannable = SpannableString(chunk.text)
            
            // 计算该块内部的高亮范围
            val localStart = max(0, currentHighlightStart - chunk.startOffset)
            val localEnd = min(chunk.text.length, currentHighlightEnd - chunk.startOffset)
            
            if (localStart < localEnd) {
                // 应用红色字体高亮
                spannable.setSpan(ForegroundColorSpan(highlightColor), localStart, localEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            spannable
        } else {
            chunk.text
        }

        // 即时创建 Layout 进行绘制 (Android 系统对 StaticLayout 创建有优化，对于局部小段文本性能尚可)
        val layout = StaticLayout.Builder.obtain(charSequence, 0, charSequence.length, textPaint, viewWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(true)
            .build()

        canvas.save()
        canvas.translate(0f, topY.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    // 接管触摸事件
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> isUserInteracting = true // 用户开始操作
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isUserInteracting = false // 用户结束操作
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }
    
    // 处理点击：计算点击坐标对应的字符时间
    private fun handleTap(x: Float, absoluteY: Float) {
        // 1. 找到点击所在的块
        val chunk = chunks.find { absoluteY >= it.startY && absoluteY < (it.startY + it.height) } ?: return
        
        val localY = absoluteY - chunk.startY
        
        // 2. 重建 Layout 进行精确碰撞检测
        val layout = StaticLayout.Builder.obtain(chunk.text, 0, chunk.text.length, textPaint, viewWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(true)
            .build()
            
        // 3. 计算行号和字符偏移
        val line = layout.getLineForVertical(localY.toInt())
        val offset = layout.getOffsetForHorizontal(line, x)
        
        val globalOffset = chunk.startOffset + offset
        
        // 4. 查找对应的时间段
        val segment = asrSegments.find { globalOffset >= it.offsetStart && globalOffset < it.offsetEnd }
        segment?.let {
            onTextClickListener?.invoke(it.startTime)
        }
    }

    /**
     * 更新当前播放时间，触发高亮和自动滚动
     */
    fun updateTime(time: Long) {
        // 二分查找当前时间对应的段落
        val segmentIndex = findSegmentIndex(time)
        if (segmentIndex != -1 && segmentIndex != lastHighlightSegmentIndex) {
            lastHighlightSegmentIndex = segmentIndex
            val segment = asrSegments[segmentIndex]
            
            currentHighlightStart = segment.offsetStart
            currentHighlightEnd = segment.offsetEnd
            
            // 仅在用户未进行交互 (未触摸、未惯性滑动) 时自动滚动
            if (!isUserInteracting && !isFlinging) {
                ensureVisible(segment.offsetStart)
            }
            
            invalidate() // 重绘以更新高亮
        }
    }
    
    // 二分查找算法
    private fun findSegmentIndex(time: Long): Int {
        var left = 0
        var right = asrSegments.size - 1
        
        while (left <= right) {
            val mid = (left + right) / 2
            val midSeg = asrSegments[mid]
            
            if (time < midSeg.startTime) {
                right = mid - 1
            } else if (time > midSeg.endTime) {
                left = mid + 1
            } else {
                return mid
            }
        }
        return -1
    }
    
    /**
     * 确保指定位置可见，并尽可能居中
     */
    private fun ensureVisible(globalOffset: Int) {
        val chunk = chunks.find { globalOffset >= it.startOffset && globalOffset < it.endOffset } ?: return
        
        // --- 精确计算目标行位置 ---
        val localOffset = globalOffset - chunk.startOffset
        
        // 需要构建 Layout 才能获取精确的行坐标
        val layout = StaticLayout.Builder.obtain(chunk.text, 0, chunk.text.length, textPaint, viewWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(true)
            .build()
            
        val line = layout.getLineForOffset(localOffset)
        val lineTop = layout.getLineTop(line)
        val lineBottom = layout.getLineBottom(line)
        
        val absoluteLineTop = chunk.startY + lineTop
        val absoluteLineBottom = chunk.startY + lineBottom
        
        // 计算目标滚动位置：将该行置于视图垂直中心
        // 目标 ScrollY = 行中心点 - 视图高度的一半
        val lineCenter = (absoluteLineTop + absoluteLineBottom) / 2
        val targetY = lineCenter - height / 2
        
        val maxY = max(0, contentHeight - height)
        val finalY = targetY.coerceIn(0, maxY)
        
        // 如果需要滚动，启动平滑滚动动画 (800ms 较慢速度，更平滑)
        if (scroller.finalY != finalY) {
            scroller.startScroll(0, scrollY, 0, finalY - scrollY, 800)
            postInvalidateOnAnimation()
        }
    }

    override fun computeVerticalScrollRange(): Int {
        return contentHeight
    }

    override fun computeVerticalScrollExtent(): Int {
        return height
    }

    override fun computeVerticalScrollOffset(): Int {
        return scrollY
    }
}

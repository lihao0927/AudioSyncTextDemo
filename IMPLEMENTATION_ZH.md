# Android 高性能音频文本同步方案 (AudioSyncTextView)

## 1. 核心需求分析
*   **目标**: 实现一个类似 `ScrollView + TextView` 的文本显示控件，能够处理 10MB 级别的连续长文本，并支持与音频播放的精确同步。
*   **痛点**:
    *   原生 `TextView` 无法一次性渲染 10MB 文本 (导致 OOM 或 ANR)。
    *   原生 `ScrollView` 需要测量子 View 完整高度，同样面临性能瓶颈。
    *   需要实现“边播边滚”和“点击跳转”的双向交互。
    *   **自由滑动体验**: 用户在手动查看时，不应被自动滚动逻辑强行打断。

## 2. 解决方案：虚拟化自定义 View

我们实现了一个名为 `AudioSyncTextView` 的自定义 View，采用“分块渲染”和“按需布局”的策略。

### 核心机制

1.  **分块 (Chunking)**:
    *   将 10MB 的长文本切分为多个逻辑块 (Chunk)，每个块约 3000 字符。
    *   后台线程预计算每个块的高度 (`StaticLayout` 测量)，维护一个块的索引列表。
    *   **优势**: 避免了一次性创建巨大的 `StaticLayout`，解决了内存溢出问题。

2.  **虚拟化渲染 (Virtualization)**:
    *   在 `onDraw` 中，仅查找并绘制当前屏幕可见区域内的 Chunk。
    *   对于不可见的 Chunk，不进行任何绘制操作，也不持有其 `StaticLayout` 对象（惰性加载或即时创建），极大降低内存占用。

3.  **滑动与交互 (Interaction)**:
    *   **自由滑动**: 集成 `OverScroller` 和 `GestureDetector`，模拟原生的惯性滑动体验。
    *   **防干扰机制**: 引入 `isUserInteracting` 状态检测。当用户手指按在屏幕上或正在快速滑动 (Fling) 时，暂停自动跟随滚动，确保用户能自由查看上下文。
    *   **边界限制**: 实现了严格的滚动边界检查 (`clamp`)，防止内容滑出视图外。

4.  **双向同步**:
    *   **Audio -> Text**: 通过二分查找快速定位当前时间对应的文本段落，并绘制背景高亮。
    *   **Text -> Audio**: 点击文本时，通过坐标逆向映射到具体的字符偏移量，再查找对应的时间戳，回调给播放器。

## 3. 实现细节

### 数据结构
```kotlin
data class AsrSegment(
    val startTime: Long,
    val endTime: Long,
    val text: String,
    val offsetStart: Int, // 全局起始偏移
    val offsetEnd: Int    // 全局结束偏移
)
```

### 关键优化点
*   **后台计算**: `recalculateChunks` 在后台线程执行，防止 UI 卡顿。
*   **生命周期处理**: 无论数据先到还是 View 尺寸先确定，都能正确触发布局计算。
*   **高亮渲染**: 使用 `SpannableString` + `BackgroundColorSpan` 仅对当前可见的高亮块进行富文本处理，其余文本作为普通字符串绘制，性能最优。

## 4. 使用方法

### 布局文件 (XML)
```xml
<com.lh.myapplication.AudioSyncTextView
    android:id="@+id/syncTextView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F0F0F0" />
```

### 代码调用 (Kotlin)
```kotlin
// 1. 设置数据
syncTextView.setData(fullTextString, asrSegmentList)

// 2. 更新播放时间 (在播放器回调中调用)
syncTextView.updateTime(currentMillis)

// 3. 处理点击跳转
syncTextView.onTextClickListener = { targetTime ->
    mediaPlayer.seekTo(targetTime)
}
```

## 5. 常见问题排查
*   **文本不显示**: 检查是否在 `setData` 后 View 有正确的宽高。最新代码已修复此 Race Condition。
*   **滑动冲突**: 如果嵌套在 `ScrollView` 或 `ViewPager` 中，可能需要处理 `requestDisallowInterceptTouchEvent`。目前建议作为独立 View 使用。

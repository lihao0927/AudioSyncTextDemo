# Android Audio-Text Synchronization High-Performance Solution Proposal

## 1. Requirement Analysis
*   **Core Goal**: Synchronize audio playback with highlighting on a large continuous text (up to 10MB).
*   **Key Challenges**:
    *   **Performance**: Rendering 10MB of text in a standard `TextView` causes severe lag and OOM.
    *   **Layout**: Text is continuous without line breaks, requiring automatic wrapping.
    *   **Interaction**: Bidirectional sync (Time -> Highlight/Scroll, Click Text -> Seek Audio).

## 2. Proposed Architecture: Virtualized Custom View
We will implement a custom View named `AudioSyncTextView` (or `SubtitleSyncView`) that extends `android.view.View`.

### Why Custom View?
*   **Standard TextView**: Calculates layout for the entire text at once. For 10MB text, `StaticLayout` creation will block the UI thread for seconds or cause a crash.
*   **RecyclerView**: Good for items, but complex to handle continuous text wrapping across "item" boundaries without visual glitches.
*   **Custom View**: Allows us to:
    1.  **Virtualization**: Measure and draw *only* the visible portion of the text.
    2.  **Memory Management**: Load/unload text chunks dynamically.
    3.  **Fine-grained Control**: Precise control over highlighting and hit-testing for clicks.

## 3. Implementation Details

### A. Data Structure (`AsrModel`)
```kotlin
data class AsrSegment(
    val startTime: Long,
    val endTime: Long,
    val text: String,
    val globalStartIndex: Int, // Start index in the massive text string
    val globalEndIndex: Int    // End index
)
```

### B. Virtualization Strategy (The "Chunking" Approach)
Since we cannot layout 10MB text at once:
1.  **Pre-processing**: Split the massive text string into manageable logical "Chunks" (e.g., 5KB - 10KB segments) based on whitespace to avoid breaking words. This happens on a background thread.
2.  **Measurement**:
    *   Use `TextPaint` to measure text height.
    *   Maintain a `Y-offset` map for each Chunk.
    *   Total View Height = Sum of all Chunk heights.
3.  **Rendering (`onDraw`)**:
    *   Calculate `visibleRect` based on `getScrollY()`.
    *   Identify which Chunks intersect with `visibleRect`.
    *   Create/Recycle `StaticLayout` objects *only* for these visible Chunks.
    *   Draw the visible `StaticLayout`s onto the Canvas.

### C. Highlighting Logic
*   **Input**: `updateTime(long currentTime)`
*   **Search**: Use **Binary Search** on the `AsrSegment` list to find the active segment in O(log N) time.
*   **Painting**:
    *   Calculate the character range for the active segment.
    *   In `onDraw`, apply a `BackgroundColorSpan` or draw a background rect behind the specific lines of text corresponding to that range.
*   **Auto-Scroll**:
    *   If the active segment's Y-coordinate is outside the viewport, call `smoothScrollTo()`.

### D. Interaction (Click to Seek)
*   **Touch Handling**: Override `onTouchEvent`.
*   **Hit Testing**:
    *   Map `event.y` + `scrollY` to the specific Chunk.
    *   Use `StaticLayout.getOffsetForHorizontal()` to find the character index.
    *   Map character index back to `AsrSegment` to get `startTime`.
*   **Callback**: Trigger listener `onTextClicked(time)`.

### E. Scrolling
*   Use `Scroller` or `OverScroller` to handle fling and smooth scrolling physics.
*   Standard vertical scrolling behavior similar to `ScrollView`.

## 4. Functional Points Checklist

| ID | Feature | Description | Implementation Approach |
|----|---------|-------------|-------------------------|
| 1 | **Data Loading** | Load large text & ASR data without freezing UI | Background thread parsing + Chunking strategy |
| 2 | **Rendering** | Display continuous text with auto-wrap | Custom View with Virtualized `StaticLayout` |
| 3 | **Scrolling** | Smooth vertical scrolling | `Scroller` + `GestureDetector` |
| 4 | **Highlighting** | Highlight text based on current time | Binary search segment -> Draw background rect/span |
| 5 | **Auto-Scroll** | Scroll text to follow audio | Check highlight position -> `smoothScrollTo` |
| 6 | **Seek-by-Text** | Click text to jump audio | `onTouch` -> `getOffset` -> find time -> callback |
| 7 | **Seek-by-Bar** | Drag SeekBar -> update text | `SeekBar.onProgress` -> `view.updateTime` |
| 8 | **Performance** | No lag on 10MB text | Recycle Layouts, draw only visible area |
| 9 | **Robustness** | No Crashes (OOM) | Avoid loading full string in one Layout object |

## 5. Next Steps
1.  Confirm this plan.
2.  I will create the `AudioSyncTextView` class.
3.  I will create a helper to generate mock 10MB ASR data for testing.
4.  I will integrate it into `MainActivity`.

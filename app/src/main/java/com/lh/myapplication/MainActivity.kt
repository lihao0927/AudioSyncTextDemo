package com.lh.myapplication

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 主界面 (MainActivity)
 *
 * 演示 AudioSyncTextView 的使用方法：
 * 1. 后台生成 10MB 模拟文本数据。
 * 2. 模拟音频播放器的时间进度更新。
 * 3. 实现播放进度条拖拽控制。
 * 4. 响应自定义 View 的点击事件进行跳转。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var syncTextView: AudioSyncTextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvTime: TextView
    
    // 后台线程，用于生成模拟数据，避免阻塞 UI
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 播放器状态
    private var totalDuration = 0L
    private var isPlaying = false
    private var currentTime = 0L
    
    // 模拟播放器的定时器，每 100ms 更新一次进度
    private val playerRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                currentTime += 100
                if (currentTime > totalDuration) {
                    currentTime = totalDuration
                    isPlaying = false
                }
                
                updateUI()
                mainHandler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        syncTextView = findViewById(R.id.syncTextView)
        seekBar = findViewById(R.id.seekBar)
        tvTime = findViewById(R.id.tvTime)

        // 1. 在后台生成模拟数据 (模拟从网络或文件加载大文本)
        generateMockData()

        // 2. 监听文本点击事件 (Text -> Audio 跳转)
        syncTextView.onTextClickListener = { time ->
            currentTime = time
            updateUI()
            Toast.makeText(this, "Seek to: ${formatTime(time)}", Toast.LENGTH_SHORT).show()
            // 在真实项目中，此处应调用 mediaPlayer.seekTo(time)
        }

        // 3. 监听进度条拖动事件 (Seekbar -> Audio/Text 跳转)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentTime = progress.toLong()
                    updateUI()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { 
                // 拖动开始时暂停播放
                isPlaying = false 
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { 
                // 拖动结束后恢复播放
                isPlaying = true
                mainHandler.post(playerRunnable) 
            }
        })
        
        // 自动开始模拟播放
        isPlaying = true
        mainHandler.post(playerRunnable)
    }

    // 更新 UI：进度条、时间文本、同步视图
    private fun updateUI() {
        seekBar.progress = currentTime.toInt()
        tvTime.text = "${formatTime(currentTime)} / ${formatTime(totalDuration)}"
        // 核心调用：通知 View 更新当前时间，触发高亮和滚动
        syncTextView.updateTime(currentTime)
    }

    private fun generateMockData() {
        Toast.makeText(this, "Generating 10MB data...", Toast.LENGTH_SHORT).show()
        
        executor.submit {
            val sb = StringBuilder()
            val segments = ArrayList<AsrSegment>()
            var time = 0L
            
            // 目标生成约 10MB 的文本数据
            // "This is a sample text segment for testing synchronization. " 约 55 字符
            // 10,000,000 / 55 ~= 180,000 次循环
            val baseText = "This is a sample text segment for testing synchronization. "
            
            for (i in 0 until 180000) {
                val duration = 2000L // 假设每句话朗读 2 秒
                val text = "$baseText $i "
                
                val start = sb.length
                sb.append(text)
                val end = sb.length
                
                // 构建数据模型：时间区间 + 文本 + 字符偏移量
                segments.add(AsrSegment(time, time + duration, text, start, end))
                time += duration
            }
            
            totalDuration = time
            val fullText = sb.toString()
            
            // 回到主线程更新 UI
            mainHandler.post {
                seekBar.max = totalDuration.toInt()
                // 将数据注入到自定义 View
                syncTextView.setData(fullText, segments)
                Toast.makeText(this, "Data Loaded: ${fullText.length / 1024 / 1024} MB", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isPlaying = false
        mainHandler.removeCallbacks(playerRunnable)
    }
}

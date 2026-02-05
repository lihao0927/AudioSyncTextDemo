package com.lh.myapplication

data class AsrSegment(
    val startTime: Long, // Start time in milliseconds
    val endTime: Long,   // End time in milliseconds
    val text: String,    // The text content of this segment
    var offsetStart: Int = 0, // Global start index in the full text
    var offsetEnd: Int = 0    // Global end index in the full text
)

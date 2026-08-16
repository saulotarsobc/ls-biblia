package com.saulocosta.lsbiblia

data class BookDetail(
    val bookNumber: Int,
    val name: String,
    val chapters: List<Chapter>,
    val fetchedAt: Long,
)

data class Chapter(
    val track: Int,
    val title: String,
    val duration: Double,
    val files: Map<String, VideoFile>,
    val verses: List<Verse>,
)

data class VideoFile(
    val quality: String,
    val url: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val duration: Double,
    val checksum: String,
)

data class Verse(
    val number: Int,
    val start: Double,
    var end: Double,
    val label: String,
)

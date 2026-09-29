package com.german.dronnk.model

data class Song(
    val id: String? = null,
    val titulo: String? = null,
    val url: String? = null,
    val thumbnail: String? = null,
    val duracion: String? = null,
    val canal: String? = null,
    val archivo: String? = null,
    var isFavorite: Boolean = false,
    var isDownloaded: Boolean = false,
    var localPath: String? = null,
    val sourceUrl: String? = null
)

data class SearchResponse(val canciones: List<Song>)
data class DownloadResponse(
    val status: String?,
    val url: String?,
    val titulo: String?,
    val archivo: String?,
    val thumbnail: String?,
    val canal: String?,
    val duracion: String?,
    val message: String?
)

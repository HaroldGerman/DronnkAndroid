package com.german.dronnk.network

import com.german.dronnk.model.DownloadResponse
import com.german.dronnk.model.SearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface ApiService {
    @GET("buscar")
    suspend fun buscar(@Query("termino") termino: String): SearchResponse

    @GET("descargar")
    suspend fun prepararMp3(@Query("url") sourceUrl: String): DownloadResponse

    @GET("descargar-video")
    suspend fun prepararVideo(@Query("url") sourceUrl: String): DownloadResponse
}

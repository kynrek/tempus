package com.eddyizm.tempus.subsonic

import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.subsonic.base.ApiResponse
import com.eddyizm.tempus.subsonic.models.Bookmarks
import com.eddyizm.tempus.subsonic.models.BookmarksDeserializer
import com.eddyizm.tempus.subsonic.utils.CacheUtil
import com.eddyizm.tempus.subsonic.utils.EmptyDateTypeAdapter
import com.eddyizm.tempus.util.ClientCertManager
import com.eddyizm.tempus.util.ServerHeaders
import com.google.gson.GsonBuilder
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Date
import java.util.concurrent.TimeUnit

class RetrofitClient(subsonic: Subsonic) {
    val retrofit: Retrofit

    init {
        retrofit = Retrofit.Builder()
            .baseUrl(sanitizeBaseUrl(subsonic.url))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(getOkHttpClient())
            .build()
    }

    /**
     * Retrofit requires a syntactically valid base URL with an http/https scheme AND a parseable
     * host, throwing IllegalArgumentException otherwise. Two distinct failure modes reach here:
     *
     *  - unconfigured / blank / scheme-less address (timed-out first login, half-configured local
     *    address) -> "Expected URL scheme 'http' or 'https'" (issues #776, #758)
     *  - a scheme is present but the host is malformed, e.g. a typo'd FQDN with a space
     *    ("http://navi.example com") -> "Invalid URL host" (issue #795)
     *
     * Parsing with OkHttp (the same parser Retrofit uses) catches both: an unparseable address
     * yields null, so we fall back to an unreachable placeholder. The client then builds and
     * requests fail through the normal onFailure callbacks instead of taking down the process.
     */
    private fun sanitizeBaseUrl(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        val parsed = trimmed.toHttpUrlOrNull()
        return if (parsed != null && parsed.host.isNotEmpty()) {
            trimmed
        } else {
            PLACEHOLDER_BASE_URL
        }
    }

    /** Constants for [RetrofitClient]. */
    companion object {
        /** Syntactically valid but unreachable; used only when no usable server URL is configured. */
        private const val PLACEHOLDER_BASE_URL = "https://localhost/rest/"

        /**
         * Shared by every client: Gson caches the reflective adapter for [ApiResponse], which takes
         * hundreds of ms to build on a slow device, so a Gson per client rebuilt it on each first call.
         */
        private val gson by lazy {
            GsonBuilder()
                .registerTypeAdapter(Date::class.java, EmptyDateTypeAdapter())
                .registerTypeAdapter(Bookmarks::class.java, BookmarksDeserializer())
                .setLenient()
                .create()
        }

        /**
         * One pool for every client, so they reuse the same few connections to the server instead
         * of each keeping its own TLS sockets open. Each client keeps its own dispatcher, so how
         * many requests run at once is unchanged.
         */
        private val connectionPool by lazy { ConnectionPool() }

        /** OkHttp allows only one [Cache] per directory, so the clients share it. */
        private val cache by lazy { Cache(App.getContext().cacheDir, 10L * 1024 * 1024) }

        /** Builds the [ApiResponse] adapter off the main thread before the first request needs it. */
        @JvmStatic
        fun warmUp() {
            gson.getAdapter(ApiResponse::class.java)
        }
    }

    private fun getOkHttpClient(): OkHttpClient {
        val cacheUtil = CacheUtil(60, 60 * 60 * 24 * 30)

        // BrowsingClient 60
        // MediaAnnotationClient 0
        // MediaLibraryScanningClient 0
        // MediaRetrievalClient 0
        // PlaylistClient 0
        // PodcastClient 60
        // SearchClient 60
        // SystemClient 60
        // AlbumSongListClient 60

        return OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.MINUTES)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(getHttpLoggingInterceptor())
            .addInterceptor(cacheUtil.offlineInterceptor)
            .addNetworkInterceptor(ServerHeaders.createInterceptor())
            // .addNetworkInterceptor(cacheUtil.onlineInterceptor)
            .cache(cache)
            .connectionPool(connectionPool)
            .setupSsl()
            .build()
    }

    private fun getHttpLoggingInterceptor(): HttpLoggingInterceptor {
        val loggingInterceptor = HttpLoggingInterceptor()
        if (BuildConfig.DEBUG) {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.HEADERS)
        } else {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.NONE)
        }
        return loggingInterceptor
    }

    private fun OkHttpClient.Builder.setupSsl(): OkHttpClient.Builder {
        ClientCertManager.sslSocketFactory?.let { sslSocketFactory ->
            sslSocketFactory(sslSocketFactory, ClientCertManager.trustManager)
        }
        return this
    }
}
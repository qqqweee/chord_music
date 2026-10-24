package com.example.chord.playback

import android.content.Context
import android.util.Log
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class AudioStream(
    val url: String,
    val headers: Map<String, String>
)

class AudioStreamExtractor(context: Context) {

    private val appContext = context.applicationContext

    suspend fun extract(videoId: String): AudioStream =
        withContext(Dispatchers.IO) {
            val httpClient = createHttpClient()
            val innerTube = InnerTube(httpClient = httpClient)

            try {
                val configStore = RemotePlayerConfigStore(
                    httpClient = httpClient,
                    repository = AndroidPlayerConfigRepository(appContext)
                )

                val cipherService = YouTubeCipherService(
                    httpClient,
                    configStore
                )

                val extractor = InnerTubeExtractor(
                    configParser = YtConfigParserImpl(
                        httpClient,
                        innerTube,
                        configStore
                    ).withEmbeddedConfigFallback(),
                    cipherService = cipherService,
                    innerTube = innerTube
                )

                val hints = ContentHints(
                    wantVideo = false
                ).withStreamCapabilities(
                    allowHls = false,
                    allowSabr = false,
                    allowBoundedRange = false
                )

                val stream = extractor.extract(
                    videoId = videoId,
                    hints = hints,
                    audioQuality = AudioQuality.HIGH
                ) ?: error("InnerTubeX returned no playable audio stream.")

                check(stream.sabrBootstrap == null) {
                    "YouTube returned SABR, which this player cannot play."
                }

                check(stream.audioUrl.isNotBlank()) {
                    "YouTube returned an empty audio URL."
                }

                AudioStream(
                    url = stream.audioUrl,
                    headers = stream.headers
                )
            } finally {
                innerTube.close()
                httpClient.close()
            }
        }

    private fun createHttpClient() = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    isLenient = true
                }
            )
        }
    }

    private class AndroidPlayerConfigRepository(
        context: Context
    ) : PlayerConfigRepository {

        private val preferences = context.getSharedPreferences(
            "innertubex_player_config",
            Context.MODE_PRIVATE
        )

        override val enabled = true

        override val sourceUrl = PLAYER_CONFIG_URL

        override val defaultSourceUrl = PLAYER_CONFIG_URL

        override var cachedJson: String
            get() = preferences.getString("json", "").orEmpty()
            set(value) {
                preferences.edit().putString("json", value).apply()
            }

        override var cachedAtMs: Long
            get() = preferences.getLong("cached_at_ms", 0L)
            set(value) {
                preferences.edit().putLong("cached_at_ms", value).apply()
            }

        override var cachedSourceUrl: String
            get() = preferences.getString("source_url", "").orEmpty()
            set(value) {
                preferences.edit().putString("source_url", value).apply()
            }

        override var cachedEtag: String
            get() = preferences.getString("etag", "").orEmpty()
            set(value) {
                preferences.edit().putString("etag", value).apply()
            }

        companion object {
            private const val PLAYER_CONFIG_URL =
                "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        }
    }

    private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {

            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean
            ) = try {
                this@withEmbeddedConfigFallback.fetchConfig(
                    videoId,
                    useLoginCookies
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Remote player config failed; trying embedded config",
                    e
                )

                this@withEmbeddedConfigFallback.fetchEmbeddedConfig(
                    videoId,
                    useLoginCookies = false
                )
            }
        }

    private companion object {
        const val TAG = "ChordPlayer"
    }
}
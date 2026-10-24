
package com.example.chord.data.repository

import com.example.chord.data.model.Song
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.SearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class RadioPage(
    val songs: List<Song>,
    val continuation: String?
)

class MusicRepository {

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

    suspend fun searchSongs(query: String): List<Song> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) {
                "Search query cannot be empty."
            }

            val httpClient = createHttpClient()
            val innerTube = InnerTube(httpClient = httpClient)

            try {
                val response = innerTube.search(
                    YouTubeClient.WEB_REMIX,
                    query = query.trim()
                ).body<SearchResponse>()

                response.contents?.let { contents ->
                    listOfNotNull(
                        contents.sectionListRenderer,
                        contents.tabbedSearchResultsRenderer
                    )
                        .flatMap { it.findSongs() }
                        .distinctBy { it.videoId }
                }.orEmpty()
            } finally {
                innerTube.close()
                httpClient.close()
            }
        }

    suspend fun getRadioPage(
        seedVideoId: String,
        continuation: String? = null
    ): RadioPage = withContext(Dispatchers.IO) {
        require(seedVideoId.isNotBlank()) {
            "A seed video ID is required."
        }

        val httpClient = createHttpClient()
        val innerTube = InnerTube(httpClient = httpClient)

        try {
            val response = innerTube.next(
                client = YouTubeClient.WEB_REMIX,
                videoId = seedVideoId,
                playlistId = "RDAMVM$seedVideoId",
                continuation = continuation
            ).body<JsonElement>()

            RadioPage(
                songs = response.findRadioSongs()
                    .filterNot { it.videoId == seedVideoId }
                    .distinctBy { it.videoId },
                continuation = response.findContinuation()
            )
        } finally {
            innerTube.close()
            httpClient.close()
        }
    }

    private fun JsonElement.findSongs(): List<Song> {
        val songs = mutableListOf<Song>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val renderer =
                        element["musicResponsiveListItemRenderer"] as? JsonObject

                    if (renderer != null) {
                        val columns = renderer["flexColumns"] as? JsonArray

                        val texts = columns?.mapNotNull { column ->
                            val columnRenderer =
                                column.jsonObject[
                                    "musicResponsiveListItemFlexColumnRenderer"
                                ] as? JsonObject

                            columnRenderer?.get("text").readText()
                                .takeIf { it.isNotBlank() }
                        }.orEmpty()

                        val title = texts.firstOrNull().orEmpty()
                        val details = texts.drop(1).joinToString(" • ")
                        val videoId = renderer.findString("videoId").orEmpty()

                        if (
                            title.isNotBlank() &&
                            videoId.isNotBlank() &&
                            songs.none { it.videoId == videoId }
                        ) {
                            songs += Song(title, details, videoId)
                        }
                    }

                    element.values.forEach(::walk)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }

        walk(this)
        return songs
    }

    private fun JsonElement.findRadioSongs(): List<Song> {
        val songs = mutableListOf<Song>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val renderer =
                        element["playlistPanelVideoRenderer"] as? JsonObject

                    if (renderer != null) {
                        val videoId = renderer["videoId"]
                            ?.jsonPrimitive?.contentOrNull

                        val title = renderer["title"].readText()
                        val artist = renderer["longBylineText"].readText()
                            .ifBlank {
                                renderer["shortBylineText"].readText()
                            }

                        if (
                            !videoId.isNullOrBlank() &&
                            title.isNotBlank() &&
                            songs.none { it.videoId == videoId }
                        ) {
                            songs += Song(
                                title = title,
                                details = artist,
                                videoId = videoId
                            )
                        }
                    }

                    element.values.forEach(::walk)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }

        walk(this)
        return songs
    }

    private fun JsonElement?.readText(): String {
        val obj = this as? JsonObject ?: return ""

        val runs = obj["runs"] as? JsonArray
        if (runs != null) {
            return runs.mapNotNull { run ->
                (run as? JsonObject)
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.joinToString("").trim()
        }

        return obj["simpleText"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun JsonElement.findContinuation(): String? {
        fun walk(
            element: JsonElement,
            insideContinuation: Boolean = false
        ): String? {
            return when (element) {
                is JsonObject -> {
                    val inContinuation = insideContinuation ||
                            element.keys.any {
                                it.contains("continuation", ignoreCase = true)
                            }

                    if (inContinuation) {
                        val token = element["continuation"]
                            ?.jsonPrimitive?.contentOrNull
                            ?: element["token"]?.jsonPrimitive?.contentOrNull

                        if (!token.isNullOrBlank()) return token
                    }

                    element.values.firstNotNullOfOrNull {
                        walk(it, inContinuation)
                    }
                }

                is JsonArray -> element.firstNotNullOfOrNull {
                    walk(it, insideContinuation)
                }

                else -> null
            }
        }

        return walk(this)
    }

    private fun JsonElement.findString(key: String): String? =
        when (this) {
            is JsonObject ->
                this[key]?.jsonPrimitive?.contentOrNull
                    ?: values.firstNotNullOfOrNull { it.findString(key) }

            is JsonArray ->
                firstNotNullOfOrNull { it.findString(key) }

            else -> null
        }
}

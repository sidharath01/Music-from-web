package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class FmaExtension : ExtensionClient, SearchFeedClient, TrackClient {

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    // 🔑 Register at https://freemusicarchive.org/api and put your key here
    private val apiKey = "YOUR_FMA_API_KEY"
    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun fmaGet(url: String): String {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).await()
        return response.body.string()
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val url =
            "https://freemusicarchive.org/api/get/tracks.json?api_key=$apiKey&search=$query&limit=25"
        val body = fmaGet(url)
        val root = json.parseToJsonElement(body).jsonObject
        val tracksJson = root["dataset"]?.jsonArray ?: return Feed(listOf()) {
            PagedData.Single<Shelf> { emptyList() }.toFeed().data
        }

        val tracks = tracksJson.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["track_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = obj["track_title"]?.jsonPrimitive?.content ?: "Unknown"
            val artist = obj["artist_name"]?.jsonPrimitive?.content ?: ""
            val streamUrl = obj["track_listen_url"]?.jsonPrimitive?.content
                ?: obj["track_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val image = obj["track_image_file"]?.jsonPrimitive?.content

            Track(
                id = id,
                title = title,
                subtitle = artist,
                cover = image?.toImageHolder(),
                streamables = listOf(
                    Streamable.server(streamUrl, 0, "$id-src")
                ),
                extras = mapOf("streamUrl" to streamUrl)
            )
        }

        val shelf = Shelf.Lists.Tracks("fma_search", "Free Music Archive", tracks)
        return Feed(listOf()) {
            PagedData.Single<Shelf> { listOf(shelf) }.toFeed().data
        }
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media {
        val url = streamable.id
        return Streamable.InputProvider { _, _ ->
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).await()
            response.body.byteStream() to (response.body.contentLength())
        }.toSource(streamable.id).toMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
}

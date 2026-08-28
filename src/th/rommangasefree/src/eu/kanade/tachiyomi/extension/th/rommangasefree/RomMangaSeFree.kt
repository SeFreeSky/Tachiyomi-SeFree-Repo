package eu.kanade.tachiyomi.extension.th.rommangasefree

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Source
abstract class RomMangaSeFree : KeiSource() {

    override val supportsLatest: Boolean = true

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.newCall(GET("$baseUrl/manga/?order=update", headers)).execute().use { it.asJsoup() }
        val mangas = document.select("#wpop-items .serieslist.pop li").distinctBy { it.selectFirst(".leftseries h2 a")?.attr("abs:href") }
            .mapNotNull { li ->
                val link = li.selectFirst(".leftseries h2 a") ?: return@mapNotNull null
                val url = link.attr("abs:href").removePrefix(baseUrl)
                if (url.isEmpty()) return@mapNotNull null
                SManga.create().apply {
                    this.url = url
                    title = link.text().ifBlank { li.selectFirst(".imgseries img")?.attr("alt").orEmpty() }
                    thumbnail_url = li.selectFirst(".imgseries img")?.attr("abs:src")
                }
            }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchMangasPage(GET("$baseUrl/manga/?order=update", headers))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart() ?: ""
        val type = filters.firstInstanceOrNull<TypeFilter>()?.toUriPart() ?: ""
        val order = filters.firstInstanceOrNull<OrderFilter>()?.toUriPart() ?: "update"
        val request = when {
            query.isNotBlank() -> GET("$baseUrl/?s=${URLEncoder.encode(query, "UTF-8")}", headers)
            genre.isNotBlank() -> {
                val sb = StringBuilder("$baseUrl/genres/$genre/")
                if (order.isNotBlank() || type.isNotBlank()) sb.append("?")
                if (order.isNotBlank()) sb.append("order=").append(order)
                if (type.isNotBlank()) sb.append(if (order.isNotBlank()) "&type=" else "type=").append(URLEncoder.encode(type, "UTF-8"))
                GET(sb.toString(), headers)
            }
            else -> {
                val sb = StringBuilder("$baseUrl/manga/?order=$order")
                if (type.isNotBlank()) sb.append("&type=").append(URLEncoder.encode(type, "UTF-8"))
                GET(sb.toString(), headers)
            }
        }
        return fetchMangasPage(request)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) fetchDetails(manga) else manga
        val updatedChapters = if (fetchChapters) fetchChapters(manga) else chapters
        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    private suspend fun fetchDetails(manga: SManga): SManga {
        val document = client.newCall(GET(baseUrl + manga.url, headers)).execute().use { it.asJsoup() }
        return SManga.create().apply {
            title = document.selectFirst("h1.entry-title")?.text() ?: manga.title
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content")
                ?: document.selectFirst("img.ts-post-image")?.attr("abs:src")
            genre = document.select(".info-desc .mgen a[rel=tag]").joinToString { it.text() }
            description = document.selectFirst(".entry-content.entry-content-single")?.text()
            author = document.selectFirst(".tsinfo .imptdt .author i[itemprop=name]")?.text()
            artist = author
            document.select(".tsinfo .imptdt").forEach { row ->
                if (row.ownText().contains("Status")) {
                    status = getStatus(row.selectFirst("i")?.text().orEmpty())
                }
            }
            initialized = true
        }
    }

    private suspend fun fetchChapters(manga: SManga): List<SChapter> {
        val document = client.newCall(GET(baseUrl + manga.url, headers)).execute().use { it.asJsoup() }
        return document.select("#chapterlist li").filter { it.selectFirst("a") != null && !it.hasClass("dib") }
            .mapNotNull { row ->
                val link = row.selectFirst(".eph-num a") ?: return@mapNotNull null
                val url = link.attr("abs:href").removePrefix(baseUrl)
                val name = row.selectFirst(".chapternum")?.text()
                if (url.isEmpty() || name.isNullOrEmpty() || name.contains("{{")) return@mapNotNull null
                SChapter.create().apply {
                    this.url = url
                    this.name = name
                    chapter_number = row.attr("data-num").toFloatOrNull() ?: 0f
                    date_upload = parseDate(row.selectFirst(".chapterdate")?.text())
                }
            }
            .filter { it.url != manga.url }
    }

    private fun fetchMangasPage(request: Request): MangasPage {
        val document = client.newCall(request).execute().use { it.asJsoup() }
        val mangas = document.select("div.listupd .bsx > a").distinctBy { it.attr("abs:href") }
            .mapNotNull { link ->
                val url = link.attr("abs:href").removePrefix(baseUrl)
                if (url.isEmpty()) return@mapNotNull null
                SManga.create().apply {
                    this.url = url
                    title = link.selectFirst(".tt")?.text() ?: link.attr("title")
                    thumbnail_url = link.selectFirst("img.ts-post-image")?.attr("abs:src")?.substringBefore("?")
                }
            }
        return MangasPage(mangas, false)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        Log.d(TAG, "getPageList chapter.url=${chapter.url}")
        val resp = client.newCall(GET(baseUrl + chapter.url, headers)).execute()
        val html = resp.use {
            Log.d(TAG, "http=${it.code} len=${it.body?.contentLength() ?: -1}")
            it.body?.string().orEmpty()
        }
        val document = Jsoup.parse(html)
        val hasTs = "ts_reader.run(" in html
        Log.d(TAG, "hasTsReader=$hasTs htmlLen=${html.length}")
        val jsonText = extractBalancedJson(html, "ts_reader.run(")
        val images = jsonText?.let {
            runCatching { json.decodeFromString<TsReader>(it).sources.flatMap { s -> s.images } }.getOrNull()
        }.orEmpty()
        Log.d(TAG, "images=${images.size} firsts=${images.take(3)}")
        return if (images.isEmpty()) {
            val fallback = document.select("img[src*=img.rom-manga.com]").mapIndexed { i, img -> Page(i, img.attr("abs:src")) }
            Log.d(TAG, "fallback pages=${fallback.size}")
            fallback
        } else {
            images.mapIndexed { i, url -> Page(i, url) }
        }
    }

    /** Extracts the first balanced `{...}` JSON object that starts after `anchor` in `html`. */
    private fun extractBalancedJson(html: String, anchor: String): String? {
        val idx = html.indexOf(anchor)
        if (idx < 0) return null
        val open = html.indexOf('{', idx)
        if (open < 0) return null
        var depth = 0
        for (i in open until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(open, i + 1)
                }
            }
        }
        return null
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(GENRE_TITLE, GENRES),
        TypeFilter(TYPE_TITLE, TYPES),
        OrderFilter(ORDER_TITLE, ORDERS),
    )

    private fun getStatus(status: String) = when (status) {
        "Ongoing" -> SManga.ONGOING
        "Completed" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val time = runCatching { dateFormat.parse(date)?.time }.getOrNull() ?: return 0L
        val cal = Calendar.getInstance().apply { this.timeInMillis = time }
        // Guard against Buddhist-era years (BE = CE + 543).
        if (cal.get(Calendar.YEAR) > 2500) cal.add(Calendar.YEAR, -543)
        return cal.timeInMillis
    }

    companion object {
        private const val TAG = "RomMangaSeFree"
        private val dateFormat: SimpleDateFormat by lazy {
            SimpleDateFormat("MMMM d, yyyy", Locale("th", "TH"))
        }
        private val json = Json { ignoreUnknownKeys = true }

        private const val GENRE_TITLE = "Genre"
        private val GENRES = listOf(
            "Action" to "action",
            "Adult" to "adult",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Fantasy" to "fantasy",
            "Harem" to "harem",
            "Horror" to "horror",
            "Manhwa" to "manhwa",
            "Mature" to "mature",
            "Mystery" to "mystery",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sci-Fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life",
            "Supernatural" to "supernatural",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
        )
        private const val TYPE_TITLE = "Type"
        private val TYPES = listOf(
            "All" to "",
            "Manga" to "Manga",
            "Manhwa" to "Manhwa",
            "Manhua" to "Manhua",
            "Comic" to "Comic",
            "Doujinshi" to "Doujinshi",
            "Novel" to "Novel",
        )
        private const val ORDER_TITLE = "Order"
        private val ORDERS = listOf(
            "Update" to "update",
            "Latest" to "latest",
            "Most Viewed" to "views",
            "Title" to "title",
        )
    }
}

private open class UriPartFilter(displayName: String, private val vals: List<Pair<String, String>>, state: Int = 0) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
    fun toUriPart() = vals[state].second
}

private class GenreFilter(title: String, vals: List<Pair<String, String>>) : UriPartFilter(title, listOf("All" to "") + vals)

private class TypeFilter(title: String, vals: List<Pair<String, String>>) : UriPartFilter(title, vals)

private class OrderFilter(title: String, vals: List<Pair<String, String>>) : UriPartFilter(title, vals)

@Serializable
private data class TsReader(val sources: List<TsSource> = emptyList())

@Serializable
private data class TsSource(val images: List<String> = emptyList())

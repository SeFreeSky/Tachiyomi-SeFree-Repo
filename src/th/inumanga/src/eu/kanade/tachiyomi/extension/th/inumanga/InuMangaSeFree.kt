package eu.kanade.tachiyomi.extension.th.inumanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
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
abstract class InuMangaSeFree : KeiSource() {

    override val supportsLatest: Boolean = true

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.newCall(GET("$baseUrl/", headers)).execute().use { it.asJsoup() }
        val mangas = document.select("#wpop-items .serieslist.pop li").distinctBy { it.selectFirst(".leftseries h2 a")?.attr("abs:href") }
            .mapNotNull { li ->
                val link = li.selectFirst(".leftseries h2 a") ?: return@mapNotNull null
                val url = link.attr("abs:href").removePrefix(baseUrl)
                if (url.isEmpty()) return@mapNotNull null
                SManga.create().apply {
                    this.url = url
                    title = link.text().ifBlank { li.selectFirst(".imgseries img")?.attr("alt").orEmpty() }
                    thumbnail_url = li.selectFirst(".imgseries img")?.attr("abs:src")?.substringBefore("?")
                }
            }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchMangasPage(GET("$baseUrl/manga/?order=update&page=$page", headers))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/?s=${URLEncoder.encode(query, "UTF-8")}&page=$page"
        return fetchMangasPage(GET(url, headers))
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.newCall(GET(baseUrl + manga.url, headers)).execute().use { it.asJsoup() }

        val updated = if (fetchDetails) {
            SManga.create().apply {
                title = document.selectFirst("h1.entry-title")?.text() ?: manga.title
                thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content")
                    ?: document.selectFirst("img.ts-post-image")?.attr("abs:src")
                genre = document.select(".mgen a[rel=tag]").joinToString { it.text() }
                description = document.selectFirst(".entry-content.entry-content-single")?.text()
                document.select(".tsinfo .imptdt").forEach { row ->
                    val text = row.ownText()
                    if (text.contains("สถานะ")) {
                        status = getStatus(row.selectFirst("i")?.text().orEmpty())
                    }
                    if (text.contains("ผู้แต่ง") || text.contains("author", ignoreCase = true)) {
                        author = row.selectFirst("i")?.text()?.takeIf { it.isNotBlank() }
                    }
                }
                initialized = true
            }
        } else {
            manga
        }

        val fetched = if (fetchChapters) {
            document.select("#chapterlist li").filter { it.selectFirst("a") != null && !it.hasClass("dib") }
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
        } else {
            chapters
        }

        return SMangaUpdate(manga = updated, chapters = fetched)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val html = client.newCall(GET(baseUrl + chapter.url, headers)).execute().use { it.body?.string().orEmpty() }
        val jsonText = extractBalancedJson(html, "ts_reader.run(")
        val images = jsonText?.let {
            runCatching { json.decodeFromString<TsReader>(it).sources.flatMap { s -> s.images } }.getOrNull()
        }.orEmpty()
        return if (images.isEmpty()) {
            Jsoup.parse(html).select("img[src*=img.inu-manga]").mapIndexed { i, img -> Page(i, img.attr("abs:src")) }
        } else {
            images.mapIndexed { i, url -> Page(i, normalizeImageHost(url)) }
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    private fun fetchMangasPage(request: Request): MangasPage {
        val document = client.newCall(request).execute().use { it.asJsoup() }
        val mangas = document.select("div.listupd .bsx > a").distinctBy { it.attr("abs:href") }
            .mapNotNull { link ->
                val url = link.attr("abs:href").removePrefix(baseUrl)
                if (url.isEmpty()) return@mapNotNull null
                SManga.create().apply {
                    this.url = url
                    title = link.selectFirst(".tt")?.text()
                        ?: link.attr("title").ifBlank { link.selectFirst("img")?.attr("alt").orEmpty() }
                    thumbnail_url = link.selectFirst("img")?.attr("abs:src")?.substringBefore("?")
                }
            }
        return MangasPage(mangas, mangas.size >= MIN_GRID_PAGE)
    }

    /** img.inu-manga.com 301-redirects to img.inu-manga.net; serve the canonical host directly. */
    private fun normalizeImageHost(url: String): String = url.replace("img.inu-manga.com", "img.inu-manga.net")

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
        private const val MIN_GRID_PAGE = 20

        private val dateFormat: SimpleDateFormat by lazy {
            SimpleDateFormat("MMMM d, yyyy", Locale("th", "TH"))
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class TsReader(val sources: List<TsSource> = emptyList())

@Serializable
private data class TsSource(val images: List<String> = emptyList())

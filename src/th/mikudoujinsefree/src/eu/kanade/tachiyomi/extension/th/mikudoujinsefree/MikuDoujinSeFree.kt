package eu.kanade.tachiyomi.extension.th.mikudoujinsefree

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
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import java.net.URLEncoder

@Source
abstract class MikuDoujinSeFree : KeiSource() {

    override val supportsLatest: Boolean = true

    override suspend fun getPopularManga(page: Int): MangasPage = fetchBrowsePage(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchBrowsePage(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart() ?: ""
        return if (query.isNotBlank()) {
            fetchMangasPage(GET("$baseUrl/search/?keyword=${encodeQuery(query)}", headers), paginated = false)
        } else if (genre.isNotBlank()) {
            val url = if (page <= 1) "$baseUrl/genre/${encodePath(genre)}/" else "$baseUrl/genre/${encodePath(genre)}/?page=$page"
            fetchMangasPage(GET(url, headers), paginated = true)
        } else {
            fetchBrowsePage(page)
        }
    }

    /** No separate "popular" list exists; the homepage (latest additions) is the general list. */
    private suspend fun fetchBrowsePage(page: Int): MangasPage {
        val url = if (page <= 1) "$baseUrl/" else "$baseUrl/?page=$page"
        return fetchMangasPage(GET(url, headers), paginated = true)
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
            title = document.selectFirst(".card-header b")?.text() ?: manga.title
            thumbnail_url = document.selectFirst("div.sr-card-body img.img-thumbnail")?.attr("abs:src")
                ?: document.selectFirst("img[src*=/uploads/thumbnail/]")?.attr("abs:src")
            artist = document.selectFirst("a[href*=/artist/]")?.text()
            author = artist
            genre = document.select("a[href*=/genre/]").joinToString { it.text() }
            description = manga.description.orEmpty().ifBlank { "" }
            status = SManga.UNKNOWN
            initialized = true
        }
    }

    /** Some doujin are multi-episode; they expose a `table.table-episode`. Otherwise it's a single chapter holding all pages on the detail page. */
    private suspend fun fetchChapters(manga: SManga): List<SChapter> {
        val document = client.newCall(GET(baseUrl + manga.url, headers)).execute().use { it.asJsoup() }
        val elements = document.select("table.table-episode tr")
        if (elements.isEmpty()) {
            return listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Chapter 1"
                    chapter_number = 1f
                },
            )
        }
        return elements.mapIndexedNotNull { idx, element ->
            val a = element.selectFirst("td a") ?: return@mapIndexedNotNull null
            val url = a.attr("abs:href").removePrefix(baseUrl)
            if (url.isEmpty()) return@mapIndexedNotNull null
            SChapter.create().apply {
                this.url = url
                name = a.text()
                chapter_number = a.text().split(" ").lastOrNull()?.toFloatOrNull() ?: (idx + 1).toFloat()
            }
        }
    }

    private fun fetchMangasPage(request: Request, paginated: Boolean): MangasPage {
        val document = client.newCall(request).execute().use { it.asJsoup() }
        val mangas = document.select(".inz-col").distinctBy { it.selectFirst("a.inz-a")?.attr("abs:href") }
            .mapNotNull { col ->
                val link = col.selectFirst("a.inz-a") ?: return@mapNotNull null
                val url = link.attr("abs:href").removePrefix(baseUrl)
                if (url.isEmpty()) return@mapNotNull null
                SManga.create().apply {
                    this.url = url
                    title = link.selectFirst(".inz-title")?.text() ?: link.attr("title").orEmpty()
                    thumbnail_url = link.selectFirst("img.inz-img-thumbnail")?.attr("abs:src")
                }
            }
        val hasNextPage = paginated && mangas.size >= 12
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.newCall(GET(baseUrl + chapter.url, headers)).execute().use { it.asJsoup() }
        val images = document.select("#v-pills-tabContent img.lazy, #v-pills-tabContent img.page-img, #manga-content img[data-src]")
            .distinctBy { img -> img.absUrl("data-src").ifEmpty { img.absUrl("src") } }
        return images.mapIndexedNotNull { i, img ->
            val src = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            if (src.isEmpty()) return@mapIndexedNotNull null
            Page(i, src)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(GENRE_TITLE, GENRES),
    )

    private fun encodePath(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun encodeQuery(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val GENRE_TITLE = "Genre"
        private val GENRES = listOf(
            "Ahegao" to "Ahegao",
            "NTR" to "NTR",
            "SM" to "SM",
            "Uncensored" to "Uncensored",
            "Yaoi" to "Yaoi",
            "Yuri" to "Yuri",
            "ครอบครัว" to "ครอบครัว",
            "คอมดี้" to "คอมดี้",
            "ซิสเตอร์" to "ซิสเตอร์",
            "ดราม่า" to "ดราม่า",
            "ตำรวจ" to "ตำรวจ",
            "นมปานกลาง" to "นมปานกลาง",
            "นมเล็ก" to "นมเล็ก",
            "นมใหญ่" to "นมใหญ่",
            "นักเรียน" to "นักเรียน",
            "บังคับ" to "บังคับ",
            "ภาพสี" to "ภาพสี",
            "มิโกะ" to "มิโกะ",
            "ลักหลับ" to "ลักหลับ",
            "สะกดจิต" to "สะกดจิต",
            "สาวกีฬา" to "สาวกีฬา",
            "สาวผิวแทน" to "สาวผิวแทน",
            "สาวหูสัตว์" to "สาวหูสัตว์",
            "สาวออฟฟิศ" to "สาวออฟฟิศ",
            "สาวแว่น" to "สาวแว่น",
            "หนวด-สัตว์" to "หนวด-สัตว์",
            "หยุดเวลา" to "หยุดเวลา",
            "อาจารย์" to "อาจารย์",
            "ฮาร์ดคอร์" to "ฮาร์ดคอร์",
            "ฮาเร็ม" to "ฮาเร็ม",
            "เอลฟ์" to "เอลฟ์",
            "แฟนตาซี" to "แฟนตาซี",
            "โดนรุม" to "โดนรุม",
            "โรแมนติก" to "โรแมนติก",
            "โล" to "โล",
            "ไอดอล" to "ไอดอล",
        )
    }
}

private open class UriPartFilter(displayName: String, private val vals: List<Pair<String, String>>, state: Int = 0) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
    fun toUriPart() = vals[state].second
}

private class GenreFilter(title: String, vals: List<Pair<String, String>>) : UriPartFilter(title, listOf("All" to "") + vals)

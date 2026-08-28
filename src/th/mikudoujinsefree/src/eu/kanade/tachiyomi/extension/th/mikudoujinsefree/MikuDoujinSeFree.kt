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
import kotlinx.serialization.json.JsonElement
import org.jsoup.nodes.Document
import java.net.URLEncoder

@Source
abstract class MikuDoujinSeFree : KeiSource() {

    override val supportsLatest = true

    // The site serves HTTP 404 for any manga/episode path that does not end in '/'.
    private fun withSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    private fun pathOf(absHref: String): String = withSlash(absHref.removePrefix(baseUrl))

    private suspend fun getDocument(url: String): Document {
        val resp = client.newCall(GET(url, headers)).execute()
        resp.use { return it.asJsoup() }
    }

    // Popular == Latest (home is always newest-first)
    override suspend fun getPopularManga(page: Int): MangasPage = latestList(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = latestList(page)

    private suspend fun latestList(page: Int): MangasPage {
        val doc = getDocument("$baseUrl/?page=$page")
        return parseList(doc)
    }

    private fun parseList(doc: Document): MangasPage {
        val mangas = doc.select("div.col-6.inz-col").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            SManga.create().apply {
                url = pathOf(a.attr("abs:href"))
                title = a.selectFirst("div.inz-title")?.text() ?: ""
                thumbnail_url = a.selectFirst("img")?.attr("abs:src")
                initialized = false
            }
        }
        val hasNextPage = doc.selectFirst("button.btn-secondary") != null
        return MangasPage(mangas, hasNextPage)
    }

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "All",
                "Ahegao",
                "Yaoi",
                "Yuri",
                "NTR",
                "SM",
                "Uncensored",
                "ครอบครัว",
                "คอมดี้",
                "ซิสเตอร์",
                "ดราม่า",
                "ตำรวจ",
                "นมปานกลาง",
                "นมเล็ก",
                "นมใหญ่",
                "นักเรียน",
                "บังคับ",
                "ปีศาจ นางฟ้า แวมไพร์",
                "ผี-ซอมบี้",
                "พยาบาล",
                "พี่สาว น้องสาว",
                "ฟุตะนาริ",
                "ภาพสี",
                "มิโกะ",
                "ลักหลับ",
                "สลับร่าง ชาย หญิง",
                "สะกดจิต",
                "สาวกีฬา",
                "สาวดุ้น",
                "สาวผิวแทน",
                "สาวมอนสเตอร์",
                "สาวหูสัตว์",
                "สาวออฟฟิศ",
                "สาวเกล",
                "สาวแว่น",
                "สาวใหญ่/แม่บ้าน",
                "หนวด-สัตว์",
                "หยุดเวลา",
                "อาจารย์",
                "ฮาร์ดคอร์",
                "ฮาเร็ม",
                "เมด สาวใช้ สาวคาเฟ่",
                "เอลฟ์",
                "แฟนตาซี",
                "โซ",
                "โดนรุม",
                "โรแมนติก",
                "โล",
                "ไอดอล",
            ),
        )

    private class CategoryFilter :
        Filter.Select<String>(
            "Category",
            arrayOf("All", "โดจิน แปลไทย"),
        )

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(),
        CategoryFilter(),
    )

    private fun genreSlug(name: String): String = if (name != "สาวใหญ่/แม่บ้าน") {
        URLEncoder.encode(name, "UTF-8")
    } else {
        name
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith("http")) {
            val doc = getDocument(query)
            if (doc.selectFirst("div.sr-card-body div.col-md-4 img") != null && doc.selectFirst("div.col-6.inz-col") == null) {
                val manga = SManga.create().apply {
                    url = pathOf(query)
                    title = doc.title()
                    thumbnail_url = doc.selectFirst("div.sr-card-body div.col-md-4 img")?.attr("abs:src")
                    initialized = false
                }
                return MangasPage(listOf(manga), false)
            }
            return parseList(doc)
        }

        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val genreName = genre?.takeIf { it.state in 1 until it.values.size }?.values?.get(genre.state)
        val categoryName = category?.takeIf { it.state in 1 until it.values.size }?.values?.get(category.state)

        if (genreName != null) {
            val doc = getDocument("$baseUrl/genre/${genreSlug(genreName)}/?page=$page")
            return parseList(doc)
        }
        if (categoryName != null) {
            val doc = getDocument("$baseUrl/category/${URLEncoder.encode(categoryName, "UTF-8")}/?page=$page")
            return parseList(doc)
        }
        if (query.isNotBlank()) {
            val doc = getDocument("$baseUrl/genre/${genreSlug(query)}/?page=$page")
            return parseList(doc)
        }
        return latestList(page)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = withSlash(manga.url)
        val doc = getDocument("$baseUrl$url")
        val details = if (fetchDetails) parseDetails(doc, url) else manga
        val fetchedChapters = if (fetchChapters) parseChapters(doc, url) else chapters
        return SMangaUpdate(manga = details, chapters = fetchedChapters)
    }

    private fun parseDetails(doc: Document, url: String): SManga {
        val info = doc.selectFirst("div.sr-card-body")
        if (info == null) {
            return SManga.create().also { it.url = url }
        }
        return SManga.create().apply {
            this.url = url
            title = doc.title()
            author = info.select("div.col-md-8 p a.badge-secondary").getOrNull(2)?.ownText()
            artist = author
            genre = info.select("div.col-md-8 div.tags a").joinToString { it.text() }
            description = info.selectFirst("div.col-md-8")?.ownText()
            thumbnail_url = info.selectFirst("div.col-md-4 img")?.attr("abs:src")

            val tableEpisodes = doc.select("table.table-episode tr td a")
            status = if (tableEpisodes.isEmpty()) {
                SManga.COMPLETED
            } else {
                val hasEnd = tableEpisodes.any { it.text().split(" ").last() == "จบ" }
                if (hasEnd) SManga.COMPLETED else SManga.UNKNOWN
            }
            initialized = true
        }
    }

    private fun parseChapters(doc: Document, detailUrl: String): List<SChapter> {
        val elements = doc.select("table.table-episode tr")

        if (elements.isEmpty()) {
            // Single-chapter doujin: reader is inline on the manga page. Only expose a
            // chapter when the page actually carries a reader; seed/preview entries get
            // NO chapter instead of a dead one whose reader would be empty.
            val hasReader = doc.selectFirst("div#v-pills-tabContent") != null
            if (!hasReader) {
                return emptyList()
            }
            return listOf(
                SChapter.create().apply {
                    url = withSlash(detailUrl)
                    name = "Chapter 1"
                    chapter_number = 1.0f
                },
            )
        }

        return elements.mapIndexedNotNull { idx, element ->
            val a = element.selectFirst("td a") ?: return@mapIndexedNotNull null
            SChapter.create().apply {
                url = pathOf(a.attr("abs:href"))
                name = a.text()
                chapter_number = if (name.isEmpty()) {
                    0.0f
                } else {
                    name.split(" ").last().toFloatOrNull() ?: (idx + 1).toFloat()
                }
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = getDocument("$baseUrl${withSlash(chapter.url)}")
        val query = "div#v-pills-tabContent img.lazy, div#v-pills-tabContent img.page-img"
        return doc.select(query)
            .distinctBy { it.attr("abs:data-src").ifEmpty { it.attr("abs:src") } }
            .mapIndexedNotNull { index, img ->
                val url = if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
                if (url.isBlank()) null else Page(index, imageUrl = url)
            }
    }
}

package eu.kanade.tachiyomi.extension.th.mikudoujinsefree

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import kotlin.time.Duration.Companion.minutes

@Source
class MikuDoujinSeFree(
    override val lang: String,
    override val id: Long,
) : HttpSource() {

    override val name = "Miku-Doujin-SeFree"

    override val baseUrl = "https://miku-doujin.com"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(1.minutes)
        .readTimeout(1.minutes)
        .writeTimeout(1.minutes)
        .build()

    // The site serves HTTP 404 for any manga/episode path that does not end in '/'.
    // Keep a trailing slash on every scraped URL so chapter/reader requests resolve.
    private fun withSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    // Popular == Latest on this site (home is always newest-first)
    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.col-6.inz-col").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(a.attr("href"))
                url = withSlash(url)
                title = a.selectFirst("div.inz-title")?.text() ?: ""
                thumbnail_url = a.selectFirst("img")?.attr("abs:src")
                initialized = false
            }
        }
        val hasNextPage = document.selectFirst("button.btn-secondary") != null
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

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        CategoryFilter(),
    )

    // Same special-case as the reference source: URL-encoding the '/' in this genre
    // slug breaks the route, so it must be sent verbatim.
    private fun genreSlug(name: String): String = if (name != "สาวใหญ่/แม่บ้าน") {
        URLEncoder.encode(name, "UTF-8")
    } else {
        name
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.startsWith("http")) {
            return GET(query, headers)
        }

        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.state

        val genreName = if (genre != null && genre > 0) {
            filters.filterIsInstance<GenreFilter>().first().values[genre]
        } else {
            null
        }
        val categoryName = if (category != null && category > 0) {
            filters.filterIsInstance<CategoryFilter>().first().values[category]
        } else {
            null
        }

        if (genreName != null) {
            return GET("$baseUrl/genre/${genreSlug(genreName)}/?page=$page", headers)
        }
        if (categoryName != null) {
            return GET("$baseUrl/category/${URLEncoder.encode(categoryName, "UTF-8")}/?page=$page", headers)
        }
        if (query.isNotBlank()) {
            return GET("$baseUrl/genre/${genreSlug(query)}/?page=$page", headers)
        }
        return GET("$baseUrl/?page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val infoElement = document.selectFirst("div.sr-card-body") ?: return SManga.create()

        return SManga.create().apply {
            title = document.title()
            author = infoElement.select("div.col-md-8 p a.badge-secondary").getOrNull(2)?.ownText()
            artist = author
            genre = infoElement.select("div.col-md-8 div.tags a").joinToString { it.text() }
            description = infoElement.selectFirst("div.col-md-8")?.ownText()
            thumbnail_url = infoElement.selectFirst("div.col-md-4 img")?.attr("abs:src")

            val tableEpisodes = document.select("table.table-episode tr td a")
            status = if (tableEpisodes.isEmpty()) {
                SManga.COMPLETED
            } else {
                val hasEnd = tableEpisodes.any { it.text().split(" ").last() == "จบ" }
                if (hasEnd) SManga.COMPLETED else SManga.UNKNOWN
            }
            initialized = true
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val elements = document.select("table.table-episode tr")

        if (elements.isEmpty()) {
            // Single-chapter doujin: reader is inline on the manga page. Only expose a
            // chapter when the page actually carries reader images; entries without a
            // reader (new/seed listings) get NO chapter instead of a dead chapter whose
            // reader would be empty.
            val hasReader = document.selectFirst("div#v-pills-tabContent") != null
            if (!hasReader) {
                return emptyList()
            }
            return listOf(
                SChapter.create().apply {
                    url = withSlash(response.request.url.encodedPath)
                    name = "Chapter 1"
                    chapter_number = 1.0f
                },
            )
        }

        return elements.mapIndexedNotNull { idx, element ->
            val a = element.selectFirst("td a") ?: return@mapIndexedNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(a.attr("href"))
                url = withSlash(url)
                name = a.text()
                chapter_number = if (name.isEmpty()) {
                    0.0f
                } else {
                    name.split(" ").last().toFloatOrNull() ?: (idx + 1).toFloat()
                }
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val query = "div#v-pills-tabContent img.lazy, div#v-pills-tabContent img.page-img"
        return document.select(query)
            .distinctBy { it.attr("abs:data-src").ifEmpty { it.attr("abs:src") } }
            .mapIndexedNotNull { index, img ->
                val url = if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
                if (url.isBlank()) null else Page(index, imageUrl = url)
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}

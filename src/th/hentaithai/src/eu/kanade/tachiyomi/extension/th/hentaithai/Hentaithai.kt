package eu.kanade.tachiyomi.extension.th.hentaithai

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
import kotlinx.serialization.json.JsonElement
import org.jsoup.nodes.Document

@Source
abstract class Hentaithai : KeiSource() {

    override val supportsLatest = true

    private val coverRegex = Regex("""background-image:url\('([^']+)'\)""")
    private val topicIdRegex = Regex("""topic=(\d+)""")

    private suspend fun getDocument(url: String): Document {
        val resp = client.newCall(GET(url, headers)).execute()
        resp.use { return it.asJsoup() }
    }

    private fun pathOf(absHref: String): String = absHref.removePrefix(baseUrl).substringBeforeLast("#")

    // Latest: the homepage grid, paginated by /page-N
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val pageUrl = if (page <= 1) "$baseUrl/" else "$baseUrl/page-$page"
        val doc = getDocument(pageUrl)
        val mangas = doc.select("a[class*=number-odd], a[class*=number-even]").map { card ->
            SManga.create().apply {
                url = pathOf(card.attr("abs:href"))
                title = card.attr("title").ifBlank { card.selectFirst(".font_name")?.text().orEmpty() }
                thumbnail_url = card.previousElementSibling()?.let { style ->
                    coverRegex.find(style.html())?.groupValues?.get(1)
                }
                initialized = false
            }
        }
        return MangasPage(mangas, mangas.size == GRID_PAGE_SIZE)
    }

    // Popular: the top-doujin board (forum topic rows; no covers)
    override suspend fun getPopularManga(page: Int): MangasPage {
        val doc = getDocument("$baseUrl/top-doujin/")
        val seen = mutableSetOf<Int>()
        val mangas = doc.select("a[href*=\"topic=\"]").mapNotNull { row ->
            val id = topicIdRegex.find(row.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            SManga.create().apply {
                url = "/t$id"
                title = row.text()
                initialized = false
            }
        }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val clean = query.trim()
        if (clean.isEmpty()) return MangasPage(emptyList(), false)

        // URL / topic intent: pasting a hentaithai link resolves to that doujin.
        if (clean.startsWith("http") || clean.startsWith("/t")) {
            val url = if (clean.startsWith("http")) clean else "$baseUrl$clean"
            val manga = runCatching { parseTopicManga(getDocument(url), pathOf(url)) }.getOrNull() ?: return MangasPage(emptyList(), false)
            return MangasPage(listOf(manga), false)
        }

        // The site has no native keyword search (its search box delegates to Google).
        return MangasPage(emptyList(), false)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = manga.url
        val doc = getDocument("$baseUrl$url")
        val details = if (fetchDetails) parseTopicManga(doc, url) else manga
        val fetched = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    this.url = url
                    name = "Chapter 1"
                    chapter_number = 1.0f
                },
            )
        } else {
            chapters
        }
        return SMangaUpdate(manga = details, chapters = fetched)
    }

    private fun parseTopicManga(doc: Document, url: String): SManga = SManga.create().apply {
        this.url = url
        title = doc.selectFirst("h1")?.text() ?: doc.title()
        description = doc.selectFirst("meta[name=description]")?.attr("content")
        thumbnail_url = pageImages(doc).firstOrNull()
        status = SManga.COMPLETED
        initialized = true
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = getDocument("$baseUrl${chapter.url}")
        return pageImages(doc).mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    // The reader is inline on the topic page: every /thai/... webp rendered as an <img>.
    private fun pageImages(doc: Document): List<String> = doc.select("img[src*=\"/thai/\"]")
        .mapNotNull { img ->
            val src = img.attr("abs:src")
            if (src.isNotBlank() && src.contains("hentaithai.net/thai/")) src else null
        }
        .distinct()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    companion object {
        private const val GRID_PAGE_SIZE = 36
    }
}

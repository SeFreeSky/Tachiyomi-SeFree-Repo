package eu.kanade.tachiyomi.extension.th.niceoppaisefree

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
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Niceoppai : KeiSource() {

    override val supportsLatest: Boolean = true

    override suspend fun getPopularManga(page: Int): MangasPage = fetchMangasPage(GET("$baseUrl/manga_list/all/any/most-popular/$page", headers))

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchMangasPage(GET("$baseUrl/manga_list/all/any/last-updated/$page", headers))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart() ?: ""
        val type = filters.firstInstanceOrNull<MediaTypeFilter>()?.toUriPart() ?: ""
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart() ?: ""
        val order = filters.firstInstanceOrNull<OrderByFilter>()?.toUriPart() ?: "last-updated"
        val request = when {
            query.isNotBlank() -> GET("$baseUrl/manga_list/search/${urlEncode(query)}/$order/$page", headers)
            status.isNotBlank() -> GET("$baseUrl/manga_list/tag/${urlEncode(status)}/$page", headers)
            else -> {
                val seg1 = when {
                    genre.isNotBlank() -> genre
                    type.isNotBlank() -> type
                    else -> "all"
                }
                GET("$baseUrl/manga_list/${urlEncode(seg1)}/any/$order/$page", headers)
            }
        }
        return fetchMangasPage(request)
    }

    private fun urlEncode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private suspend fun fetchMangasPage(request: Request): MangasPage {
        val document = client.newCall(request).execute().use { it.asJsoup() }
        val mangas = document.select("div.fcard").mapNotNull { card ->
            val title = card.selectFirst("a.fcard__title") ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title.text()
                url = title.attr("abs:href").removePrefix(baseUrl)
                thumbnail_url = card.selectFirst("img.cover__img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.select("ul.pgg li a").any { it.text() == "Next" }
        return MangasPage(mangas, hasNextPage)
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
            title = document.selectFirst(".series__info h1")?.text() ?: manga.title
            thumbnail_url = document.selectFirst("img[itemprop=image]")?.attr("abs:src")
            genre = document.select("a.chip--genre").joinToString { it.text() }
            description = document.selectFirst("p.series__syn")?.text()
            document.select(".series__facts .fact").forEach { fact ->
                when (fact.selectFirst("span")?.text()) {
                    "ผู้แต่ง" -> {
                        author = fact.selectFirst("b a")?.text() ?: fact.selectFirst("b")?.ownText()
                        artist = author
                    }
                    "สถานะ" -> status = getStatus(fact.selectFirst("b")?.ownText().orEmpty())
                }
            }
            initialized = true
        }
    }

    private suspend fun fetchChapters(manga: SManga): List<SChapter> {
        val document = client.newCall(GET(baseUrl + manga.url, headers)).execute().use { it.asJsoup() }
        val chapters = parseChapters(document).toMutableList()
        val pageUrls = document.select("a[href*=chapter-list]").map { it.attr("abs:href") }.distinct()
        pageUrls.forEach { pageUrl ->
            client.newCall(GET(pageUrl, headers)).execute().use { res ->
                chapters += parseChapters(res.asJsoup())
            }
        }
        return chapters
    }

    private fun parseChapters(document: Document): List<SChapter> = document.select("a.chrow").mapNotNull { row ->
        val url = row.attr("abs:href").removePrefix(baseUrl)
        if (url.isEmpty()) return@mapNotNull null
        SChapter.create().apply {
            this.url = url
            val numberText = row.selectFirst(".chrow__t")?.text()
            name = numberText ?: ""
            chapter_number = numberText?.toFloatOrNull() ?: 0f
            date_upload = parseDate(row.selectFirst(".chrow__d")?.text())
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.newCall(GET(baseUrl + chapter.url, headers)).execute().use { it.asJsoup() }
        return document.select("img[src^=\"https://image\"]").mapIndexed { i, img ->
            Page(i, imageUrl = if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src"))
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        MediaTypeFilter(MEDIA_TYPE_FILTER_TITLE, MEDIA_TYPE_FILTER_VALUES),
        GenreFilter(GENRE_FILTER_TITLE, GENRE_FILTER_VALUES),
        StatusFilter(),
        OrderByFilter(
            ORDER_BY_FILTER_TITLE,
            ORDER_BY_FILTER_OPTIONS.zip(ORDER_BY_FILTER_OPTIONS_VALUES).toList(),
            2,
        ),
    )

    private fun getStatus(status: String) = when (status) {
        "ยังไม่จบ" -> SManga.ONGOING
        "จบแล้ว" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return runCatching { dateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L)
    }

    companion object {
        private val dateFormat: SimpleDateFormat by lazy {
            SimpleDateFormat("MMM dd, yyyy", Locale.US)
        }
        private const val MEDIA_TYPE_FILTER_TITLE = "Media type"
        private val MEDIA_TYPE_FILTER_VALUES = listOf(
            "All" to "",
            "Manga" to "Manga",
            "Manhwa" to "Manhwa",
            "Manhua" to "Manhua",
            "Comic" to "Comic",
            "Novel" to "Novel",
            "Webtoon" to "Webtoon",
            "Doujinshi" to "Doujinshi",
            "One Shot" to "One Shot",
        )
        private const val GENRE_FILTER_TITLE = "Genre"
        private val GENRE_FILTER_VALUES = listOf(
            "All" to "",
            "Action" to "Action",
            "Adult" to "Adult",
            "Adventure" to "Adventure",
            "Comedy" to "Comedy",
            "Cooking" to "Cooking",
            "Drama" to "Drama",
            "Ecchi" to "Ecchi",
            "Fantasy" to "Fantasy",
            "Gender Bender" to "Gender Bender",
            "Harem" to "Harem",
            "Historical" to "Historical",
            "Horror" to "Horror",
            "Isekai" to "Isekai",
            "Josei" to "Josei",
            "Martial Arts" to "Martial Arts",
            "Mature" to "Mature",
            "Mecha" to "Mecha",
            "Military" to "Military",
            "Murim" to "Murim",
            "Mystery" to "Mystery",
            "Psychological" to "Psychological",
            "Reincarnation" to "Reincarnation",
            "Revenge" to "Revenge",
            "Romance" to "Romance",
            "School Life" to "School Life",
            "Sci Fi" to "Sci Fi",
            "Seinen" to "Seinen",
            "Shoujo" to "Shoujo",
            "Shoujo Ai" to "Shoujo Ai",
            "Shounen" to "Shounen",
            "Shounen Ai" to "Shounen Ai",
            "Slice of Life" to "Slice of Life",
            "Sport" to "Sport",
            "Supernatural" to "Supernatural",
            "Survival" to "Survival",
            "Thriller" to "Thriller",
            "Tragedy" to "Tragedy",
            "Yuri" to "Yuri",
            "Zombie" to "Zombie",
        )
        private const val ORDER_BY_FILTER_TITLE = "Order By"
        private val ORDER_BY_FILTER_OPTIONS = arrayOf(
            "Name (A-Z)", "Name (Z-A)", "Last Updated", "Oldest Updated", "Most Popular",
            "Most Popular (Weekly)", "Most Popular (Monthly)", "Least Popular",
            "Last Added", "Early Added", "Top Rating", "Lowest Rating",
        )
        private val ORDER_BY_FILTER_OPTIONS_VALUES = arrayOf(
            "name-az", "name-za", "last-updated", "oldest-updated", "most-popular",
            "most-popular-weekly", "most-popular-monthly", "least-popular",
            "last-added", "early-added", "top-rating", "lowest-rating",
        )
    }
}

private open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>, state: Int = 0) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
    fun toUriPart() = vals[state].second
}

private class OrderByFilter(title: String, options: List<Pair<String, String>>, state: Int = 0) : UriPartFilter(title, options.toTypedArray(), state)

private class MediaTypeFilter(title: String, vals: List<Pair<String, String>>) : UriPartFilter(title, vals.toTypedArray())

private class GenreFilter(title: String, vals: List<Pair<String, String>>) : UriPartFilter(title, vals.toTypedArray())

private class StatusFilter : UriPartFilter("Status", listOf("All" to "", "Ongoing" to "ยังไม่จบ", "Completed" to "จบแล้ว").toTypedArray())

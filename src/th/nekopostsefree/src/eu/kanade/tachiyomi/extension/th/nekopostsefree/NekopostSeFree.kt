package eu.kanade.tachiyomi.extension.th.nekopostsefree

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class NekopostSeFree : KeiSource() {

    override val supportsLatest: Boolean = true

    private val projectDataEndpoint = "/api/project/detail2"
    private val fileHost = "https://www.osemocphoto.com"

    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("th")) }

    private val apiHeaders = headersBuilder()
        .set("Accept", "*/*")
        .set("Content-Type", "application/json")
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun getStatus(status: Int) = when (status) {
        1 -> SManga.ONGOING
        2 -> SManga.COMPLETED
        3 -> SManga.LICENSED
        else -> SManga.UNKNOWN
    }

    override suspend fun getPopularManga(page: Int): MangasPage = projectRequest(
        "list/popular",
        UpdatesRequest("mc", PagingInfo(1, POPULAR_PAGE_SIZE)),
    ) { resp -> parseProjectList(resp, null, false) }

    override suspend fun getLatestUpdates(page: Int): MangasPage = projectRequest(
        "latest",
        UpdatesRequest("m", PagingInfo(page, LATEST_PAGE_SIZE)),
    ) { resp ->
        val chapterList = parse<RawLatestChapterList>(resp)
        if (chapterList.listChapter.isNullOrEmpty()) {
            MangasPage(emptyList(), false)
        } else {
            val mangaList = chapterList.listChapter.map {
                SManga.create().apply {
                    url = it.pid.toString()
                    title = it.projectName
                    status = getStatus(it.status.toInt())
                    thumbnail_url = buildCoverUrl(it.pid.toString(), it.coverVersion)
                    initialized = false
                }
            }
            MangasPage(mangaList, mangaList.size == LATEST_PAGE_SIZE)
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val cleanQuery = query.trim()
        val projectMatch = Regex("""nekopost\.net/manga/(\d+)""").find(cleanQuery)
        val editorMatch = Regex("""nekopost\.net/editor/(\d+)""").find(cleanQuery)
        return when {
            projectMatch != null -> {
                val requestBody = json.encodeToString(ProjectRequestBody(projectMatch.groupValues[1].toInt())).toRequestBody(jsonMedia)
                val projectInfo = post(projectDataEndpoint, requestBody) { resp ->
                    if (resp.peekBody(1024).string().contains("\"projectInfo\":null")) null else parse<RawProjectInfo>(resp)
                }
                projectInfo?.let { MangasPage(listOf(mangaFromProjectInfo(it)), false) } ?: MangasPage(emptyList(), false)
            }
            editorMatch != null -> {
                val editorId = editorMatch.groupValues[1]
                val resp = client.newCall(GET("$baseUrl/api/editor/project/$editorId", apiHeaders)).execute()
                resp.use {
                    val list = json.decodeFromString<List<EditorProject>>(it.body?.string().orEmpty())
                    val mangaList = list.filter { m -> m.projectType == "m" }.map { project ->
                        SManga.create().apply {
                            url = project.pid.toString()
                            title = project.projectName
                            status = project.status
                            thumbnail_url = buildCoverUrl(project.pid.toString(), project.coverVersion)
                            initialized = false
                        }
                    }
                    MangasPage(mangaList, false)
                }
            }
            else -> {
                val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
                val genreSlug = genreFilter?.takeIf { it.state > 0 && it.state < genreFilter.values.size }?.values?.get(genreFilter.state)?.let(::genreSlugOf)
                val mediaFilter = filters.filterIsInstance<MediaTypeFilter>().firstOrNull()
                val projectType = mediaFilter?.takeIf { it.state > 0 && it.state < mediaFilter.values.size }?.values?.get(mediaFilter.state)?.let(::mediaTypeOf)
                val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
                val orderBy = sortFilter?.takeIf { it.state > 0 && it.state < sortFilter.values.size }?.values?.get(sortFilter.state)?.let(::orderByOf)
                val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
                val status = statusFilter?.state ?: 0

                val body = SearchRequest(
                    keyword = cleanQuery,
                    status = status,
                    paging = PagingInfo(page, SEARCH_PAGE_SIZE),
                    projectType = projectType,
                    genre = genreSlug?.let { listOf(it) },
                    orderBy = orderBy,
                )
                projectRequest("search", body) { resp -> parseProjectList(resp, setOf("m"), true) }
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val pid = manga.url.filter(Char::isDigit).trim().toIntOrNull() ?: return SMangaUpdate(manga, chapters)
        val info = post(projectDataEndpoint, json.encodeToString(ProjectRequestBody(pid)).toRequestBody(jsonMedia)) { resp ->
            parse<RawProjectInfo>(resp)
        }
        val p = info.projectInfo.project

        val updated = if (fetchDetails) {
            if (getStatus(p.status) == SManga.LICENSED) {
                manga
            } else {
                SManga.create().apply {
                    url = p.projectId.toString()
                    title = p.projectName
                    artist = p.artistName
                    author = p.authorName
                    description = p.info
                    status = getStatus(p.status)
                    thumbnail_url = buildCoverUrl(p.projectId.toString(), p.coverVersion)
                    genre = info.projectInfo.category?.joinToString(", ") { it.categoryName }.orEmpty()
                    initialized = true
                }
            }
        } else {
            manga
        }

        val fetched = if (fetchChapters) {
            info.projectInfo.chapter.orEmpty().map {
                SChapter.create().apply {
                    url = "${p.projectId}/${it.chapterId}/${p.projectId}_${it.chapterId}.json"
                    name = it.chapterName
                    chapter_number = it.chapterNo.toFloat()
                    date_upload = dateFormat.parse(it.publishDate.value)?.time ?: 0L
                    scanlator = it.providerName
                }
            }
        } else {
            chapters
        }

        return SMangaUpdate(manga = updated, chapters = fetched)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // osemocphoto is migrating chapters from www. -> fs.; each chapter's manifest
        // and images live on ONE host right now, the other returns a placeholder JPEG.
        val hosts = listOf("https://www.osemocphoto.com", "https://fs.osemocphoto.com")
        for (host in hosts) {
            val body = try {
                client.newCall(GET("$host/collectManga/${chapter.url}", headers)).execute().use { it.body?.string().orEmpty() }
            } catch (e: Exception) {
                continue
            }
            if (body.isBlank() || !body.startsWith("{")) continue
            runCatching {
                val info = json.decodeFromString<RawChapterInfo>(body)
                val base = "$host/collectManga/${info.projectId}/${info.chapterId}"
                return info.pageItem.mapIndexed { index, page ->
                    Page(index = index, imageUrl = "$base/${page.pageName ?: page.fileName}")
                }
            }
        }
        return emptyList()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        MediaTypeFilter(),
        StatusFilter(),
        GenreFilter(),
        SortFilter(),
    )

    private fun genreSlugOf(label: String): String = when (label) {
        "School Life" -> "school_life"
        "Slice of Life" -> "slice_of_life"
        "Sci-fi" -> "sci_fi"
        "Second Life" -> "second_life"
        else -> label
    }

    private fun mediaTypeOf(label: String): String = when (label) {
        "Manga" -> "m"
        "Comic" -> "c"
        "Novel" -> "n"
        else -> "m"
    }

    private fun orderByOf(label: String): String = when (label) {
        "Latest Update" -> "updateDate"
        "Newest" -> "createDesc"
        "Random" -> "random"
        else -> "updateDate"
    }

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "All",
                "Isekai",
                "Fantasy",
                "Romance",
                "School Life",
                "Shounen",
                "Shoujo",
                "Mystery",
                "Action",
                "Comedy",
                "Drama",
                "Horror",
                "Seinen",
                "Harem",
                "Slice of Life",
                "Adventure",
                "Sport",
                "Sci-fi",
                "Yaoi",
                "Yuri",
                "Gourmet",
                "Second Life",
                "Trap",
            ),
        )

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed"),
        )

    private class MediaTypeFilter :
        Filter.Select<String>(
            "Media Type",
            arrayOf("All", "Manga", "Comic", "Novel"),
        )

    private class SortFilter :
        Filter.Select<String>(
            "Sort",
            arrayOf("Default", "Latest Update", "Newest", "Random"),
        )

    private suspend inline fun <reified B, reified T> projectRequest(
        endpoint: String,
        body: B,
        block: suspend (Response) -> T,
    ): T {
        val requestBody = json.encodeToString(body).toRequestBody(jsonMedia)
        return post("/api/project/$endpoint", requestBody, block)
    }

    private suspend fun mangaFromProjectInfo(info: RawProjectInfo): SManga = SManga.create().apply {
        val p = info.projectInfo.project
        url = p.projectId.toString()
        title = p.projectName
        artist = p.artistName
        author = p.authorName
        description = p.info
        status = getStatus(p.status)
        thumbnail_url = buildCoverUrl(p.projectId.toString(), p.coverVersion)
        genre = info.projectInfo.category?.joinToString(", ") { it.categoryName }.orEmpty()
        initialized = true
    }

    private fun parseProjectList(
        resp: Response,
        filterTypes: Set<String>?,
        isPaginated: Boolean,
    ): MangasPage {
        val projectList = parse<RawProjectSearchSummaryList>(resp)
        if (projectList.listProject.isNullOrEmpty()) {
            return MangasPage(emptyList(), false)
        }
        val mangaList = projectList.listProject
            .filter { filterTypes == null || it.projectType in filterTypes }
            .map {
                SManga.create().apply {
                    url = it.pid.toString()
                    title = it.projectName
                    status = it.status
                    thumbnail_url = buildCoverUrl(it.pid.toString(), it.coverVersion)
                    initialized = false
                }
            }
        return MangasPage(mangaList, isPaginated && mangaList.size == SEARCH_PAGE_SIZE)
    }

    private fun buildCoverUrl(projectId: String, coverVersion: Int?): String {
        val base = "$fileHost/collectManga/$projectId/${projectId}_cover.jpg"
        return if (coverVersion != null) "$base?ver=$coverVersion" else base
    }

    private suspend inline fun <reified T> post(
        path: String,
        requestBody: okhttp3.RequestBody,
        block: suspend (Response) -> T,
    ): T {
        val req = POST("$baseUrl$path", apiHeaders, requestBody)
        val resp = client.newCall(req).execute()
        resp.use { return block(it) }
    }

    private inline fun <reified T> parse(resp: Response): T = json.decodeFromString<T>(resp.body?.string().orEmpty())

    companion object {
        private const val POPULAR_PAGE_SIZE = 15
        private const val LATEST_PAGE_SIZE = 15
        private const val SEARCH_PAGE_SIZE = 100
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}

// ---------- DTOs (mirror of Keiyoushi's nekopost model) ----------

@Serializable
internal data class PagingInfo(val pageNo: Int, val pageSize: Int)

@Serializable
internal data class SearchRequest(
    val keyword: String,
    val status: Int,
    val paging: PagingInfo,
    val projectType: String? = null,
    val genre: List<String>? = null,
    val orderBy: String? = null,
)

@Serializable
internal data class UpdatesRequest(val type: String, val paging: PagingInfo)

@Serializable
internal data class ProjectRequestBody(val pid: Int)

@Serializable
internal data class RawProjectSearchSummaryList(val listProject: List<RawProjectSearchSummary>? = null)

@Serializable
internal data class RawProjectSearchSummary(
    @SerialName("pid") val pid: Int,
    @SerialName("projectName") val projectName: String,
    @SerialName("status") val status: Int,
    @SerialName("projectType") val projectType: String,
    @SerialName("coverVersion") val coverVersion: Int,
)

@Serializable
internal data class RawLatestChapterList(val listChapter: List<RawLatestChapter>? = null)

@Serializable
internal data class RawLatestChapter(
    @SerialName("pid") val pid: Int,
    @SerialName("projectName") val projectName: String,
    @SerialName("coverVersion") val coverVersion: Int,
    @SerialName("status") val status: String,
)

@Serializable
internal data class RawProjectInfo(val projectInfo: RawProjectInfoData)

@Serializable
internal data class RawProjectInfoData(
    @SerialName("Project") val project: RawProject,
    @SerialName("ListCate") val category: List<RawProjectCategory>?,
    @SerialName("ListChapter") val chapter: List<RawProjectChapter>?,
)

@Serializable
internal data class RawProject(
    @SerialName("pid") val projectId: Int,
    @SerialName("projectName") val projectName: String,
    @SerialName("authorName") val authorName: String,
    @SerialName("artistName") val artistName: String,
    @SerialName("info") val info: String,
    @SerialName("status") val status: Int,
    @SerialName("coverVersion") val coverVersion: Int? = null,
)

@Serializable
internal data class RawProjectCategory(@SerialName("CateName") val categoryName: String)

@Serializable
internal data class RawProjectChapter(
    @SerialName("ChapterID") val chapterId: Int,
    @SerialName("ChapterNo") val chapterNo: String,
    @SerialName("ChapterName") val chapterName: String,
    @SerialName("PublishDate") val publishDate: RawValidString,
    @SerialName("ProviderName") val providerName: String,
)

@Serializable
internal data class RawValidString(@SerialName("String") val value: String)

@Serializable
internal data class RawChapterInfo(
    @SerialName("chapterId") val chapterId: String,
    @SerialName("pageItem") val pageItem: List<RawPageItem>,
    @SerialName("projectId") val projectId: String,
)

@Serializable
internal data class RawPageItem(
    @SerialName("pageName") val pageName: String? = null,
    @SerialName("fileName") val fileName: String? = null,
)

@Serializable
internal data class EditorProject(
    @SerialName("pid") val pid: Int,
    @SerialName("projectName") val projectName: String,
    @SerialName("projectType") val projectType: String,
    @SerialName("status") val status: Int,
    @SerialName("coverVersion") val coverVersion: Int? = null,
)

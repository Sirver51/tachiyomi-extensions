package eu.kanade.tachiyomi.extension.all.tachidesk

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.min
import kotlin.reflect.KProperty1

class Tachidesk : ConfigurableSource, UnmeteredSource, HttpSource() {
    override val name = "Suwayomi"
    override val id = 3100117499901280806L
    override val baseUrl by lazy { getPrefBaseUrl() }
    private val baseLogin by lazy { getPrefBaseLogin() }
    private val basePassword by lazy { getPrefBasePassword() }

    override val lang = "all"
    override val supportsLatest = false

    private val json: Json by injectLazy()

    override val client: OkHttpClient =
        network.client.newBuilder()
            .dns(Dns.SYSTEM) // don't use DNS over HTTPS as it breaks IP addressing
            .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        if (basePassword.isNotEmpty() && baseLogin.isNotEmpty()) {
            val credentials = Credentials.basic(baseLogin, basePassword)
            add("Authorization", credentials)
        }
    }

    // ------------- Popular Manga -------------

    // Route the popular manga view through search to avoid duplicate code path
    override fun popularMangaRequest(page: Int): Request =
        searchMangaRequest(page, "", FilterList())

    override fun popularMangaParse(response: Response): MangasPage =
        searchMangaParse(response)

    // ------------- Manga Details -------------

    override fun mangaDetailsRequest(manga: SManga) =
        GET("$checkedBaseUrl/api/v1/manga/${manga.url}/?onlineFetch=true", headers)

    override fun mangaDetailsParse(response: Response): SManga =
        json.decodeFromString<MangaDataClass>(response.body.string()).toSManga()

    // ------------- Chapter -------------

    override fun chapterListRequest(manga: SManga): Request =
        GET("$checkedBaseUrl/api/v1/manga/${manga.url}/chapters?onlineFetch=true", headers)

    override fun chapterListParse(response: Response): List<SChapter> =
        json.decodeFromString<List<ChapterDataClass>>(response.body.string()).map {
            it.toSChapter()
        }

    // ------------- Page List -------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map { response ->
                pageListParse(response, chapter)
            }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val mangaId = chapter.url.split(" ").first()
        val chapterIndex = chapter.url.split(" ").last()

        return GET("$checkedBaseUrl/api/v1/manga/$mangaId/chapter/$chapterIndex/?onlineFetch=True", headers)
    }

    fun pageListParse(response: Response, sChapter: SChapter): List<Page> {
        val mangaId = sChapter.url.split(" ").first()
        val chapterIndex = sChapter.url.split(" ").last()

        val chapter = json.decodeFromString<ChapterDataClass>(response.body.string())

        return List(chapter.pageCount) {
            Page(it + 1, "", "$checkedBaseUrl/api/v1/manga/$mangaId/chapter/$chapterIndex/page/$it/")
        }
    }

    // ------------- Filters & Search -------------

    private var categoryList: List<CategoryDataClass> = emptyList()
    private val defaultCategoryId: Int
        get() = categoryList.firstOrNull()?.id ?: 0

    private val resultsPerPageOptions = listOf(10, 15, 20, 25)
    private val defaultResultsPerPage = resultsPerPageOptions.first()

    private val sortByOptions = listOf(
        "Title" to MangaDataClass::title,
        "Artist" to MangaDataClass::artist,
        "Author" to MangaDataClass::author,
        "Date added" to MangaDataClass::inLibraryAt,
        "Total chapters" to MangaDataClass::chapterCount,
//        "Unread chapters (remote)" to MangaDataClass::unreadCount,
    )
    private val defaultSortByIndex = 0

    class CategorySelect(categoryList: List<CategoryDataClass>) :
        Filter.Select<String>("Category", categoryList.map { it.name }.toTypedArray())

    class ResultsPerPageSelect(options: List<Int>) :
        Filter.Select<Int>("Results per page", options.toTypedArray())

    class SortBy(options: List<Pair<String, KProperty1<MangaDataClass, Any?>>>) :
        Filter.Sort("Sort by", options.map { it.first }.toTypedArray(), Selection(0, true))

    override fun getFilterList(): FilterList =
        FilterList(
            Filter.Header("Press reset to attempt to fetch categories."),
            Filter.Header("\"All\" shows all manga regardless of category."),
            CategorySelect(refreshCategoryList(baseUrl).let { categoryList }),
            Filter.Separator(),
            SortBy(sortByOptions),
            ResultsPerPageSelect(resultsPerPageOptions),
        )

    private fun refreshCategoryList(baseUrl: String) {
        Single.fromCallable {
            client.newCall(GET("$baseUrl/api/v1/category", headers)).execute()
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { response ->
                    categoryList = try {
                        // Add a pseudo category to list all manga across all categories
                        listOf(CategoryDataClass(-1, -1, "All", false)) +
                            json.decodeFromString<List<CategoryDataClass>>(response.body.string())
                    } catch (e: Exception) {
                        emptyList()
                    }
                },
                {},
            )
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Embed search query and scope into URL params for processing in searchMangaParse
        var currentCategoryId = defaultCategoryId
        var resultsPerPage = defaultResultsPerPage
        var sortByIndex = defaultSortByIndex
        var sortByAscending = true
        filters.forEach { filter ->
            when (filter) {
                is CategorySelect -> currentCategoryId = categoryList[filter.state].id
                is ResultsPerPageSelect -> resultsPerPage = resultsPerPageOptions[filter.state]
                is SortBy -> {
                    sortByIndex = filter.state?.index ?: sortByIndex
                    sortByAscending = filter.state?.ascending ?: sortByAscending
                }
                else -> {}
            }
        }
        val url = "$checkedBaseUrl/api/v1/$currentCategoryId"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("searchQuery", query)
            .addQueryParameter("currentCategoryId", currentCategoryId.toString())
            .addQueryParameter("sortBy", sortByIndex.toString())
            .addQueryParameter("sortByAscending", sortByAscending.toString())
            .addQueryParameter("resultsPerPage", resultsPerPage.toString())
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val request = response.request
        var searchQuery: String? = ""
        var currentCategoryId = defaultCategoryId
        var sortByIndex = defaultSortByIndex
        var sortByAscending = true
        var resultsPerPage = defaultResultsPerPage
        var page = 1

        // Check if URL has query params and parse them
        if (!request.url.query.isNullOrEmpty()) {
            searchQuery = request.url.queryParameter("searchQuery")
            currentCategoryId = request.url.queryParameter("currentCategoryId")?.toIntOrNull() ?: currentCategoryId
            sortByIndex = request.url.queryParameter("sortBy")?.toIntOrNull() ?: sortByIndex
            sortByAscending = request.url.queryParameter("sortByAscending").toBoolean()
            resultsPerPage = request.url.queryParameter("resultsPerPage")?.toIntOrNull() ?: resultsPerPage
            page = request.url.queryParameter("page")?.toIntOrNull() ?: page
        }
        val sortByProperty = sortByOptions[sortByIndex].second

        // Get URLs of categories to search
        val categoryUrlList = if (currentCategoryId == -1) {
            categoryList.map { category -> "$checkedBaseUrl/api/v1/category/${category.id}" }
        } else {
            listOfNotNull("$checkedBaseUrl/api/v1/category/$currentCategoryId")
        }

        // Construct a list of all manga in the required categories by querying each one
        val mangaList = mutableListOf<MangaDataClass>()
        categoryUrlList.forEach { categoryUrl ->
            val categoryMangaListRequest =
                GET(categoryUrl, headers)
            val categoryMangaListResponse =
                client.newCall(categoryMangaListRequest).execute()
            val categoryMangaListJson =
                categoryMangaListResponse.body.string()
            val categoryMangaList =
                json.decodeFromString<List<MangaDataClass>>(categoryMangaListJson)
            mangaList.addAll(categoryMangaList)
        }

        // Filter according to search terms.
        // Basic substring search, room for improvement.
        var searchResults = if (!searchQuery.isNullOrEmpty()) {
            mangaList.filter { mangaData ->
                val fieldsToCheck = listOfNotNull(
                    mangaData.title,
                    mangaData.url,
                    mangaData.artist,
                    mangaData.author,
                    mangaData.description,
                )
                fieldsToCheck.any { field ->
                    field.contains(searchQuery, ignoreCase = true)
                }
            }
        } else {
            mangaList
        }.distinct()

        // Sort results
        searchResults = searchResults.sortedBy { mangaData ->
            (sortByProperty.get(mangaData) ?: 0) as Comparable<Any>
        }
        if (!sortByAscending) {
            searchResults = searchResults.asReversed()
        }

        // Paginate results
        val hasNextPage: Boolean
        with(paginateResults(searchResults, page, resultsPerPage)) {
            searchResults = first
            hasNextPage = second
        }

        return MangasPage(searchResults.map { mangaData -> mangaData.toSManga() }, hasNextPage)
    }

    // ------------- Images -------------
    override fun imageRequest(page: Page) = GET(page.imageUrl!!, headers)

    // ------------- Settings -------------

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    init {
        val preferencesMap = mapOf(
            ADDRESS_TITLE to ADDRESS_DEFAULT,
            LOGIN_TITLE to LOGIN_DEFAULT,
            PASSWORD_TITLE to PASSWORD_DEFAULT,
        )

        preferencesMap.forEach { (key, defaultValue) ->
            val initBase = preferences.getString(key, defaultValue)!!

            if (initBase.isNotBlank()) {
                refreshCategoryList(initBase)
            }
        }
    }

    // ------------- Preferences -------------
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(screen.editTextPreference(ADDRESS_TITLE, ADDRESS_DEFAULT, baseUrl, false, "i.e. http://192.168.1.115:4567"))
        screen.addPreference(screen.editTextPreference(LOGIN_TITLE, LOGIN_DEFAULT, baseLogin, false, ""))
        screen.addPreference(screen.editTextPreference(PASSWORD_TITLE, PASSWORD_DEFAULT, basePassword, true, ""))
    }

    /** boilerplate for [EditTextPreference] */
    private fun PreferenceScreen.editTextPreference(title: String, default: String, value: String, isPassword: Boolean = false, placeholder: String): EditTextPreference {
        return EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value.ifEmpty { placeholder }
            this.setDefaultValue(default)
            dialogTitle = title

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(context, "Restart Tachiyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    Log.e("Tachidesk", "Exception while setting text preference", e)
                    false
                }
            }
        }
    }

    private fun getPrefBaseUrl(): String = preferences.getString(ADDRESS_TITLE, ADDRESS_DEFAULT)!!
    private fun getPrefBaseLogin(): String = preferences.getString(LOGIN_TITLE, LOGIN_DEFAULT)!!
    private fun getPrefBasePassword(): String = preferences.getString(PASSWORD_TITLE, PASSWORD_DEFAULT)!!

    companion object {
        private const val ADDRESS_TITLE = "Server URL Address"
        private const val ADDRESS_DEFAULT = ""
        private const val LOGIN_TITLE = "Login (Basic Auth)"
        private const val LOGIN_DEFAULT = ""
        private const val PASSWORD_TITLE = "Password (Basic Auth)"
        private const val PASSWORD_DEFAULT = ""
    }

    // ------------- Not Used -------------

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("Not used")

    override fun pageListParse(response: Response): List<Page> = throw Exception("Not used")

    override fun imageUrlParse(response: Response): String = throw Exception("Not used")

    // ------------- Util -------------

    private fun MangaDataClass.toSManga() = SManga.create().also {
        it.url = id.toString()
        it.title = title
        it.thumbnail_url = "$baseUrl$thumbnailUrl"
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre.joinToString(", ")
        it.status = when (status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "LICENSED" -> SManga.LICENSED
            else -> SManga.UNKNOWN // covers "UNKNOWN" and other Impossible cases
        }
    }

    private fun ChapterDataClass.toSChapter() = SChapter.create().also {
        it.url = "$mangaId $index"
        it.name = name
        it.date_upload = uploadDate
        it.scanlator = scanlator
    }

    private val checkedBaseUrl: String
        get(): String = baseUrl.ifEmpty { throw RuntimeException("Set Tachidesk server url in extension settings") }

    private fun paginateResults(mangaList: List<MangaDataClass>, page: Int?, itemsPerPage: Int?): Pair<List<MangaDataClass>, Boolean> {
        var hasNextPage = false
        val pageItems = if (mangaList.isNotEmpty() && itemsPerPage is Int && page is Int) {
            val fromIndex = (page - 1) * itemsPerPage
            val toIndex = min(fromIndex + itemsPerPage, mangaList.size)
            hasNextPage = toIndex < mangaList.size
            mangaList.subList(fromIndex, toIndex)
        } else {
            mangaList
        }
        return Pair(pageItems, hasNextPage)
    }
}

package eu.kanade.tachiyomi.extension.zh.hotmangas

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.Single
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HotMangas : HttpSource(), ConfigurableSource {
    override val name = "热辣漫画"
    override val lang = "zh"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private var domain = DOMAINS[preferences.getString(DOMAIN_PREF, "0")!!.toInt().coerceIn(0, DOMAINS.size - 1)]
    private var webDomain = WWW_PREFIX + WEB_DOMAINS[preferences.getString(WEB_DOMAIN_PREF, "0")!!.toInt().coerceIn(0, WEB_DOMAINS.size - 1)]
    override val baseUrl = webDomain
    private var apiUrl = API_PREFIX + domain

    private val groupRatelimitRegex = Regex("""/group/.*/chapters""")
    private val chapterRatelimitRegex = Regex("""/chapter2/""")
    private val imageQualityRegex = Regex("""(c|h)(800|1200|1500)x\.""")

    private val groupInterceptor = RateLimitInterceptor(null, preferences.getString(GROUP_API_RATE_PREF, "30")!!.toInt(), 61, TimeUnit.SECONDS)
    private val chapterInterceptor = RateLimitInterceptor(null, preferences.getString(CHAPTER_API_RATE_PREF, "20")!!.toInt(), 61, TimeUnit.SECONDS)

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val url = chain.request().url.toString()
            when {
                url.contains(groupRatelimitRegex) -> groupInterceptor.intercept(chain)
                url.contains(chapterRatelimitRegex) -> chapterInterceptor.intercept(chain)
                else -> chain.proceed(chain.request())
            }
        }
        .build()

    private fun Headers.Builder.setUserAgent(userAgent: String) = set("User-Agent", userAgent.ifEmpty { DEFAULT_BROWSER_USER_AGENT })
    private fun Headers.Builder.setWebp(useWebp: Boolean) = set("webp", if (useWebp) { "1" } else { "0" })
    private fun Headers.Builder.setVersion(version: String) = set("version", version)

    private var apiHeaders = Headers.Builder()
        .setWebp(preferences.getBoolean(WEBP_PREF, true))
        .setVersion(DEFAULT_VERSION)
        .add("platform", "3")
        .build()

    // Use desktop version of website in webview
    override fun headersBuilder() = Headers.Builder()
        .setUserAgent(preferences.getString(BROWSER_USER_AGENT_PREF, DEFAULT_BROWSER_USER_AGENT)!!)

    override fun popularMangaRequest(page: Int): Request {
        val offset = PAGE_SIZE * (page - 1)
        return GET("$apiUrl/api/v3/comics?free_type=1&limit=$PAGE_SIZE&offset=$offset&ordering=-popular&_update=true", apiHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val page: ListDto<MangaDto> = response.parseAs()
        val hasNextPage = page.offset + page.limit < page.total
        return MangasPage(page.list.map { it.toSManga() }, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = PAGE_SIZE * (page - 1)
        return GET("$apiUrl/api/v3/comics?free_type=1&limit=$PAGE_SIZE&offset=$offset&ordering=-datetime_updated&_update=true", apiHeaders)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val offset = PAGE_SIZE * (page - 1)
        val builder = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("limit", "$PAGE_SIZE")
            .addQueryParameter("offset", "$offset")
            .addQueryParameter("free_type", "1")
            .addQueryParameter("_update", "true")
        if (query.isNotBlank()) {
            builder.addPathSegments("api/v3/search/comic")
                .addQueryParameter("q", query)
            filters.filterIsInstance<SearchFilter>().firstOrNull()?.addQuery(builder)
            builder.addQueryParameter("platform", "3")
        } else {
            builder.addPathSegments("api/v3/comics")
            filters.filterIsInstance<HotMangaFilter>().forEach {
                if (it !is SearchFilter) { it.addQuery(builder) }
            }
        }
        return GET(builder.build(), apiHeaders)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun getMangaUrl(manga: SManga): String = webDomain + manga.url

    override fun mangaDetailsRequest(manga: SManga) =
        GET("$apiUrl/api/v3/comic2/${manga.url.removePrefix(MangaDto.URL_PREFIX)}?platform=3&_update=true", apiHeaders)

    override fun mangaDetailsParse(response: Response): SManga =
        response.parseAs<MangaWrapperDto>().toSMangaDetails()

    private fun ArrayList<SChapter>.fetchChapterGroup(manga: String, key: String, name: String) {
        val result = ArrayList<SChapter>(0)
        var offset = 0
        var hasNextPage = true
        val groupName = when {
            key.equals("default") -> ""
            else -> name
        }
        while (hasNextPage) {
            val response = client.newCall(GET("$apiUrl/api/v3/comic/$manga/group/$key/chapters?limit=$CHAPTER_PAGE_SIZE&offset=$offset&_update=true", apiHeaders)).execute()
            val chapters: ListDto<ChapterDto> = response.parseAs()
            result.ensureCapacity(chapters.total)
            chapters.list.mapTo(result) { it.toSChapter(groupName) }
            offset += CHAPTER_PAGE_SIZE
            hasNextPage = offset < chapters.total
        }
        addAll(result.asReversed())
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Single.create<List<SChapter>> {
        val result = ArrayList<SChapter>()
        val response = client.newCall(mangaDetailsRequest(manga)).execute()
        val groups = response.parseAs<MangaWrapperDto>().groups!!.values
        val mangaSlug = manga.url.removePrefix(MangaDto.URL_PREFIX)
        for (group in groups) {
            result.fetchChapterGroup(mangaSlug, group.path_word, group.name)
        }
        it.onSuccess(result)
    }.toObservable()

    override fun chapterListRequest(manga: SManga) = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter): String = webDomain + chapter.url

    override fun pageListRequest(chapter: SChapter) = GET("$apiUrl/api/v3${chapter.url}?platform=3&_update=true", apiHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val result: ChapterPageListWrapperDto = response.parseAs()
        val slug = response.request.url.pathSegments[3]
        val pageList = result.chapter.contents.filter { it.url.contains("/$slug/") }.withIndex().map { it.value }
        return pageList.mapIndexed { i, it ->
            Page(i, imageUrl = it.url)
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    private var imageQuality = preferences.getString(QUALITY_PREF, QUALITY[0])
    override fun imageRequest(page: Page): Request {
        var imageUrl = page.imageUrl!!
        imageUrl = imageQualityRegex.replace(imageUrl, "c${imageQuality}x.")

        return GET(imageUrl, headers)
    }

    private inline fun <reified T> Response.parseAs(): T = use {
        if (header("Content-Type") != "application/json") {
            throw Exception("返回数据错误，不是json")
        } else if (code != 200) {
            throw Exception(json.decodeFromStream<ResultMessageDto>(body.byteStream()).message)
        }
        json.decodeFromStream<ResultDto<T>>(body.byteStream()).results
    }

    private var genres: Array<Param> = emptyArray()
    private var isFetchingGenres = false

    override fun getFilterList(): FilterList {
        val genreFilter = if (genres.isEmpty()) {
            fetchGenres()
            Filter.Header("点击“重置”尝试刷新题材分类")
        } else {
            GenreFilter(genres)
        }
        return FilterList(
            SearchFilter(),
            Filter.Separator(),
            Filter.Header("分类（搜索文本时无效）"),
            genreFilter,
            SortFilter(),
        )
    }

    private fun fetchGenres() {
        if (genres.isNotEmpty() || isFetchingGenres) { return }
        isFetchingGenres = true
        thread {
            try {
                val response = client.newCall(GET("$apiUrl/api/v3/theme/comic/count?limit=500&offset=0&free_type=1&platform=3", apiHeaders)).execute()
                val list = response.parseAs<ListDto<KeywordDto>>().list.sortedBy { it.name }
                val result = ArrayList<Param>(list.size + 1).apply { add(Param("全部", "")) }
                genres = list.mapTo(result) { it.toParam() }.toTypedArray()
            } catch (e: Exception) {
                Log.e("CopyManga", "failed to fetch genres", e)
            } finally {
                isFetchingGenres = false
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = "API域名"
            summary = "连接不稳定时可以尝试切换\n当前值：%s"
            entries = DOMAINS
            entryValues = DOMAIN_INDICES
            setDefaultValue(DOMAIN_INDICES[0])
            setOnPreferenceChangeListener { _, newValue ->
                val index = newValue as String
                preferences.edit().putString(DOMAIN_PREF, index).apply()
                domain = DOMAINS[index.toInt()]
                apiUrl = API_PREFIX + domain
                true
            }
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = WEB_DOMAIN_PREF
            title = "网页版域名"
            summary = "webview中使用的域名\n当前值：%s"
            entries = WEB_DOMAINS
            entryValues = WEB_DOMAIN_INDICES
            setDefaultValue(WEB_DOMAIN_INDICES[0])
            setOnPreferenceChangeListener { _, newValue ->
                val index = newValue as String
                preferences.edit().putString(WEB_DOMAIN_PREF, index).apply()
                webDomain = WWW_PREFIX + WEB_DOMAINS[index.toInt()]
                true
            }
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = QUALITY_PREF
            title = "图片分辨率（像素）"
            summary = "阅读过的部分需要清空缓存才能生效\n当前值：%s"
            entries = QUALITY
            entryValues = QUALITY
            setDefaultValue(QUALITY[0])
            setOnPreferenceChangeListener { _, newValue ->
                imageQuality = newValue as String
                preferences.edit().putString(QUALITY_PREF, imageQuality).apply()
                true
            }
        }.let { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = WEBP_PREF
            title = "使用 WebP 图片格式"
            summary = "默认开启，可以节省网站流量"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                val useWebp = newValue as Boolean
                preferences.edit().putBoolean(WEBP_PREF, useWebp).apply()
                apiHeaders = apiHeaders.newBuilder().setWebp(useWebp).build()
                true
            }
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = GROUP_API_RATE_PREF
            title = "章节目录请求频率限制"
            summary = "此值影响向章节目录api时发起连接请求的数量。需要重启软件以生效。\n当前值：每分钟 %s 个请求"
            entries = RATE_ARRAY
            entryValues = RATE_ARRAY
            setDefaultValue("20")
            setOnPreferenceChangeListener { _, newValue ->
                val rateLimit = newValue as String
                preferences.edit().putString(GROUP_API_RATE_PREF, rateLimit).apply()
                true
            }
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = CHAPTER_API_RATE_PREF
            title = "章节图片列表请求频率限制"
            summary = "此值影响向章节图片列表api时发起连接请求的数量。需要重启软件以生效。\n当前值：每分钟 %s 个请求"
            entries = RATE_ARRAY
            entryValues = RATE_ARRAY
            setDefaultValue("20")
            setOnPreferenceChangeListener { _, newValue ->
                val rateLimit = newValue as String
                preferences.edit().putString(CHAPTER_API_RATE_PREF, rateLimit).apply()
                true
            }
        }.let { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = BROWSER_USER_AGENT_PREF
            title = "浏览器User Agent"
            summary = "留空则使用插件默认 User Agent\n重启生效"
            setDefaultValue(DEFAULT_BROWSER_USER_AGENT)
            setOnPreferenceChangeListener { _, newValue ->
                val userAgent = newValue as String
                preferences.edit().putString(BROWSER_USER_AGENT_PREF, userAgent).apply()
                true
            }
        }.let { screen.addPreference(it) }
    }

    companion object {
        private const val DOMAIN_PREF = "domainZ"
        private const val WEB_DOMAIN_PREF = "webDomainZ"
        private const val QUALITY_PREF = "imageQualityZ"
        private const val WEBP_PREF = "useWebpZ"
        private const val GROUP_API_RATE_PREF = "groupApiRateZ"
        private const val CHAPTER_API_RATE_PREF = "chapterApiRateZ"
        private const val BROWSER_USER_AGENT_PREF = "browserUserAgent"

        private const val WWW_PREFIX = "https://www."
        private const val API_PREFIX = "https://mapi."
        private val DOMAINS = arrayOf("hotmangasg.com", "hotmangasd.com", "hotmangasf.com", "elfgjfghkk.club", "fgjfghkkcenter.club", "fgjfghkk.club")
        private val DOMAIN_INDICES = arrayOf("0", "1", "2", "3", "4", "5")
        private val WEB_DOMAINS = arrayOf("2024manga.com", "relamanhua.com")
        private val WEB_DOMAIN_INDICES = arrayOf("0", "1")
        private val QUALITY = arrayOf("800", "1200", "1500")
        private val RATE_ARRAY = (5..60 step 5).map { i -> i.toString() }.toTypedArray()
        private const val DEFAULT_VERSION = "2024.04.28"
        private const val DEFAULT_BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        private const val PAGE_SIZE = 20
        private const val CHAPTER_PAGE_SIZE = 100
    }
}

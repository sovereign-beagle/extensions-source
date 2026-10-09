package eu.kanade.tachiyomi.extension.en.tailspace

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.array
import keiyoushi.utils.booleanOrNull
import keiyoushi.utils.get
import keiyoushi.utils.intOrNull
import keiyoushi.utils.longOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Tailspace : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 5, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/browse.data".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "trending")
            .addQueryParameter("page", page.toString())
            .build()
        return getBrowseManga(url, page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/browse.data".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return getBrowseManga(url, page)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/browse.data".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query.trim())
        }

        for (filter in filters) {
            when (filter) {
                is SortFilter -> {
                    val sortValue = filter.selectedValue
                    if (sortValue.isNotEmpty()) {
                        url.addQueryParameter("sort", sortValue)
                    }
                }
                is CategoryGroup -> {
                    filter.state.filter { it.state }.forEach {
                        url.addQueryParameter("c", it.value)
                    }
                }
                is StatusFilter -> {
                    if (filter.state) {
                        url.addQueryParameter("finishedOnly", "true")
                    }
                }
                is SfwFilter -> {
                    if (filter.state) {
                        url.addQueryParameter("sfwOnly", "true")
                    }
                }
                is TagGroup -> {
                    for (tag in filter.state) {
                        if (tag.isIncluded()) {
                            url.addQueryParameter("tag", tag.id.toString())
                        } else if (tag.isExcluded()) {
                            url.addQueryParameter("excludeTag", tag.id.toString())
                        }
                    }
                }
                else -> {}
            }
        }

        return getBrowseManga(url.build(), page)
    }

    private suspend fun getBrowseManga(url: HttpUrl, page: Int): MangasPage {
        val response = client.get(url)
        val text = response.body.string()
        val firstLine = text.substringBefore('\n')
        val rawArray = firstLine.parseAs<JsonArray>()
        val root = rehydrate(rawArray)

        val browsePage = root["routes/pages/browse/BrowsePage"]?.jsonObject
            ?: return MangasPage(emptyList(), false)
        val data = browsePage["data"]?.jsonObject
            ?: return MangasPage(emptyList(), false)

        val comicsAndAds = data["comicsAndAds"]?.array ?: return MangasPage(emptyList(), false)
        val mangas = comicsAndAds.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.longOrNull ?: return@mapNotNull null
            val name = obj["name"]?.stringOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artistName = obj["artistName"]?.stringOrNull
            val thumbnailVersion = obj["thumbnailVersion"]?.intOrNull ?: 0
            val state = obj["state"]?.stringOrNull

            SManga.create().apply {
                this.url = "/c/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                title = name
                thumbnail_url = "https://pics.tailspace.com/comics/$id/thumbnail-2x.webp?v=$thumbnailVersion"
                artist = artistName
                author = artistName
                status = when (state) {
                    "finished" -> SManga.COMPLETED
                    "wip" -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
            }
        }

        val numberOfPages = data["numberOfPages"]?.intOrNull ?: 1
        val hasNextPage = page < numberOfPages
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (!url.encodedPath.startsWith("/c/")) return null
        val comicName = url.pathSegments.getOrNull(1)?.removeSuffix(".data")?.takeIf { it.isNotEmpty() }
            ?: return null
        val encodedName = URLEncoder.encode(comicName, "UTF-8").replace("+", "%20")
        return SManga.create().apply {
            this.url = "/c/$encodedName"
            title = comicName
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val comic = getComicDetails(manga.url)
        val chapterList = getCoalescedChapters(comic)

        manga.apply {
            title = comic.name
            thumbnail_url = "https://pics.tailspace.com/comics/${comic.id}/thumbnail-2x.webp?v=${comic.thumbnailVersion ?: 0}"
            artist = comic.artists
            author = comic.artists
            genre = buildList {
                comic.category?.let { add(it) }
                when (comic.isSfw) {
                    true -> add("Safe-ish")
                    false -> add("Explicit")
                    null -> {}
                }
                comic.tags.forEach { add(it) }
            }.distinct().joinToString(", ").ifEmpty { null }
            description = comic.description?.let(::extractDescriptionText)
            status = when (comic.state) {
                "finished" -> SManga.COMPLETED
                "wip" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            initialized = true
        }

        return SMangaUpdate(manga, chapterList)
    }

    private suspend fun getCoalescedChapters(currentComic: ComicDetails): List<SChapter> {
        val visitedIds = mutableSetOf(currentComic.id)
        val chapters = ArrayDeque<ComicDetails>()
        chapters.add(currentComic)

        var prev = currentComic.previousComic
        while (prev != null && prev.id != 0L && visitedIds.add(prev.id)) {
            val comic = runCatching { getComicDetails(prev.name) }.getOrNull() ?: break
            chapters.addFirst(comic)
            prev = comic.previousComic
        }

        var next = currentComic.nextComic
        while (next != null && next.id != 0L && visitedIds.add(next.id)) {
            val comic = runCatching { getComicDetails(next.name) }.getOrNull() ?: break
            chapters.addLast(comic)
            next = comic.nextComic
        }

        val isSingle = chapters.size == 1
        return chapters.mapIndexed { index, chapterComic ->
            SChapter.create().apply {
                url = "/c/" + URLEncoder.encode(chapterComic.name, "UTF-8").replace("+", "%20")
                name = if (isSingle) "Chapter" else chapterComic.name
                chapter_number = (index + 1).toFloat()
                date_upload = chapterComic.updated ?: chapterComic.published ?: 0L
            }
        }.reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val comic = getComicDetails(chapter.url)
        return comic.pages
            .sortedBy { it.pageNumber }
            .mapIndexed { index, pageItem ->
                Page(index, imageUrl = "https://pics.tailspace.com/comics/${comic.id}/${pageItem.token}.jpg")
            }
    }

    private suspend fun getComicDetails(urlPath: String): ComicDetails {
        val path = urlPath.removePrefix("/").removeSuffix(".data")
        val requestUrl = "$baseUrl/$path.data"
        val response = client.get(requestUrl)
        val text = response.body.string()
        val firstLine = text.substringBefore('\n')
        val rawArray = firstLine.parseAs<JsonArray>()
        val root = rehydrate(rawArray)

        val comicPage = root["routes/pages/comic/ComicPage"]?.jsonObject
            ?: throw Exception("Could not find ComicPage in response")
        val data = comicPage["data"]?.jsonObject
            ?: throw Exception("Could not find data in ComicPage")
        val comic = data["comic"]?.jsonObject
            ?: throw Exception("Could not find comic in ComicPage data")

        return parseComicDetails(comic)
    }

    private fun parseComicDetails(comic: JsonObject): ComicDetails {
        val id = comic["id"]?.longOrNull ?: 0L
        val name = comic["name"]?.stringOrNull ?: ""
        val description = comic["description"]?.stringOrNull
        val category = comic["category"]?.stringOrNull
        val isSfw = comic["isSfw"]?.booleanOrNull
        val state = comic["state"]?.stringOrNull
        val thumbnailVersion = comic["thumbnailVersion"]?.intOrNull
        val updated = comic["updated"]?.longOrNull
        val published = comic["published"]?.longOrNull

        val artists = buildList {
            comic["artist"]?.get("name")?.stringOrNull?.let { add(it) }
            comic["additionalArtists"]?.array?.forEach { artistElem ->
                artistElem["name"]?.stringOrNull?.let { add(it) }
            }
        }.distinct().joinToString(", ").ifEmpty { null }

        val tags = comic["tags"]?.array?.mapNotNull { it["name"]?.stringOrNull } ?: emptyList()

        val pages = comic["pages"]?.array?.mapNotNull { pageElem ->
            val token = pageElem["token"]?.stringOrNull ?: return@mapNotNull null
            val pageNumber = pageElem["pageNumber"]?.intOrNull ?: 0
            PageItem(token, pageNumber)
        } ?: emptyList()

        val previousComic = comic["previousComic"]?.let { prev ->
            val prevName = prev["name"]?.stringOrNull
            val prevId = prev["id"]?.longOrNull ?: 0L
            if (!prevName.isNullOrBlank()) LinkedComic(prevId, prevName) else null
        }

        val nextComic = comic["nextComic"]?.let { next ->
            val nextName = next["name"]?.stringOrNull
            val nextId = next["id"]?.longOrNull ?: 0L
            if (!nextName.isNullOrBlank()) LinkedComic(nextId, nextName) else null
        }

        return ComicDetails(
            id = id,
            name = name,
            description = description,
            category = category,
            isSfw = isSfw,
            state = state,
            thumbnailVersion = thumbnailVersion,
            artists = artists,
            tags = tags,
            pages = pages,
            updated = updated,
            published = published,
            previousComic = previousComic,
            nextComic = nextComic,
        )
    }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val response = client.get("$baseUrl/api/tags")
        val text = response.body.string()
        return text.parseAs<JsonElement>()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            SortFilter(),
            CategoryGroup(),
            StatusFilter(),
            SfwFilter(),
        )

        val tagsArray = data?.get("data")?.array ?: data?.array
        if (tagsArray != null && tagsArray.isNotEmpty()) {
            val tags = tagsArray.mapNotNull { elem ->
                val id = elem["id"]?.intOrNull ?: return@mapNotNull null
                val name = elem["name"]?.stringOrNull ?: return@mapNotNull null
                val category = elem["category"]?.stringOrNull
                TagDto(id, name, category)
            }
            val general = tags.filter { it.category == null }.sortedBy { it.name }
            val species = tags.filter { it.category == "species" }.sortedBy { it.name }
            val character = tags.filter { it.category == "character" }.sortedBy { it.name }
            val franchise = tags.filter { it.category == "franchise" }.sortedBy { it.name }

            if (general.isNotEmpty()) filters.add(TagGroup("General Tags", general))
            if (species.isNotEmpty()) filters.add(TagGroup("Species Tags", species))
            if (character.isNotEmpty()) filters.add(TagGroup("Character Tags", character))
            if (franchise.isNotEmpty()) filters.add(TagGroup("Franchise Tags", franchise))
        } else {
            filters.add(Filter.Header("Press 'Filter' to load tags"))
        }

        return FilterList(filters)
    }

    private fun rehydrate(raw: JsonArray): JsonObject {
        val cache = arrayOfNulls<JsonElement>(raw.size)
        val visiting = BooleanArray(raw.size)

        fun resolve(idx: Int): JsonElement {
            if (idx < 0 || idx >= raw.size || visiting[idx]) return JsonNull
            cache[idx]?.let { return it }

            visiting[idx] = true
            val result = try {
                when (val item = raw[idx]) {
                    is JsonPrimitive -> item
                    is JsonArray -> {
                        if (item.size == 2 && item[0].stringOrNull == "D" && item[1].longOrNull != null) {
                            item[1]
                        } else if (item.isNotEmpty() && item[0].stringOrNull == "P") {
                            JsonNull
                        } else {
                            buildJsonArray {
                                for (elem in item) {
                                    val refIdx = elem.intOrNull
                                    if (refIdx != null) {
                                        add(resolve(refIdx))
                                    }
                                }
                            }
                        }
                    }
                    is JsonObject -> {
                        buildJsonObject {
                            for ((k, v) in item) {
                                if (k.startsWith("_")) {
                                    val keyIdx = k.substring(1).toIntOrNull() ?: continue
                                    val keyName = raw.getOrNull(keyIdx)?.stringOrNull ?: continue
                                    val valIdx = v.intOrNull ?: continue
                                    put(keyName, resolve(valIdx))
                                }
                            }
                        }
                    }
                }
            } finally {
                visiting[idx] = false
            }

            cache[idx] = result
            return result
        }

        return (resolve(0) as? JsonObject) ?: JsonObject(emptyMap())
    }

    private fun extractDescriptionText(rawDesc: String): String {
        if (!rawDesc.trimStart().startsWith("{")) return rawDesc
        return runCatching {
            val root = rawDesc.parseAs<JsonElement>()
            val sb = StringBuilder()
            fun traverse(element: JsonElement) {
                when (element) {
                    is JsonObject -> {
                        if (element["type"]?.stringOrNull == "text") {
                            element["text"]?.stringOrNull?.let { sb.append(it) }
                        } else {
                            element["content"]?.let { traverse(it) }
                            if (element["type"]?.stringOrNull in listOf("paragraph", "heading", "listItem")) {
                                sb.append("\n\n")
                            }
                        }
                    }
                    is JsonArray -> {
                        for (child in element) {
                            traverse(child)
                        }
                    }
                    else -> {}
                }
            }
            traverse(root)
            sb.toString().trim()
        }.getOrDefault(rawDesc)
    }
}

package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.albumTrackSortKey
import com.bestiapop.android.domain.util.IdentifyQueryVariants
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.NaturalTextOrder
import com.bestiapop.android.domain.util.sortedWithNaturalOrder
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.albumGroupingKey
import com.bestiapop.android.domain.util.albumIdentityKey
import com.bestiapop.android.domain.util.dominantNonBlank
import com.bestiapop.android.domain.util.preferredAlbumDisplayName
import com.bestiapop.android.domain.util.songsByAlbumBucket
import com.bestiapop.android.domain.util.songsMatchingAlbumBucket
import com.bestiapop.android.domain.util.studioAlbumKeysByArtist
import com.bestiapop.android.ui.SortDirection
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.formatSortRelevantInfo
import com.bestiapop.android.ui.state.LibraryAlbumSegment
import com.bestiapop.android.ui.state.LibraryBrowseFilter
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryListModel
import com.bestiapop.android.ui.state.LibraryViewMode
import java.util.TreeMap

class GetLibrarySongsUseCase {

    data class CatalogProjection(
        val songs: List<Song>,
        val albums: List<Album>,
        val list: LibraryListModel
    ) {
        companion object {
            val EMPTY = CatalogProjection(emptyList(), emptyList(), LibraryListModel.EMPTY)
        }
    }

    private data class CachedProjection(
        val songsRef: Any,
        val songsSize: Int,
        val query: String,
        val sortOption: SortOption,
        val sortDirection: SortDirection,
        val overrides: Map<String, AlbumOverride>,
        val listMode: LibraryViewMode,
        val emphasizeLastPlayed: Boolean,
        val projection: CatalogProjection
    )

    private data class CachedGroupedAlbums(
        val songsRef: Any,
        val songsSize: Int,
        val grouped: Map<String, List<Song>>,
        val inheritedArtwork: Map<Long, String>
    )

    private data class CachedHaystack(
        val songsRef: Any,
        val songsSize: Int,
        val haystack: Map<Long, String>
    )

    private data class CachedArtists(
        val songsRef: Any,
        val songsSize: Int,
        val photosRef: Any,
        val sortOption: SortOption,
        val sortDirection: SortDirection,
        val artists: List<Artist>
    )

    private data class CachedGenres(
        val songsRef: Any,
        val songsSize: Int,
        val sortOption: SortOption,
        val sortDirection: SortDirection,
        val genres: List<GenreGroup>
    )

    @Volatile
    private var lastCachedProjection: CachedProjection? = null

    @Volatile
    private var lastCachedGrouped: CachedGroupedAlbums? = null

    @Volatile
    private var lastCachedHaystack: CachedHaystack? = null

    @Volatile
    private var lastCachedArtists: CachedArtists? = null

    @Volatile
    private var lastCachedGenres: CachedGenres? = null

    fun getOrBuildHaystack(songs: List<Song>): Map<Long, String> {
        val cached = lastCachedHaystack
        if (cached != null && cached.songsRef === songs && cached.songsSize == songs.size) {
            return cached.haystack
        }
        val map = HashMap<Long, String>(songs.size * 2)
        for (song in songs) {
            map[song.id] = searchHaystack(song)
        }
        lastCachedHaystack = CachedHaystack(songs, songs.size, map)
        return map
    }

    fun execute(
        songs: List<Song>,
        query: String,
        sortOption: SortOption,
        sortDirection: SortDirection = SortDirection.defaultFor(sortOption),
        haystackById: Map<Long, String>? = null
    ): List<Song> = filterAndSort(songs, query, sortOption, sortDirection, haystackById)

    fun projectCatalog(
        songs: List<Song>,
        query: String,
        sortOption: SortOption,
        sortDirection: SortDirection,
        overrides: Map<String, AlbumOverride>,
        listMode: LibraryViewMode,
        emphasizeLastPlayed: Boolean = false,
        haystackById: Map<Long, String>? = null
    ): CatalogProjection {
        val cached = lastCachedProjection
        if (cached != null &&
            cached.songsRef === songs &&
            cached.songsSize == songs.size &&
            cached.query == query &&
            cached.sortOption == sortOption &&
            cached.sortDirection == sortDirection &&
            cached.overrides == overrides &&
            cached.listMode == listMode &&
            cached.emphasizeLastPlayed == emphasizeLastPlayed
        ) {
            return cached.projection
        }

        val filtered = filterSongs(songs, query, haystackById)
        if (filtered.isEmpty()) {
            return CatalogProjection.EMPTY
        }
        // ALBUM_GROUPS visual order is album blocks + track number, not a global title sort.
        val pool = if (listMode == LibraryViewMode.FLAT) {
            sortSongs(filtered, sortOption, sortDirection)
        } else {
            filtered
        }

        val cachedGrouped = lastCachedGrouped
        val grouped = if (cachedGrouped != null &&
            cachedGrouped.songsRef === filtered &&
            cachedGrouped.songsSize == filtered.size
        ) {
            cachedGrouped.grouped
        } else {
            songsByAlbumBucket(pool, IdentifyRanking::isGenericAlbum)
        }

        val albums = albumsFromGrouped(grouped, overrides, sortOption, sortDirection)
        val inherited = albumArtworkBySongId(pool, grouped, albums)
        lastCachedGrouped = CachedGroupedAlbums(
            songsRef = filtered,
            songsSize = filtered.size,
            grouped = grouped,
            inheritedArtwork = inherited
        )
        val projection = CatalogProjection(
            songs = pool,
            albums = albums,
            list = listModelFrom(
                songs = pool,
                grouped = grouped,
                albums = albums,
                viewMode = listMode,
                sortOption = sortOption,
                emphasizeLastPlayed = emphasizeLastPlayed,
                inheritedArtwork = inherited
            )
        )

        lastCachedProjection = CachedProjection(
            songsRef = songs,
            songsSize = songs.size,
            query = query,
            sortOption = sortOption,
            sortDirection = sortDirection,
            overrides = overrides,
            listMode = listMode,
            emphasizeLastPlayed = emphasizeLastPlayed,
            projection = projection
        )
        return projection
    }

    fun recentSongs(
        songs: List<Song>,
        query: String,
        lastPlayedAtById: Map<Long, Long> = emptyMap()
    ): List<Song> {
        val stamped = ArrayList<Song>()
        for (song in songs) {
            val ts = lastPlayedAtById[song.id] ?: song.lastPlayedAt
            if (ts <= 0L) continue
            stamped += if (song.lastPlayedAt == ts) song else song.copy(lastPlayedAt = ts)
        }
        return filterAndSort(stamped, query, SortOption.TITLE, SortDirection.ASC)
            .sortedByDescending { it.lastPlayedAt }
    }

    fun songsInOrder(pool: List<Song>, ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<Long, Song>(pool.size * 2)
        for (song in pool) byId[song.id] = song
        return ids.mapNotNull { byId[it] }
    }

    private fun filterAndSort(
        songs: List<Song>,
        query: String,
        sortOption: SortOption,
        sortDirection: SortDirection,
        haystackById: Map<Long, String>? = null
    ): List<Song> = sortSongs(filterSongs(songs, query, haystackById), sortOption, sortDirection)

    private fun filterSongs(
        songs: List<Song>,
        query: String,
        haystackById: Map<Long, String>?
    ): List<Song> {
        if (query.isBlank()) return songs
        val normalizedQuery = TrackMatchKeys.normalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()
        return songs.filter { song ->
            val haystack = haystackById?.get(song.id) ?: searchHaystack(song)
            haystack.contains(normalizedQuery)
        }
    }

    private fun sortSongs(
        songs: List<Song>,
        sortOption: SortOption,
        sortDirection: SortDirection
    ): List<Song> {
        if (songs.size <= 1) return songs
        val ascending = sortDirection == SortDirection.ASC
        return when (sortOption) {
            SortOption.TITLE -> songs.sortedByText(ascending) { it.title }
            SortOption.ARTIST -> songs.sortedByText(ascending) { it.artist }
            SortOption.ALBUM -> songs.sortedByText(ascending) { it.album }
            SortOption.GENRE -> songs.sortedByText(ascending) { it.genre }
            SortOption.DATE_ADDED ->
                if (ascending) songs.sortedBy { it.dateAdded } else songs.sortedByDescending { it.dateAdded }
        }
    }

    private fun List<Song>.sortedByText(
        ascending: Boolean,
        selector: (Song) -> String
    ): List<Song> = sortedWithNaturalOrder(ascending, selector)

    fun compareSongsWithinAlbum(a: Song, b: Song): Int {
        val byTrack = albumTrackSortKey(a.trackNumber).compareTo(albumTrackSortKey(b.trackNumber))
        if (byTrack != 0) return byTrack
        return a.title.compareTo(b.title, ignoreCase = true)
    }

    fun sortSongsWithinAlbum(songs: List<Song>): List<Song> =
        songs.sortedWith(::compareSongsWithinAlbum)

    fun songsFromListItems(items: List<LibraryListItem>): List<Song> =
        items.mapNotNull { (it as? LibraryListItem.SongRow)?.song }

    /**
     * Compact list index: optional album segments + visual song order.
     * ALBUM_GROUPS orders album blocks with the same keys as [extractAlbums];
     * songs inside each album stay by track number (unknown tracks last).
     */
    fun buildListModel(
        songs: List<Song>,
        viewMode: LibraryViewMode,
        overrides: Map<String, AlbumOverride> = emptyMap(),
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC,
        emphasizeLastPlayed: Boolean = false
    ): LibraryListModel {
        if (songs.isEmpty()) return LibraryListModel.EMPTY
        val grouped = songsByAlbumBucket(songs, IdentifyRanking::isGenericAlbum)
        val albums = albumsFromGrouped(grouped, overrides, sortOption, sortDirection)
        val albumArtworks = albumArtworkBySongId(songs, grouped, albums)
        return listModelFrom(songs, grouped, albums, viewMode, sortOption, emphasizeLastPlayed, albumArtworks)
    }

    fun buildListItems(
        songs: List<Song>,
        viewMode: LibraryViewMode,
        overrides: Map<String, AlbumOverride> = emptyMap(),
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC,
        emphasizeLastPlayed: Boolean = false
    ): List<LibraryListItem> =
        buildListModel(songs, viewMode, overrides, sortOption, sortDirection, emphasizeLastPlayed)
            .toListItems()

    internal fun searchHaystack(song: Song): String {
        val raw = "${song.title} ${song.artist} ${song.album} ${song.genre}"
        return TrackMatchKeys.normalize(IdentifyQueryVariants.searchTokens(raw))
    }

    private fun albumArtworkBySongId(
        songs: List<Song>,
        grouped: Map<String, List<Song>>,
        albums: List<Album>
    ): Map<Long, String> {
        val albumArtByKey = HashMap<String, String>(albums.size)
        for (album in albums) {
            val art = album.artworkUri
            if (!art.isNullOrBlank()) {
                albumArtByKey[album.groupingKey] = art
                albumArtByKey[album.name] = art
            }
        }
        val out = HashMap<Long, String>(songs.size)
        for ((bucketKey, albumSongs) in grouped) {
            val isGeneric = albumSongs.all { IdentifyRanking.isGenericAlbum(it.album) }
            if (isGeneric) {
                // Generic/Unknown albums do not inherit covers across unrelated tracks
                for (song in albumSongs) {
                    val art = song.artworkUri?.takeIf(String::isNotBlank)
                    if (art != null) out[song.id] = art
                }
                continue
            }
            val art = albumArtByKey[bucketKey]
                ?: albumSongs.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) }
                ?: continue
            for (song in albumSongs) {
                out[song.id] = art
            }
        }
        return out
    }

    private fun listModelFrom(
        songs: List<Song>,
        grouped: Map<String, List<Song>>,
        albums: List<Album>,
        viewMode: LibraryViewMode,
        sortOption: SortOption,
        emphasizeLastPlayed: Boolean,
        inheritedArtwork: Map<Long, String>
    ): LibraryListModel {
        return when (viewMode) {
            LibraryViewMode.FLAT -> LibraryListModel.of(
                songsVisual = songs,
                inheritedArtworkBySongId = inheritedArtwork,
                sortOption = sortOption,
                emphasizeLastPlayed = emphasizeLastPlayed
            )

            LibraryViewMode.ALBUM_GROUPS -> {
                val visual = ArrayList<Song>(songs.size)
                val segments = ArrayList<LibraryAlbumSegment>(albums.size)
                albums.forEach { album ->
                    val albumSongs = songsInGroupedAlbum(grouped, album.groupingKey)
                    val start = visual.size
                    visual.addAll(albumSongs)
                    val songCount = albumSongs.size
                    val ids = ArrayList<Long>(songCount)
                    for (i in 0 until songCount) {
                        ids.add(albumSongs[i].id)
                    }
                    segments += LibraryAlbumSegment(
                        albumName = album.name,
                        displayName = album.displayName,
                        artistName = album.artist,
                        artworkUri = album.artworkUri,
                        groupingKey = album.groupingKey,
                        sortHint = formatSortRelevantInfo(
                            sortOption = sortOption,
                            genre = album.genre,
                            dateAdded = album.dateAdded
                        ),
                        start = start,
                        count = songCount,
                        songIds = ids
                    )
                }
                LibraryListModel.of(
                    songsVisual = visual,
                    segments = segments,
                    inheritedArtworkBySongId = inheritedArtwork,
                    sortOption = sortOption,
                    emphasizeLastPlayed = emphasizeLastPlayed
                )
            }
        }
    }

    fun songsForAlbum(songs: List<Song>, albumKey: String): List<Song> =
        sortSongsWithinAlbum(
            songsMatchingAlbumBucket(songs, albumKey, IdentifyRanking::isGenericAlbum)
        )

    fun extractAlbums(
        songs: List<Song>,
        overrides: Map<String, AlbumOverride> = emptyMap(),
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC
    ): List<Album> = albumsFromGrouped(
        songsByAlbumBucket(songs, IdentifyRanking::isGenericAlbum),
        overrides,
        sortOption,
        sortDirection
    )

    fun extractArtists(
        songs: List<Song>,
        artistPhotoMap: Map<String, String> = emptyMap(),
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC
    ): List<Artist> {
        val cached = lastCachedArtists
        if (cached != null &&
            cached.songsRef === songs &&
            cached.songsSize == songs.size &&
            cached.photosRef == artistPhotoMap &&
            cached.sortOption == sortOption &&
            cached.sortDirection == sortDirection
        ) {
            return cached.artists
        }
        val ascending = sortDirection == SortDirection.ASC
        val artists = songs.groupBy { it.artist }.map { (artistName, artistSongs) ->
            val studio = studioAlbumKeysByArtist(artistSongs, IdentifyRanking::isGenericAlbum)
            val distinctAlbumKeys = HashSet<String>(artistSongs.size)
            for (song in artistSongs) {
                distinctAlbumKeys.add(
                    albumGroupingKey(song.album, song.artist, studio, IdentifyRanking::isGenericAlbum)
                )
            }
            val photoArt = artistPhotoMap[artistName]
                ?: artistSongs.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) }
            Artist(
                name = artistName,
                songCount = artistSongs.size,
                albumCount = distinctAlbumKeys.size,
                photoUri = photoArt,
                genre = dominantGenreFromSongs(artistSongs),
                dateAdded = artistSongs.maxOfOrNull { it.dateAdded }
            )
        }
        val result = when (sortOption) {
            SortOption.TITLE, SortOption.ARTIST, SortOption.ALBUM ->
                artists.sortedAggregates(ascending) { it.name }
            SortOption.GENRE ->
                artists.sortedAggregates(ascending) { it.genre ?: "" }
            SortOption.DATE_ADDED ->
                artists.sortedAggregates(ascending, useLong = true, longKey = { it.dateAdded }) { it.name }
        }
        lastCachedArtists = CachedArtists(
            songsRef = songs,
            songsSize = songs.size,
            photosRef = artistPhotoMap,
            sortOption = sortOption,
            sortDirection = sortDirection,
            artists = result
        )
        return result
    }

    /**
     * Groups by genre label; blank → [Song.UNKNOWN_GENRE]. Known genres sorted; Unknown always last.
     */
    fun extractGenres(
        songs: List<Song>,
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC
    ): List<GenreGroup> {
        if (songs.isEmpty()) return emptyList()
        val cached = lastCachedGenres
        if (cached != null &&
            cached.songsRef === songs &&
            cached.songsSize == songs.size &&
            cached.sortOption == sortOption &&
            cached.sortDirection == sortDirection
        ) {
            return cached.genres
        }
        val ascending = sortDirection == SortDirection.ASC
        val groups = songs.groupBy { genreKey(it) }.map { (name, genreSongs) ->
            GenreGroup(
                name = name,
                songCount = genreSongs.size,
                artworkUri = firstArtwork(genreSongs),
                dateAdded = genreSongs.maxOfOrNull { it.dateAdded }
            )
        }
        val (unknown, known) = groups.partition { it.name.equals(Song.UNKNOWN_GENRE, ignoreCase = true) }
        val sortedKnown = when (sortOption) {
            SortOption.TITLE, SortOption.ARTIST, SortOption.ALBUM, SortOption.GENRE ->
                known.sortedAggregates(ascending) { it.name }
            SortOption.DATE_ADDED ->
                known.sortedAggregates(ascending, useLong = true, longKey = { it.dateAdded }) { it.name }
        }
        val result = sortedKnown + unknown
        lastCachedGenres = CachedGenres(
            songsRef = songs,
            songsSize = songs.size,
            sortOption = sortOption,
            sortDirection = sortDirection,
            genres = result
        )
        return result
    }

    private fun <T> List<T>.sortedAggregates(
        ascending: Boolean,
        useLong: Boolean = false,
        longKey: ((T) -> Long?)? = null,
        stringKey: (T) -> String
    ): List<T> =
        if (useLong && longKey != null) {
            if (ascending) sortedBy { longKey(it) ?: 0L } else sortedByDescending { longKey(it) ?: 0L }
        } else {
            sortedWithNaturalOrder(ascending, stringKey)
        }

    fun songsMatchingGenre(songs: List<Song>, genreName: String): List<Song> =
        songs.filter { genreKey(it).equals(genreName, ignoreCase = true) }

    /**
     * Flattens the songs represented by the current browse projection (play-all / shuffle).
     */
    fun songsForBrowseProjection(
        filter: LibraryBrowseFilter,
        songs: List<Song>,
        viewMode: LibraryViewMode = LibraryViewMode.FLAT,
        albums: List<Album>? = null,
        artists: List<Artist>? = null,
        genres: List<GenreGroup>? = null,
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC,
        overrides: Map<String, AlbumOverride> = emptyMap()
    ): List<Song> {
        if (songs.isEmpty()) return emptyList()
        return when (filter) {
            LibraryBrowseFilter.SONGS ->
                buildListModel(songs, viewMode, overrides, sortOption, sortDirection).songsVisual
            LibraryBrowseFilter.RECENT ->
                songs.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }
            LibraryBrowseFilter.ALBUMS -> {
                val grouped = songsByAlbumBucket(songs, IdentifyRanking::isGenericAlbum)
                val albumList = albums ?: albumsFromGrouped(grouped, emptyMap())
                albumList.flatMap { album -> songsInGroupedAlbum(grouped, album.groupingKey) }
            }
            LibraryBrowseFilter.ARTISTS -> {
                val artistList = artists ?: extractArtists(songs)
                val byArtist = songs.caseInsensitiveBuckets(Song::artist)
                artistList.flatMap { artist -> byArtist[artist.name].orEmpty() }
            }
            LibraryBrowseFilter.GENRES -> {
                val genreList = genres ?: extractGenres(songs)
                val byGenre = songs.caseInsensitiveBuckets(::genreKey)
                genreList.flatMap { genre -> byGenre[genre.name].orEmpty() }
            }
            LibraryBrowseFilter.PLAYLISTS -> emptyList()
        }
    }

    private fun List<Song>.caseInsensitiveBuckets(
        keyOf: (Song) -> String
    ): Map<String, List<Song>> {
        val buckets = TreeMap<String, MutableList<Song>>(String.CASE_INSENSITIVE_ORDER)
        for (song in this) {
            buckets.getOrPut(keyOf(song)) { ArrayList() }.add(song)
        }
        return buckets
    }

    private fun albumsFromGrouped(
        grouped: Map<String, List<Song>>,
        overrides: Map<String, AlbumOverride> = emptyMap(),
        sortOption: SortOption = SortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASC
    ): List<Album> {
        val ascending = sortDirection == SortDirection.ASC
        val albums = ArrayList<Album>(grouped.size)
        for ((bucketKey, albumSongs) in grouped) {
            val albumName = preferredAlbumDisplayNameFromSongs(albumSongs).ifBlank {
                albumSongs.first().album
            }
            val override = overrideForBucket(overrides, bucketKey, albumName)
            val firstArt = firstArtwork(albumSongs)
            val artistName = dominantArtistFromSongs(albumSongs, "Unknown Artist")
            var derivedYear = 0
            var maxDateAdded: Long? = null
            for (i in albumSongs.indices) {
                val song = albumSongs[i]
                if (derivedYear == 0 && song.year > 0) {
                    derivedYear = song.year
                }
                if (maxDateAdded == null || song.dateAdded > maxDateAdded) {
                    maxDateAdded = song.dateAdded
                }
            }
            albums.add(
                Album(
                    name = albumName,
                    displayName = override?.displayName?.takeIf { it.isNotBlank() } ?: albumName,
                    artist = override?.artist?.takeIf { it.isNotBlank() } ?: artistName,
                    songCount = albumSongs.size,
                    artworkUri = override?.artworkUri?.takeIf { it.isNotBlank() } ?: firstArt,
                    genre = override?.genre?.takeIf { it.isNotBlank() }
                        ?: dominantGenreFromSongs(albumSongs),
                    year = if (override != null && override.year > 0) override.year else derivedYear,
                    dateAdded = maxDateAdded,
                    groupingKey = bucketKey
                )
            )
        }
        return when (sortOption) {
            SortOption.TITLE, SortOption.ALBUM ->
                albums.sortedAggregates(ascending) { it.displayName }
            SortOption.ARTIST ->
                albums.sortedAggregates(ascending) { it.artist }
            SortOption.GENRE ->
                albums.sortedAggregates(ascending) { it.genre ?: "" }
            SortOption.DATE_ADDED ->
                albums.sortedAggregates(ascending, useLong = true, longKey = { it.dateAdded }) { it.displayName }
        }
    }

    private fun preferredAlbumDisplayNameFromSongs(songs: List<Song>): String {
        if (songs.isEmpty()) return ""
        if (songs.size == 1) return songs.first().album.trim()
        val names = ArrayList<String>(songs.size)
        for (i in songs.indices) {
            names.add(songs[i].album)
        }
        return preferredAlbumDisplayName(names)
    }

    private fun dominantArtistFromSongs(songs: List<Song>, default: String = "Unknown Artist"): String =
        dominantNonBlank(songs.map { it.artist }, default)


    private fun songsInGroupedAlbum(
        grouped: Map<String, List<Song>>,
        albumKey: String
    ): List<Song> {
        val target = albumIdentityKey(albumKey)
        val bucket = grouped[albumKey]
            ?: grouped[target]
            ?: grouped.entries.firstOrNull { (_, songs) ->
                preferredAlbumDisplayNameFromSongs(songs).equals(albumKey, ignoreCase = true)
            }?.value
        return sortSongsWithinAlbum(bucket.orEmpty())
    }

    private fun overrideForBucket(
        overrides: Map<String, AlbumOverride>,
        bucketKey: String,
        preferredName: String
    ): AlbumOverride? {
        if (overrides.isEmpty() || bucketKey.isEmpty()) return null
        overrides[preferredName]?.let { return it }
        return overrides.entries.firstOrNull { albumIdentityKey(it.key) == bucketKey }?.value
    }

    private fun firstArtwork(songs: List<Song>): String? =
        songs.firstOrNull { !it.artworkUri.isNullOrEmpty() }?.artworkUri

    private fun dominantGenreFromSongs(songs: List<Song>): String? {
        if (songs.isEmpty()) return null
        val counts = HashMap<String, Int>(8)
        var maxGenre: String? = null
        var maxCount = 0
        for (i in songs.indices) {
            val genre = songs[i].genre
            if (genre.isBlank() || genre.equals(Song.UNKNOWN_GENRE, ignoreCase = true)) continue
            val count = (counts[genre] ?: 0) + 1
            counts[genre] = count
            if (count > maxCount) {
                maxCount = count
                maxGenre = genre
            }
        }
        return maxGenre
    }

    private fun dominantGenre(genres: Iterable<String>): String? {
        val counts = HashMap<String, Int>(8)
        var maxGenre: String? = null
        var maxCount = 0
        for (genre in genres) {
            if (genre.isBlank() || genre.equals(Song.UNKNOWN_GENRE, ignoreCase = true)) continue
            val count = (counts[genre] ?: 0) + 1
            counts[genre] = count
            if (count > maxCount) {
                maxCount = count
                maxGenre = genre
            }
        }
        return maxGenre
    }

    companion object {
        fun genreKey(song: Song): String =
            song.genre.trim().ifBlank { Song.UNKNOWN_GENRE }
    }
}

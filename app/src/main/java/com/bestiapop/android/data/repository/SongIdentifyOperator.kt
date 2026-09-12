package com.bestiapop.android.data.repository

import androidx.room.withTransaction
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.db.MusicDao
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyApplyRequest
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.IdentifyResult
import com.bestiapop.android.data.model.IdentifySearchFilters
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.mergePreferring
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.domain.usecase.IdentifyPipeline
import com.bestiapop.android.domain.util.FilenameMetadataHints
import com.bestiapop.android.domain.util.IdentifyCatalogQuery
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.KnownAlbumTrack
import com.bestiapop.android.domain.util.KnownAlbumTracks
import com.bestiapop.android.domain.util.TrackMatchKeys
import com.bestiapop.android.domain.util.albumGroupKey
import com.bestiapop.android.domain.util.albumIdentityKey
import com.bestiapop.android.domain.util.albumNamesMatch
import com.bestiapop.android.domain.util.artistsCompatible
import com.bestiapop.android.domain.util.assignUniqueKnownAlbumMatches
import com.bestiapop.android.domain.util.identifySearchTexts
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.knownAlbumQueryOf
import com.bestiapop.android.domain.util.mergeKnownAlbumTracks
import com.bestiapop.android.domain.util.needsGapIdentify
import com.bestiapop.android.domain.util.pickPersistedAlbumName
import com.bestiapop.android.domain.util.pickPersistedArtistName
import com.bestiapop.android.domain.util.stripLeadingTitleJunk
import com.bestiapop.android.domain.util.studioAlbumKeysByArtist
import com.bestiapop.android.domain.util.toIdentifyCandidate
import com.bestiapop.android.domain.util.toKnownAlbumTrack
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encapsulates the online identification pipeline, candidate ranking,
 * identity proposal, metadata application, and known albums management.
 */
internal class SongIdentifyOperator(
    private val database: AppDatabase,
    private val musicDao: MusicDao,
    private val metadataSource: RepositoryMetadataSource,
    private val identityCache: RepositoryIdentityCache,
    private val onSongMetadataPersisted: suspend (List<Song>) -> Unit
) {
    private val catalogAlbumTracksCache = ConcurrentHashMap<String, List<KnownAlbumTrack>>()

    suspend fun proposeSongIdentity(
        song: Song,
        customQuery: String? = null,
        force: Boolean = false,
        listenBrainzToken: String? = null,
        filters: IdentifySearchFilters = IdentifySearchFilters(),
        catalogIndex: Int = 0,
        existingCandidates: List<IdentifyCandidate> = emptyList()
    ): IdentifyProposal = withContext(Dispatchers.IO) {
        val normalizedFilters = filters.normalized()
        val isExpand = catalogIndex > 0 || existingCandidates.isNotEmpty()
        if (!force && !isExpand && !needsMetadataIdentify(song)) {
            return@withContext IdentifyProposal(
                songId = song.id,
                queryArtist = song.artist,
                queryTitle = song.title,
                alreadyIdentified = true,
                confidence = IdentifyConfidence.NONE
            )
        }

        val stage1 = IdentifyPipeline.parseLocalFile(song)
        val baseName = stage1.baseName
        val stage2 = IdentifyPipeline.narrowArtist(stage1, identityCache.getArtists())
        val working = if (isExpand) song else persistWeakIdentityCleanup(song, stage2.mergedHints)
        val filenameArtist = stage2.filenameArtist
        val filenameTitle = stage2.filenameTitle
        val queryArtist = stage2.queryArtist
        val queryTitle = stage2.queryTitle
        val sourceHints = stage2.sourceHints

        val trimmedCustom = customQuery?.trim().orEmpty()
        val artistPlaceholder = queryArtist.isBlank() ||
                IdentifyRanking.isPlaceholderArtist(queryArtist)
        val filterArtist = normalizedFilters.artist.takeUnless {
            it.isBlank() || IdentifyRanking.isPlaceholderArtist(it)
        }
        val filterAlbum = normalizedFilters.album.takeUnless {
            it.isBlank() || IdentifyRanking.isGenericAlbum(it)
        }
        val preferYear = when {
            normalizedFilters.year in 1000..9999 -> normalizedFilters.year
            working.year in 1000..9999 -> working.year
            else -> 0
        }
        val isRefineSearch = trimmedCustom.isNotEmpty() || normalizedFilters.hasAny || isExpand
        if (!isRefineSearch) {
            matchKnownAlbumFromLibrary(working, queryArtist, queryTitle)?.let { candidate ->
                return@withContext IdentifyProposal(
                    songId = song.id,
                    queryArtist = queryArtist,
                    queryTitle = queryTitle,
                    sourceHints = sourceHints,
                    candidates = listOf(candidate),
                    confidence = IdentifyConfidence.HIGH,
                    suggested = candidate
                )
            }
        }

        val defaultSearch = IdentifyRanking.catalogSearchText(
            artist = queryArtist,
            title = queryTitle,
            album = working.album,
            artistIsPlaceholder = artistPlaceholder
        )
        val titleCollidesArtist = IdentifyRanking.titleCollidesWithArtistOrAlbum(
            title = working.title,
            artist = working.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) }
        )

        val catalogFreeText: String? = when {
            trimmedCustom.isNotEmpty() -> trimmedCustom
            isExpand && !normalizedFilters.hasAny -> defaultSearch
            else -> null
        }
        val catalogQuery = if (isRefineSearch) {
            IdentifyCatalogQuery.build(catalogFreeText, normalizedFilters).ifBlank { defaultSearch }
        } else {
            ""
        }

        val pageIndex = catalogIndex.coerceAtLeast(0)
        var fetchedCount = 0
        var usedListenBrainz = false
        var tracks = if (isRefineSearch) {
            val page = metadataSource.searchOnlineCatalog(
                query = catalogQuery,
                limit = IdentifyRanking.CATALOG_PAGE,
                index = pageIndex
            )
            fetchedCount = page.size
            page
        } else {
            val token = listenBrainzToken?.trim().orEmpty()
            val fetched = fetchIdentifyCatalogTracks(
                queryArtist = queryArtist,
                queryTitle = queryTitle,
                artistPlaceholder = artistPlaceholder,
                album = working.album,
                skipExactTitleLookup = titleCollidesArtist,
                durationMs = working.durationMs,
                listenBrainzToken = token.takeIf { it.isNotEmpty() },
                titleCollidesArtist = titleCollidesArtist
            )
            usedListenBrainz = fetched.usedListenBrainz
            fetched.tracks
        }

        val rankingQuery = IdentifyRanking.Query(
            artist = filterArtist ?: queryArtist,
            title = if (trimmedCustom.isNotEmpty()) trimmedCustom else queryTitle,
            durationMs = working.durationMs,
            filenameArtist = filenameArtist,
            filenameTitle = filenameTitle,
            artistIsPlaceholder = filterArtist == null && artistPlaceholder && trimmedCustom.isEmpty(),
            sourceArtist = filterArtist
                ?: working.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) },
            sourceTitle = working.title.takeUnless { it.isBlank() || looksLikeStoragePath(it) },
            sourceAlbum = filterAlbum
                ?: working.album.takeUnless { IdentifyRanking.isGenericAlbum(it) },
            preferYear = preferYear
        )

        val rankLimit = if (isExpand || isRefineSearch) {
            IdentifyRanking.CATALOG_PAGE
        } else {
            IdentifyRanking.TOP_N
        }
        var ranked = IdentifyRanking.rank(rankingQuery, tracks, limit = rankLimit)
        if (existingCandidates.isNotEmpty()) {
            ranked = IdentifyRanking.appendCandidates(existingCandidates, ranked)
        }
        var confidence = IdentifyRanking.confidence(ranked, rankingQuery)
        if (!isRefineSearch && confidence != IdentifyConfidence.HIGH) {
            val searchTexts = identifySearchTexts(defaultSearch, baseName)
            val extraQueries = searchTexts.filter { variant ->
                variant.isNotBlank() && !variant.equals(defaultSearch, ignoreCase = true)
            }
            if (extraQueries.isNotEmpty()) {
                tracks = mergeIdentifyCatalogTracks(
                    tracks,
                    extraQueries.flatMap { metadataSource.searchOnlineCatalog(it) }
                )
                ranked = IdentifyRanking.rank(rankingQuery, tracks, limit = rankLimit)
                if (existingCandidates.isNotEmpty()) {
                    ranked = IdentifyRanking.appendCandidates(existingCandidates, ranked)
                }
                confidence = IdentifyRanking.confidence(ranked, rankingQuery)
            }
            if (confidence != IdentifyConfidence.HIGH) {
                val fallbackQuery = searchTexts.firstOrNull().orEmpty().ifBlank { defaultSearch }
                val extraFallback = extraQueries.firstOrNull()
                val fallbackQueries = listOfNotNull(
                    fallbackQuery.takeIf { it.isNotBlank() },
                    extraFallback
                ).distinct()
                if (fallbackQueries.isNotEmpty()) {
                    tracks = mergeIdentifyCatalogTracks(
                        tracks,
                        fallbackQueries.flatMap { query ->
                            metadataSource.searchIdentifyFallbacks(
                                query = query,
                                durationMs = working.durationMs
                            )
                        }
                    )
                    ranked = IdentifyRanking.rank(rankingQuery, tracks, limit = rankLimit)
                    if (existingCandidates.isNotEmpty()) {
                        ranked = IdentifyRanking.appendCandidates(existingCandidates, ranked)
                    }
                    confidence = IdentifyRanking.confidence(ranked, rankingQuery)
                }
            }
        }

        val nextIndex = if (isRefineSearch) {
            pageIndex + fetchedCount
        } else {
            0
        }
        val mayHaveMore = when {
            isRefineSearch -> fetchedCount >= IdentifyRanking.CATALOG_PAGE
            ranked.isNotEmpty() -> true
            else -> false
        }
        val enrichedRanked = IdentifyPipeline.enrichTrackNumberIfMissing(ranked) { first ->
            findTrackNumberInAlbum(
                artist = first.artist,
                album = first.album,
                title = first.title,
                durationMs = working.durationMs
            )
        }

        if (confidence == IdentifyConfidence.HIGH && enrichedRanked.isNotEmpty()) {
            val best = enrichedRanked.first()
            if (best.artist.isNotBlank() && best.album.isNotBlank() && !IdentifyRanking.isGenericAlbum(best.album)) {
                try {
                    loadKnownAlbumTracks(best.artist, best.album, fetchCatalog = true)
                } catch (_: Exception) {}
            }
        }

        IdentifyProposal(
            songId = song.id,
            queryArtist = queryArtist,
            queryTitle = if (trimmedCustom.isNotEmpty()) trimmedCustom else queryTitle,
            sourceHints = sourceHints,
            candidates = enrichedRanked,
            confidence = confidence,
            suggested = enrichedRanked.firstOrNull(),
            usedListenBrainz = usedListenBrainz,
            nextCatalogIndex = nextIndex,
            catalogMayHaveMore = mayHaveMore
        )
    }

    suspend fun applySongIdentity(
        songId: Long,
        candidate: IdentifyCandidate,
        fields: IdentifyApplyFields = IdentifyApplyFields.ALL
    ): IdentifyResult {
        val applied = applySongIdentities(listOf(IdentifyApplyRequest(songId, candidate, fields)))
        return if (songId in applied) IdentifyResult.Updated(songId) else IdentifyResult.NoMatch
    }

    suspend fun applySongIdentities(
        requests: List<IdentifyApplyRequest>
    ): Set<Long> = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext emptySet()
        val library = identityCache.getSongs()
        val byId = library.associateBy { it.id }
        val studio = studioAlbumKeysByArtist(library, IdentifyRanking::isGenericAlbum)
        val bucketArtistsByAlbum = HashMap<String, List<String>>()
        val resolved = requests.mapNotNull { request ->
            val entity = byId[request.songId] ?: return@mapNotNull null
            resolveAppliedIdentity(
                entity,
                request.candidate,
                request.fields,
                library,
                studio,
                bucketArtistsByAlbum
            )
        }
        if (resolved.isEmpty()) return@withContext emptySet()
        database.withTransaction {
            for (updated in resolved) {
                musicDao.updateSongIdentity(
                    songId = updated.id,
                    title = updated.title,
                    artist = updated.artist,
                    album = updated.album,
                    artworkUri = updated.artworkUri,
                    trackNumber = updated.trackNumber,
                    year = updated.year,
                    durationMs = updated.durationMs
                )
            }
        }
        syncSongsRelations(database, musicDao, resolved)
        identityCache.remember(resolved)
        onSongMetadataPersisted(resolved)
        resolved.mapTo(LinkedHashSet(resolved.size)) { it.id }
    }

    suspend fun identifySongMetadata(song: Song): IdentifyResult = withContext(Dispatchers.IO) {
        val proposal = proposeSongIdentity(song)
        if (proposal.alreadyIdentified) return@withContext IdentifyResult.Skipped
        val reviewGate = IdentifyPipeline.evaluateReviewGate(proposal)
        if (!reviewGate.requiresReview && proposal.suggested != null) {
            return@withContext applySongIdentity(
                song.id,
                proposal.suggested,
                IdentifyApplyFields.ALL.copy(title = false)
            )
        }
        IdentifyResult.NoMatch
    }

    suspend fun loadKnownAlbumTracks(
        artist: String,
        album: String,
        fetchCatalog: Boolean = true
    ): KnownAlbumTracks? = withContext(Dispatchers.IO) {
        if (album.isBlank() || IdentifyRanking.isGenericAlbum(album)) return@withContext null
        val key = albumGroupKey(artist, album)
        val library = identityCache.getKnownAlbums().firstOrNull { it.key == key }
        val catalog = if (fetchCatalog) {
            catalogAlbumTracksCache.getOrPut(key) {
                fetchCatalogAlbumTracks(artist, album)
            }
        } else {
            emptyList()
        }
        mergeKnownAlbumTracks(artist, album, library, catalog)
    }

    suspend fun loadLibraryKnownAlbums(): List<KnownAlbumTracks> = withContext(Dispatchers.IO) {
        identityCache.getKnownAlbums()
    }

    suspend fun findTrackNumberInAlbum(
        artist: String,
        album: String,
        title: String,
        durationMs: Long = 0L
    ): Int? {
        if (album.isBlank() || IdentifyRanking.isGenericAlbum(album) || IdentifyRanking.isPlaceholderArtist(artist)) {
            return null
        }
        val albumTracks = loadKnownAlbumTracks(artist, album, fetchCatalog = false)
            ?: loadKnownAlbumTracks(artist, album, fetchCatalog = true)
            ?: return null
        val cleanedTitle = IdentifyRanking.cleanIdentityTitle(title, artist).lowercase()
        val strippedQuery = IdentifyRanking.stripTitleNoise(cleanedTitle).lowercase()
        val match = albumTracks.tracks.firstOrNull { track ->
            val trackCleaned = IdentifyRanking.cleanIdentityTitle(track.title, albumTracks.artist).lowercase()
            val trackStripped = IdentifyRanking.stripTitleNoise(trackCleaned).lowercase()
            trackCleaned == cleanedTitle ||
                trackStripped == strippedQuery ||
                (trackStripped.isNotEmpty() && strippedQuery.isNotEmpty() &&
                    IdentifyRanking.fieldSimilarity(trackStripped, strippedQuery) >= 0.80f) ||
                (durationMs > 0 && track.durationMs > 0 && kotlin.math.abs(track.durationMs - durationMs) <= 3500 &&
                    IdentifyRanking.fieldSimilarity(trackStripped, strippedQuery) >= 0.55f)
        }
        return match?.trackNumber?.takeIf { it > 0 }
    }

    suspend fun resolveTrackNumberFallback(artist: String, title: String): Int {
        if (!hasUsableIdentity(artist, title)) return 0
        return try {
            val fetched = metadataSource.fetchFullTrackMetadata(artist, title)
            fetched?.trackNumber?.takeIf { it > 0 } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun matchKnownAlbumFromLibrary(
        song: Song,
        queryArtist: String,
        queryTitle: String
    ): IdentifyCandidate? {
        val albums = identityCache.getKnownAlbums()
        if (albums.isEmpty()) return null
        val query = knownAlbumQueryOf(song, queryArtist = queryArtist, queryTitle = queryTitle)
        return assignUniqueKnownAlbumMatches(listOf(query), albums, scoped = false)[song.id]
            ?.toIdentifyCandidate()
    }

    private suspend fun fetchCatalogAlbumTracks(
        artist: String,
        album: String
    ): List<KnownAlbumTrack> {
        val hits = metadataSource.searchAlbums("$artist $album".trim())
        val chosen = hits.firstOrNull { hit ->
            albumNamesMatch(hit.title, album) && artistsCompatible(hit.artist, artist)
        } ?: hits.firstOrNull { hit -> albumNamesMatch(hit.title, album) }
        if (chosen == null) return emptyList()
        return metadataSource.fetchAlbumTracks(
            albumId = chosen.id,
            albumTitle = chosen.title,
            artistName = chosen.artist,
            coverUrl = chosen.coverUrl
        ).map { it.toKnownAlbumTrack() }
    }

    private suspend fun resolveAppliedIdentity(
        entity: Song,
        candidate: IdentifyCandidate,
        fields: IdentifyApplyFields,
        library: List<Song>,
        studioKeysByArtist: Map<String, List<String>>,
        bucketArtistsByAlbum: MutableMap<String, List<String>>
    ): Song {
        val preferred = candidate.track.identity.copy(
            album = candidate.album
                .takeIf { it.isNotBlank() && !IdentifyRanking.isGenericAlbum(it) }
                .orEmpty()
        )
        val merged = preferred.mergePreferring(entity.toIdentity())
        val candidateArtist = merged.artist
        val candidateTitle = IdentifyRanking.cleanIdentityTitle(merged.title, candidateArtist).ifBlank { merged.title }
        val bilingualTitle = IdentifyRanking.preferBilingualTitle(candidateTitle, entity.title)
        val candidateAlbum = IdentifyRanking.fallbackAlbum(merged.artist, merged.album)
        val candidateArtwork = merged.artworkUri
        val candidateTrackNumber = merged.trackNumber

        val resolvedAlbum = if (fields.album) {
            pickPersistedAlbumName(
                library = library,
                proposedAlbum = candidateAlbum.ifBlank { entity.album },
                proposedArtist = if (fields.artist) candidateArtist.ifBlank { entity.artist } else entity.artist,
                sourceAlbum = entity.album,
                isGeneric = IdentifyRanking::isGenericAlbum,
                studioKeysByArtist = studioKeysByArtist
            )
        } else {
            entity.album
        }
        val bucketArtists = if (fields.artist) {
            val cacheKey = albumIdentityKey(resolvedAlbum).ifBlank { resolvedAlbum }
            bucketArtistsByAlbum.getOrPut(cacheKey) {
                library.mapNotNull { song ->
                    song.artist.takeIf { albumNamesMatch(song.album, resolvedAlbum) }
                }
            } + entity.artist
        } else {
            emptyList()
        }
        val finalTitle = when {
            fields.title -> bilingualTitle.ifBlank { entity.title }
            IdentifyRanking.shouldApplyBilingualTitle(candidateTitle, entity.title) ->
                bilingualTitle.ifBlank { entity.title }

            else -> entity.title
        }
        val finalArtist = if (fields.artist) {
            pickPersistedArtistName(bucketArtists, candidateArtist.ifBlank { entity.artist })
        } else {
            entity.artist
        }
        val finalArtwork = if (fields.artwork) (candidateArtwork ?: entity.artworkUri) else entity.artworkUri
        val finalTrackNumber = if (fields.trackNumber) {
            if (candidateTrackNumber > 0) {
                candidateTrackNumber
            } else {
                findTrackNumberInAlbum(
                    artist = finalArtist,
                    album = resolvedAlbum,
                    title = finalTitle,
                    durationMs = entity.durationMs
                ) ?: entity.trackNumber
            }
        } else {
            entity.trackNumber
        }
        val finalYear = if (fields.year && candidate.year > 0) candidate.year else entity.year
        val finalDuration = if (entity.durationMs > 0) entity.durationMs else merged.durationMs
        return entity.copy(
            title = finalTitle,
            artist = finalArtist,
            album = resolvedAlbum,
            artworkUri = finalArtwork,
            trackNumber = finalTrackNumber,
            year = finalYear,
            durationMs = finalDuration
        )
    }

    private data class IdentifyCatalogFetch(
        val tracks: List<OnlineCatalogTrack>,
        val usedListenBrainz: Boolean
    )

    private suspend fun fetchIdentifyCatalogTracks(
        queryArtist: String,
        queryTitle: String,
        artistPlaceholder: Boolean,
        album: String,
        skipExactTitleLookup: Boolean,
        durationMs: Long,
        listenBrainzToken: String?,
        titleCollidesArtist: Boolean
    ): IdentifyCatalogFetch {
        val tracks = ArrayList<OnlineCatalogTrack>()
        var usedListenBrainz = false
        val token = listenBrainzToken?.trim().orEmpty()
        if (token.isNotEmpty() && !titleCollidesArtist && queryTitle.isNotBlank()) {
            val releaseHint = album.takeUnless { IdentifyRanking.isGenericAlbum(it) }
            metadataSource.lookupListenBrainzIdentifyTrack(
                artist = queryArtist,
                title = queryTitle,
                releaseName = releaseHint,
                token = token
            )?.let { lbTrack ->
                usedListenBrainz = true
                tracks.add(lbTrack)
            }
        }
        val primary = IdentifyRanking.catalogSearchText(
            artist = queryArtist,
            title = queryTitle,
            album = album,
            artistIsPlaceholder = artistPlaceholder
        )
        if (primary.isNotEmpty()) {
            tracks.addAll(
                metadataSource.searchMusicBrainzRecordings(
                    query = primary,
                    durationMs = durationMs
                )
            )
        }
        if (!artistPlaceholder && !skipExactTitleLookup) {
            metadataSource.fetchFullTrackMetadata(queryArtist, queryTitle)?.let { meta ->
                tracks.add(meta.toIdentifyCatalogTrack())
            }
        }
        if (primary.isNotEmpty()) {
            tracks.addAll(metadataSource.searchOnlineCatalog(primary))
        }
        return IdentifyCatalogFetch(
            tracks = mergeIdentifyCatalogTracks(emptyList(), tracks),
            usedListenBrainz = usedListenBrainz
        )
    }

    private fun mergeIdentifyCatalogTracks(
        existing: List<OnlineCatalogTrack>,
        extra: List<OnlineCatalogTrack>
    ): List<OnlineCatalogTrack> {
        val merged = LinkedHashMap<String, OnlineCatalogTrack>()
        for (track in existing + extra) {
            val key = IdentifyRanking.dedupeKey(track.artist, track.title, track.album)
            if (key !in merged) merged[key] = track
        }
        return merged.values.toList()
    }

    private fun TrackIdentity.toIdentifyCatalogTrack(): OnlineCatalogTrack = OnlineCatalogTrack(
        identity = this,
        id = "identify:${TrackMatchKeys.matchKey(artist, title)}",
        audioUrl = "",
        provider = "Catalog"
    )

    private fun needsMetadataIdentify(song: Song): Boolean = needsGapIdentify(song)

    private suspend fun persistWeakIdentityCleanup(
        song: Song,
        hints: FilenameMetadataHints
    ): Song {
        val artistWeak = IdentifyRanking.isPlaceholderArtist(song.artist)
        val titleJunk = song.title.trimStart().let {
            it.startsWith("-") || it.startsWith("_") || looksLikeStoragePath(it)
        } || isTrackNumberLabel(song.title.trim()) || (
                artistWeak &&
                        hints.title != null &&
                        stripLeadingTitleJunk(song.title) == hints.title &&
                        song.title != hints.title
                ) || (
                artistWeak &&
                        !hints.artist.isNullOrBlank() &&
                        !hints.title.isNullOrBlank() &&
                        song.title != hints.title
                )

        val newArtist = when {
            artistWeak && !hints.artist.isNullOrBlank() -> hints.artist
            isTrackNumberLabel(song.artist) -> "Unknown Artist"
            else -> null
        }
        val newTitle = when {
            !hints.title.isNullOrBlank() && titleJunk -> hints.title
            else -> null
        }
        val newTrack = hints.trackNumber?.takeIf { it > 0 && song.trackNumber <= 0 }

        if (newArtist == null && newTitle == null && newTrack == null) return song

        val updated = song.copy(
            artist = newArtist ?: song.artist,
            title = newTitle ?: song.title,
            trackNumber = newTrack ?: song.trackNumber
        )
        if (updated.artist == song.artist &&
            updated.title == song.title &&
            updated.trackNumber == song.trackNumber
        ) {
            return song
        }
        musicDao.updateSongMetadata(
            songId = song.id,
            title = updated.title,
            artist = updated.artist,
            album = song.album,
            genre = song.genre,
            year = song.year,
            trackNumber = updated.trackNumber
        )
        identityCache.remember(updated)
        return updated
    }

    private fun hasUsableIdentity(artist: String, title: String): Boolean =
        !IdentifyRanking.isPlaceholderArtist(artist) ||
            (!isTrackNumberLabel(title) && !isPlaceholderTitle(title))
}

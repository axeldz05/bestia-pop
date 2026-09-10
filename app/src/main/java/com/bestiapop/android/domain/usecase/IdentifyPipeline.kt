package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.IdentifyConfidence
import com.bestiapop.android.data.model.IdentifyProposal
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.withIdentity
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.looksLikeStoragePath
import com.bestiapop.android.domain.util.FilenameMetadataHints
import com.bestiapop.android.domain.util.IdentifyRanking
import com.bestiapop.android.domain.util.mergeIdentityHints
import com.bestiapop.android.domain.util.parseFilenameMetadataHints
import com.bestiapop.android.domain.util.resolveWeakIdentityHints
import com.bestiapop.android.domain.util.splitUsingKnownArtists
import com.bestiapop.android.domain.util.tidyFilenamePhrase

/**
 * 7-Stage Identification Pipeline for local audio files:
 * 1. Parse local file tags and filename (no streaming).
 * 2. Narrow artist candidate to constrain search space.
 * 3. Search by song/album name (saved/known albums checked first).
 * 4. Exactly identify album and song via multi-signal ranking.
 * 5. Save album for future references (sibling tracks in album/folder).
 * 6. Fetch remaining metadata (track number, lyrics, high-res art).
 * 7. On discrepancies or unclear metadata, route to review gate.
 */
object IdentifyPipeline {

    data class Stage1ParsedFile(
        val song: Song,
        val baseName: String,
        val fileHints: FilenameMetadataHints,
        val initialHints: FilenameMetadataHints
    )

    data class Stage2NarrowedArtist(
        val parsed: Stage1ParsedFile,
        val queryArtist: String,
        val queryTitle: String,
        val filenameArtist: String?,
        val filenameTitle: String?,
        val sourceHints: String?,
        val mergedHints: FilenameMetadataHints = parsed.initialHints
    )

    data class Stage4RankedResult(
        val candidates: List<IdentifyCandidate>,
        val confidence: IdentifyConfidence,
        val suggested: IdentifyCandidate?
    )

    data class Stage7ReviewDecision(
        val proposal: IdentifyProposal,
        val requiresReview: Boolean
    )

    /**
     * Stage 1: Parsing local file identity (local only, no streaming / network).
     */
    fun parseLocalFile(song: Song): Stage1ParsedFile {
        val path = SongPathNormalizer.resolveFilePath(song.uriString, song.folderPath)
        val baseName = path
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: song.uriString.substringAfterLast('/').substringBeforeLast('.')
        val fileHints = if (looksLikeStoragePath(baseName)) {
            FilenameMetadataHints(artist = null, title = null)
        } else {
            parseFilenameMetadataHints(baseName)
        }
        val initialHints = mergeIdentityHints(
            resolveWeakIdentityHints(song.artist, song.title),
            fileHints
        )
        return Stage1ParsedFile(
            song = song,
            baseName = baseName,
            fileHints = fileHints,
            initialHints = initialHints
        )
    }

    /**
     * Stage 2: Narrow artist to constrain candidates using library artists, tags, or split patterns.
     */
    fun narrowArtist(
        stage1: Stage1ParsedFile,
        libraryArtists: List<String>
    ): Stage2NarrowedArtist {
        val song = stage1.song
        val knownSplit = if (IdentifyRanking.isPlaceholderArtist(song.artist)) {
            val phrases = listOfNotNull(
                stage1.initialHints.title,
                song.title.takeUnless { looksLikeStoragePath(it) },
                stage1.fileHints.title,
                stage1.baseName.takeUnless { looksLikeStoragePath(it) }
            )
            phrases.firstNotNullOfOrNull { splitUsingKnownArtists(it, libraryArtists) }
        } else {
            null
        }

        val mergedHints = if (knownSplit?.artist != null) {
            mergeIdentityHints(knownSplit, stage1.initialHints)
        } else {
            stage1.initialHints
        }

        val filenameArtist = mergedHints.artist?.takeUnless { looksLikeStoragePath(it) }
        val filenameTitle = mergedHints.title?.takeUnless { looksLikeStoragePath(it) }

        var qArtist = song.artist
        var qTitle = song.title
        if (IdentifyRanking.isPlaceholderArtist(qArtist)) {
            qArtist = mergedHints.artist?.takeIf { it.isNotBlank() }.orEmpty()
            if (!mergedHints.title.isNullOrBlank()) qTitle = mergedHints.title
        } else if (!mergedHints.title.isNullOrBlank() &&
            (qTitle.trimStart().startsWith("-") || looksLikeStoragePath(qTitle))
        ) {
            qTitle = mergedHints.title
        }
        qArtist = tidyFilenamePhrase(qArtist)
        qTitle = tidyFilenamePhrase(qTitle).ifBlank { qTitle }

        val tagHints = listOfNotNull(
            song.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) },
            song.title.takeUnless { it.isBlank() || looksLikeStoragePath(it) },
            song.album.takeUnless { IdentifyRanking.isGenericAlbum(it) }
        ).joinToString(" · ").ifBlank { null }
        val filenameHint = listOfNotNull(
            filenameArtist?.takeIf { it.isNotBlank() },
            filenameTitle?.takeIf { it.isNotBlank() }
        ).takeIf { it.size == 2 }?.joinToString(" · ")
            ?: filenameTitle?.takeIf { it.isNotBlank() }
        val sourceHints = tagHints ?: filenameHint

        return Stage2NarrowedArtist(
            parsed = stage1,
            queryArtist = qArtist,
            queryTitle = qTitle,
            filenameArtist = filenameArtist,
            filenameTitle = filenameTitle,
            sourceHints = sourceHints,
            mergedHints = mergedHints
        )
    }

    /**
     * Stage 6: Enriches candidate list with track number for the primary candidate if missing or <= 0.
     * Level 1 primitive.
     */
    fun enrichTrackNumber(candidates: List<IdentifyCandidate>, trackNumber: Int?): List<IdentifyCandidate> {
        if (candidates.isEmpty() || trackNumber == null || trackNumber <= 0) return candidates
        val first = candidates.first()
        if (first.track.identity.trackNumber > 0) return candidates
        return listOf(
            first.copy(
                track = first.track.withIdentity { copy(trackNumber = trackNumber) }
            )
        ) + candidates.drop(1)
    }

    /**
     * Stage 6: Helper that fetches track number only if primary candidate exists and lacks a track number.
     * Level 2 compressed wrapper.
     */
    suspend inline fun enrichTrackNumberIfMissing(
        candidates: List<IdentifyCandidate>,
        fetchTrackNumber: (first: IdentifyCandidate) -> Int?
    ): List<IdentifyCandidate> {
        val first = candidates.firstOrNull() ?: return candidates
        if (first.track.identity.trackNumber > 0) return candidates
        val trackNum = fetchTrackNumber(first)
        return enrichTrackNumber(candidates, trackNum)
    }

    /**
     * Stage 7: Evaluate whether proposal requires manual review or can be automatically applied.
     */
    fun evaluateReviewGate(proposal: IdentifyProposal): Stage7ReviewDecision {
        val suggested = proposal.suggested
        val requiresReview = when {
            suggested == null -> true
            proposal.confidence != IdentifyConfidence.HIGH -> true
            IdentifyRanking.hasSevereConflict(suggested.reasons) -> true
            else -> false
        }
        return Stage7ReviewDecision(
            proposal = proposal,
            requiresReview = requiresReview
        )
    }

    /**
     * Execute full 7-stage pipeline for a song.
     */
    suspend fun execute(
        song: Song,
        libraryArtists: List<String>,
        searchSavedAlbums: suspend (song: Song, artist: String, title: String) -> IdentifyCandidate? = { _, _, _ -> null },
        searchCatalog: suspend (artist: String, title: String, album: String, durationMs: Long) -> List<OnlineCatalogTrack> = { _, _, _, _ -> emptyList() },
        saveAlbumReference: suspend (artist: String, album: String) -> Unit = { _, _ -> },
        fetchTrackNumber: suspend (artist: String, album: String, title: String, durationMs: Long) -> Int? = { _, _, _, _ -> null }
    ): Stage7ReviewDecision {
        // Stage 1: Parse local file
        val stage1 = parseLocalFile(song)

        // Stage 2: Narrow artist
        val stage2 = narrowArtist(stage1, libraryArtists)

        // Stage 3: Search by song/album name (saved albums first!)
        val savedAlbumCandidate = searchSavedAlbums(song, stage2.queryArtist, stage2.queryTitle)
        if (savedAlbumCandidate != null) {
            val proposal = IdentifyProposal(
                songId = song.id,
                queryArtist = stage2.queryArtist,
                queryTitle = stage2.queryTitle,
                sourceHints = stage2.sourceHints,
                candidates = listOf(savedAlbumCandidate),
                confidence = IdentifyConfidence.HIGH,
                suggested = savedAlbumCandidate
            )
            return evaluateReviewGate(proposal)
        }

        // If not in saved albums, search catalog
        val catalogTracks = searchCatalog(
            stage2.queryArtist,
            stage2.queryTitle,
            song.album,
            song.durationMs
        )

        // Stage 4: Exactly identify album and song via ranking
        val rankingQuery = IdentifyRanking.Query(
            artist = stage2.queryArtist,
            title = stage2.queryTitle,
            durationMs = song.durationMs,
            filenameArtist = stage2.filenameArtist,
            filenameTitle = stage2.filenameTitle,
            artistIsPlaceholder = stage2.queryArtist.isBlank() || IdentifyRanking.isPlaceholderArtist(stage2.queryArtist),
            sourceArtist = song.artist.takeUnless { IdentifyRanking.isPlaceholderArtist(it) },
            sourceTitle = song.title.takeUnless { it.isBlank() || looksLikeStoragePath(it) },
            sourceAlbum = song.album.takeUnless { IdentifyRanking.isGenericAlbum(it) }
        )
        val ranked = IdentifyRanking.rank(rankingQuery, catalogTracks, limit = IdentifyRanking.TOP_N)
        val confidence = IdentifyRanking.confidence(ranked, rankingQuery)
        val stage4 = Stage4RankedResult(
            candidates = ranked,
            confidence = confidence,
            suggested = ranked.firstOrNull()
        )

        // Stage 5: Save album for future references
        val suggested = stage4.suggested
        if (suggested != null && stage4.confidence == IdentifyConfidence.HIGH) {
            saveAlbumReference(suggested.artist, suggested.album)
        }

        // Stage 6: Fetch remaining metadata (track number, lyrics, etc.)
        val enrichedCandidates = enrichTrackNumberIfMissing(stage4.candidates) { cand ->
            fetchTrackNumber(
                cand.artist,
                cand.album,
                cand.title,
                song.durationMs
            )
        }

        val finalProposal = IdentifyProposal(
            songId = song.id,
            queryArtist = stage2.queryArtist,
            queryTitle = stage2.queryTitle,
            sourceHints = stage2.sourceHints,
            candidates = enrichedCandidates,
            confidence = stage4.confidence,
            suggested = enrichedCandidates.firstOrNull()
        )

        // Stage 7: Route to review gate if confidence is low, ambiguous, or has discrepancies
        return evaluateReviewGate(finalProposal)
    }

}

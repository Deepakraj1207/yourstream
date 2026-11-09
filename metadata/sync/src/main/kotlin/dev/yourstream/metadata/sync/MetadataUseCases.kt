package dev.yourstream.metadata.sync

import dev.yourstream.metadata.api.MetadataRef
import javax.inject.Inject

class MatchLibraryMetadata @Inject constructor(private val service: LibraryMetadataService) {
    /** Processes everything pending, in batches. */
    suspend operator fun invoke(): MetadataRunResult {
        var total = MetadataRunResult(0, 0, 0, 0, 0)
        repeat(MAX_BATCHES) {
            val r = service.processPending()
            total = MetadataRunResult(total.processed + r.processed, total.accepted + r.accepted, total.needsReview + r.needsReview, total.noMatch + r.noMatch, total.failed + r.failed)
            if (r.processed == 0 || r.processed == r.failed) return total
        }
        return total
    }

    /** Up to 4,000 titles per run (newest first); the next index change continues. */
    private companion object { const val MAX_BATCHES = 100 }
}

class SearchMetadataCandidates @Inject constructor(private val service: LibraryMetadataService) {
    suspend operator fun invoke(itemId: Long, title: String? = null, year: Int? = null) = service.searchCandidates(itemId, title, year)
}

class ConfirmMetadataMatch @Inject constructor(private val service: LibraryMetadataService) {
    suspend operator fun invoke(itemId: Long, ref: MetadataRef) = service.confirm(itemId, ref)
}

class RejectMetadataMatch @Inject constructor(private val service: LibraryMetadataService) {
    suspend operator fun invoke(itemId: Long) = service.reject(itemId)
}

class RematchItem @Inject constructor(private val service: LibraryMetadataService) {
    suspend operator fun invoke(itemId: Long) = service.rematch(itemId)
}

class GetItemMetadata @Inject constructor(private val repository: MetadataRepository) {
    operator fun invoke(itemId: Long) = repository.itemMetadata(itemId)
}

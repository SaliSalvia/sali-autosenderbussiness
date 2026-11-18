package ir.salisec.autosender.data

/**
 * Persistence boundary for the campaign studio.
 *
 * The repository owns Room access and keeps snapshot serialization out of UI
 * code. It intentionally stores no PDF text, API credentials, or raw report
 * contents in the campaign snapshot.
 */
class CampaignRepository(
    private val campaigns: CampaignDao,
    private val drafts: StudioDraftDao
) {
    suspend fun restore(): RestoredCampaign {
        val snapshot = campaigns.active()?.payload
        return RestoredCampaign(
            snapshot = CampaignSnapshot.decode(snapshot),
            drafts = drafts.all().associateBy { it.key }
        )
    }

    suspend fun saveSnapshot(data: CampaignSnapshotData, updatedAt: Long = System.currentTimeMillis()) {
        val payload = CampaignSnapshot.encode(
            domains = data.domains,
            contacts = data.contacts,
            selected = data.selected,
            contexts = data.contexts
        )
        campaigns.upsert(CampaignEntity(id = ACTIVE_CAMPAIGN_ID, payload = payload, updatedAt = updatedAt))
    }

    suspend fun saveDraft(draft: StudioDraftEntity) {
        drafts.upsert(draft)
    }

    suspend fun deleteDraft(key: String) {
        drafts.deleteByKey(key)
    }

    suspend fun clear() {
        campaigns.clearAll()
        drafts.clearAll()
    }

    companion object {
        const val ACTIVE_CAMPAIGN_ID = "active"
    }
}

data class RestoredCampaign(
    val snapshot: CampaignSnapshotData,
    val drafts: Map<String, StudioDraftEntity>
)

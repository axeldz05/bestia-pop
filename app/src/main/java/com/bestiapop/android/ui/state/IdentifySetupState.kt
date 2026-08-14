package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.Song

/**
 * State for configuring identify fields before executing an online metadata search.
 */
data class IdentifySetupState(
    val songs: List<Song>,
    val applyFields: IdentifyApplyFields = IdentifyApplyFields.ALL,
    val contextTitle: String = ""
)

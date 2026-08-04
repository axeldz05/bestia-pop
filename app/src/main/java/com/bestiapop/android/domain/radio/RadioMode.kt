package com.bestiapop.android.domain.radio

enum class RadioMode {
    /** Solo biblioteca local (offline). */
    EASY,
    /** Biblioteca + ListenBrainz / remotos cuando hay token y red. */
    EXPLORE
}

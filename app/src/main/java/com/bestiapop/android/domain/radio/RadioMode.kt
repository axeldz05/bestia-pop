package com.bestiapop.android.domain.radio

enum class RadioMode {
    /** Solo biblioteca local (conocidos). */
    KNOWN,
    /** Solo Remotes de LB/CF (nuevos / online). */
    NEW,
    /** Intercala Remote y Local equitativamente (online, offline, …). */
    BOTH
}

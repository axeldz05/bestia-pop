package com.bestiapop.android.domain.radio

enum class RadioMode {
    /** Solo biblioteca local (conocidos). */
    KNOWN,
    /** Solo Remotes online (LB → CF → Deezer/iTunes; nuevos). */
    NEW,
    /** Intercala Remote y Local equitativamente (online, offline, …). */
    BOTH
}

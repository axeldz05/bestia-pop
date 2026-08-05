package com.bestiapop.android.data.util

import org.json.JSONObject

fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return value.ifBlank { null }
}

package com.github.turansky.yfiles.correction

import org.json.JSONObject

internal fun applyEventHacks(source: Source) {
    source.types()
        .flatMap { it.optJsequence(J_CONSTRUCTORS) + it.optJsequence(J_METHODS) }
        .plus(source.functionSignatures.run { keySet().map { getJSONObject(it) } })
        .filter { it.has(J_PARAMETERS) }
        .jsequence(J_PARAMETERS)
        .forEach {
            val name = it.getNewName()
                ?: return@forEach

            it[J_NAME] = name
        }
}

fun JSONObject.getNewName(): String? =
    when (get(J_NAME)) {
        "evt" -> "event"
        "args" -> if (get(J_TYPE).endsWith("Args")) {
            "event"
        } else {
            null
        }

        "src", "eventSource" -> "source"

        else -> null
    }

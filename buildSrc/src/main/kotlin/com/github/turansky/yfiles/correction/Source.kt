package com.github.turansky.yfiles.correction

import org.json.JSONObject

internal class Source(private val api: JSONObject) {
    val functionSignatures: JSONObject
        get() = api[J_FUNCTION_SIGNATURES]

    private val types: List<JSONObject> = api
        .jsequence(J_NAMESPACES)
        .optionalArray(J_NAMESPACES)
        .jsequence(J_TYPES)
        .toList()

    private val typeMap = types.associateBy { it.uid }

    fun types(): Sequence<JSONObject> =
        types.asSequence()

    fun type(className: String): JSONObject =
        typeMap.getValue(className)

    fun types(vararg classNames: String): Sequence<JSONObject> =
        classNames.asSequence()
            .map { type(it) }

    fun allMethods(vararg methodNames: String): Sequence<JSONObject> =
        types.asSequence()
            .map { it.optionalArray(J_METHODS) + it.optionalArray(J_STATIC_METHODS) }
            .flatMap { it.asSequence() }
            .filter { it.getString(J_NAME) in methodNames }

    private val JSONObject.uid: String
        get() = if (has(J_ES6_NAME)) {
            getString(J_ES6_NAME)
        } else {
            getString(J_NAME)
        }
}

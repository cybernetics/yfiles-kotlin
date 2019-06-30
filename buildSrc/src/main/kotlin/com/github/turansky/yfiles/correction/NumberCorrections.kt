package com.github.turansky.yfiles.correction

import com.github.turansky.yfiles.JS_NUMBER
import org.json.JSONObject

private val INT = "Int"
private val DOUBLE = "Double"

internal fun correctNumbers(source: JSONObject) {
    val types = Source(source)
        .types()
        .toList()

    correctEnumerable(types)

    types.asSequence()
        .onEach { it.correctConstants() }
        .onEach { it.correctConstructors() }
        .onEach { it.correctProperties() }
        .onEach { it.correctPropertiesGeneric() }
        .onEach { it.correctMethods() }
        .forEach { it.correctMethodParameters() }

    (source
        .getJSONObject("functionSignatures")
        .getJSONObject("yfiles.view.AnimationCallback")
        .getJSONArray(J_PARAMETERS)
        .single() as JSONObject)
        .put(J_TYPE, DOUBLE)

}

private fun JSONObject.correctConstants() {
    if (!has("constants")) {
        return
    }

    val className = getString(J_NAME)
    jsequence("constants")
        .filter { it.getString(J_TYPE) != JS_NUMBER }
        .filter { it.getString(J_TYPE).contains(JS_NUMBER) }
        .forEach {
            if (it.has("signature")) {
                check(className == "HierarchicalClustering")
                it.put("signature", it.getString("signature").replace(",$JS_NUMBER>", ",$DOUBLE>"))
                return@forEach
            }

            val type = it.getString(J_TYPE)
            check(type.endsWith("DpKey<$JS_NUMBER>"))

            val name = it.getString(J_NAME)
            val generic = if (name.contains("_ID_") || name.contains("_INDEX_")) {
                INT
            } else {
                DOUBLE
            }
            it.put(J_TYPE, type.replace("<$JS_NUMBER>", "<$generic>"))
        }
}

private fun JSONObject.correctConstructors() {
    if (!has(J_CONSTRUCTORS)) {
        return
    }

    val className = getString(J_NAME)
    jsequence(J_CONSTRUCTORS)
        .optionalArray(J_PARAMETERS)
        .filter { it.getString(J_TYPE) == JS_NUMBER }
        .forEach { it.put(J_TYPE, getConstructorParameterType(className, it.getString(J_NAME))) }
}

private val DOUBLE_CONSTRUCTOR_CLASSES = setOf(
    "BorderLine",
    "GridConstraintProvider",
    "YVector",
    "Matrix",
    "TimeSpan",
    "DefaultNodePlacer",
    "Interval",
    "MinimumNodeSizeStage",
    "FreeEdgeLabelLayoutModelParameter",
    "FreeNodeLabelLayoutModelParameter"
)

private fun getConstructorParameterType(className: String, parameterName: String): String {
    if (className in DOUBLE_CONSTRUCTOR_CLASSES) {
        return DOUBLE
    }

    return when (parameterName) {
        in INT_CONSTRUCTOR_PARAMETERS -> INT
        in DOUBLE_CONSTRUCTOR_PARAMETERS -> DOUBLE
        else -> getPropertyType(className, parameterName)
    }
}

private fun JSONObject.correctProperties() {
    correctProperties("staticProperties")
    correctProperties("properties")
}

private fun JSONObject.correctProperties(key: String) {
    if (!has(key)) {
        return
    }

    val className = getString(J_NAME)
    jsequence(key)
        .filter { it.getString(J_TYPE) == JS_NUMBER }
        .forEach { it.put(J_TYPE, getPropertyType(className, it.getString(J_NAME))) }
}

private fun getPropertyType(className: String, propertyName: String): String {
    if (propertyName.endsWith("Count")) {
        return INT
    }

    if (propertyName.endsWith("Cost")) {
        return DOUBLE
    }

    if (propertyName.endsWith("Ratio")) {
        return DOUBLE
    }

    if (className == "BalloonLayout" && propertyName == "minimumNodeDistance") {
        return INT
    }

    if (propertyName.endsWith("Distance")) {
        return DOUBLE
    }

    if (className == "AffineLine" && (propertyName == "a" || propertyName == "b")) {
        return DOUBLE
    }

    return when (propertyName) {
        in INT_PROPERTIES -> INT
        in DOUBLE_PROPERTIES -> DOUBLE
        else -> throw IllegalStateException("Unexpected $className.$propertyName")
    }
}

private fun JSONObject.correctPropertiesGeneric() {
    if (!has("properties")) {
        return
    }

    jsequence("properties")
        .filter { it.getString(J_TYPE).contains("$JS_NUMBER>") }
        .forEach { it.put(J_TYPE, getPropertyGenericType(it.getString(J_NAME), it.getString(J_TYPE))) }

    jsequence("properties")
        .filter { it.has("signature") }
        .forEach {
            val signature = it.getString("signature")
            if (!signature.endsWith(",$JS_NUMBER>")) {
                return@forEach
            }

            val name = it.getString(J_NAME)
            check(name == "metric" || name == "heuristic")
            it.put("signature", signature.replace("$JS_NUMBER>", "$DOUBLE>"))
        }
}

private fun getPropertyGenericType(propertyName: String, type: String): String {
    val generic = if (
        propertyName.endsWith("Ids")
        || propertyName.endsWith("Indices")
        || propertyName.endsWith("Capacities", true)
        || propertyName == "busRootOffsets"
    ) {
        INT
    } else {
        DOUBLE
    }

    return type.replace(JS_NUMBER, generic)
}


private fun JSONObject.correctMethods() {
    correctMethods(J_STATIC_METHODS)
    correctMethods(J_METHODS)
}

private fun JSONObject.correctMethods(key: String) {
    if (!has(key)) {
        return
    }

    val className = getString(J_NAME)
    jsequence(key)
        .filter { it.has("returns") }
        .forEach {
            val methodName = it.getString(J_NAME)
            val returns = it.getJSONObject("returns")

            when (returns.getString(J_TYPE)) {
                JS_NUMBER -> returns.put(J_TYPE, getReturnType(className, methodName))
                "Array<$JS_NUMBER>" -> returns.put(J_TYPE, "Array<$DOUBLE>")
            }
        }
}

private fun getReturnType(className: String, methodName: String): String {
    if (methodName.endsWith("Count")) {
        return INT
    }

    if (methodName.endsWith("Components")) {
        return INT
    }

    if (methodName.endsWith("Cost") || methodName.endsWith("Costs")) {
        return DOUBLE
    }

    if (methodName.endsWith("Ratio")) {
        return DOUBLE
    }

    if (methodName.endsWith("Distance")) {
        return DOUBLE
    }

    if (className == "YVector" || className == "LineSegment" && methodName == "length") {
        return DOUBLE
    }

    return when (methodName) {
        in INT_METHODS -> INT
        in DOUBLE_METHODS -> DOUBLE
        else -> throw IllegalStateException("Unexpected $className.$methodName")
    }
}

private fun JSONObject.correctMethodParameters() {
    correctMethodParameters(J_STATIC_METHODS)
    correctMethodParameters(J_METHODS)
}

private fun JSONObject.correctMethodParameters(key: String) {
    if (!has(key)) {
        return
    }

    val className = getString(J_NAME)
    jsequence(key)
        .filter { it.has(J_PARAMETERS) }
        .forEach { method ->
            val methodName = method.getString(J_NAME)
            method.jsequence(J_PARAMETERS)
                .forEach {
                    val parameterName = it.getString(J_NAME)
                    when (it.getString(J_TYPE)) {
                        JS_NUMBER -> it.put(J_TYPE, getParameterType(className, methodName, parameterName))
                        "Array<$JS_NUMBER>" -> {
                            val generic = getGenericParameterType(className, methodName, parameterName)
                            it.put(J_TYPE, "Array<$generic>")
                        }
                    }
                }
        }
}

private val A_MAP = mapOf(
    "fromArgb" to INT,
    "fromHSLA" to DOUBLE,
    "fromRGBA" to DOUBLE
)

private val DOUBLE_CLASSES = setOf(
    "BorderLine",
    "Interval",
    "TimeSpan",
    "NodeReshapeSnapResultProvider",
    "InteractiveOrganicLayout",
    "GraphSnapContext",
    "NodeHalo"
)

private val DOUBLE_METHOD_NAMES = setOf(
    "setNumber",
    "createHighPerformanceDoubleMap",
    "applyZoom",
    "createStripeAnimation"
)

private fun getParameterType(className: String, methodName: String, parameterName: String): String {
    if (methodName == "setInt" || methodName == "createHighPerformanceIntMap") {
        return INT
    }

    if (methodName in DOUBLE_METHOD_NAMES) {
        return DOUBLE
    }

    if (className in DOUBLE_CLASSES) {
        return DOUBLE
    }

    if (className == "List" || className == "IEnumerable") {
        return INT
    }

    if (parameterName.endsWith("Ratio")) {
        return DOUBLE
    }

    if (parameterName.endsWith("Duration")) {
        return DOUBLE
    }

    if (parameterName.endsWith("Distance")) {
        return DOUBLE
    }

    if (parameterName.endsWith("Index")) {
        return INT
    }

    if (parameterName.endsWith("Count")) {
        return INT
    }

    if (parameterName == "a") {
        return A_MAP.getValue(methodName)
    }

    return when (parameterName) {
        in INT_METHOD_PARAMETERS -> INT
        in INT_PROPERTIES -> INT
        in DOUBLE_METHOD_PARAMETERS -> DOUBLE
        in DOUBLE_PROPERTIES -> DOUBLE
        else -> throw IllegalStateException("Unexpected $className.$methodName.$parameterName")
    }
}

private fun getGenericParameterType(className: String, methodName: String, parameterName: String): String {
    return if (className == "NodeOrders" || methodName.endsWith("ForInt") || parameterName == "intData") {
        INT
    } else {
        DOUBLE
    }
}

private val INT_SIGNATURE_CLASSES = setOf(
    "IEnumerable",
    "List"
)

private fun correctEnumerable(types: List<JSONObject>) {
    types.asSequence()
        .filter { it.getString(J_NAME) in INT_SIGNATURE_CLASSES }
        .flatMap { type ->
            sequenceOf(J_CONSTRUCTORS, J_METHODS, J_STATIC_METHODS)
                .filter { type.has(it) }
                .flatMap { type.jsequence(it) }
        }
        .optionalArray(J_PARAMETERS)
        .filter { it.has("signature") }
        .forEach {
            val signature = it.getString("signature")
            if (signature.contains(",$JS_NUMBER")) {
                it.put("signature", signature.replace(",$JS_NUMBER", ",$INT"))
            }
        }
}


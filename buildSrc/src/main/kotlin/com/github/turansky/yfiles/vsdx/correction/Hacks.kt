package com.github.turansky.yfiles.vsdx.correction

import com.github.turansky.yfiles.ABSTRACT
import com.github.turansky.yfiles.JS_ANY
import com.github.turansky.yfiles.JS_STRING
import com.github.turansky.yfiles.YCLASS
import com.github.turansky.yfiles.correction.*
import com.github.turansky.yfiles.json.strictRemove
import org.json.JSONObject

private val YFILES_TYPE_MAP = sequenceOf(
    YCLASS,

    "yfiles.collections.IEnumerator",
    "yfiles.collections.IEnumerable",

    "yfiles.geometry.Insets",
    "yfiles.geometry.Point",
    "yfiles.geometry.Size",

    "yfiles.graph.IModelItem",
    "yfiles.graph.INode",
    "yfiles.graph.IEdge",
    "yfiles.graph.ILabel",
    "yfiles.graph.IPort",
    "yfiles.graph.IGraph",

    "yfiles.graph.ILabelModelParameter",
    "yfiles.graph.InteriorStretchLabelModel",

    "yfiles.styles.INodeStyle",
    "yfiles.styles.IEdgeStyle",
    "yfiles.styles.ILabelStyle",
    "yfiles.styles.IPortStyle",

    "yfiles.styles.ArcEdgeStyle",
    "yfiles.styles.PolylineEdgeStyle",
    "yfiles.styles.VoidEdgeStyle",
    "yfiles.styles.IEdgePathCropper",

    "yfiles.styles.DefaultLabelStyle",

    "yfiles.styles.ImageNodeStyle",
    "yfiles.styles.PanelNodeStyle",
    "yfiles.styles.ShapeNodeStyle",

    "yfiles.styles.NodeStylePortStyleAdapter",
    "yfiles.styles.VoidPortStyle",

    "yfiles.view.GraphComponent",

    "yfiles.view.Color",
    "yfiles.view.Fill",
    "yfiles.view.Stroke",
    "yfiles.view.LinearGradient",

    "yfiles.view.Font",
    "yfiles.view.HorizontalTextAlignment",
    "yfiles.view.VerticalTextAlignment"
).associate { it.substringAfterLast(".") to it }

private val TYPE_MAP = YFILES_TYPE_MAP + mapOf(
    "[LinearGradient,RadialGradient]" to "yfiles.view.LinearGradient",

    "[CropEdgePathsPredicate,boolean]" to "CropEdgePathsPredicate",

    "[number,vsdx.Value<number>]" to "Value<number>",
    "[vsdx.PageLike,vsdx.Shape]" to "PageLike",
    "[Document,string]" to "Document", // ??? SvgDocument

    // TODO: use data interface instead
    "Promise<{data:string,format:string}>" to "Promise<$IMAGE_DATA>",
    "Promise<{master:vsdx.Master,fillStyle:vsdx.StyleSheet,lineStyle:vsdx.StyleSheet,textStyle:vsdx.StyleSheet}>" to "Promise<$MASTER_STATE>",
    "Promise<[{master:vsdx.Master,fillStyle:vsdx.StyleSheet,lineStyle:vsdx.StyleSheet,textStyle:vsdx.StyleSheet},null]>" to "Promise<$MASTER_STATE?>"
)

private val COLLECTION_INTERFACES = setOf(
    "IEnumerator<",
    "IEnumerable<",
    "IListEnumerable<",
    "IList<"
)

internal fun applyVsdxHacks(api: JSONObject) {
    val source = VsdxSource(api)

    removeUnusedFunctionSignatures(source)

    fixPackage(source)

    fixTypes(source)
    fixOptionTypes(source)
    fixGeneric(source)
    fixMethodModifier(source)
    fixSummary(source)
}

private fun removeUnusedFunctionSignatures(source: VsdxSource) {
    source.functionSignatures.apply {
        strictRemove("vsdx.ComparisonFunction")
        strictRemove("vsdx.LabelTextProcessingPredicate")
    }
}

private fun String.fixVsdxPackage(): String =
    replace("vsdx.", "yfiles.vsdx.")

private fun fixPackage(source: VsdxSource) {
    source.types()
        .forEach {
            val id = it[ID]
            it[ID] = "yfiles.$id"
        }

    source.types()
        .filter { it.has(EXTENDS) }
        .forEach {
            it[EXTENDS] = it[EXTENDS].fixVsdxPackage()
        }

    source.types()
        .filter { it.has(IMPLEMENTS) }
        .forEach {
            it[IMPLEMENTS] = it[IMPLEMENTS]
                .asSequence()
                .map { it as String }
                .map { it.fixVsdxPackage() }
                .toList()
        }

    source.functionSignatures.apply {
        keySet().toSet().forEach { id ->
            val functionSignature = getJSONObject(id)
            if (functionSignature.has(RETURNS)) {
                functionSignature[RETURNS]
                    .fixType()
            }

            put(id.fixVsdxPackage(), functionSignature)
            remove(id)
        }
    }
}

private fun JSONObject.fixType() {
    if (has(J_SIGNATURE)) {
        val signature = getFixedType(get(J_SIGNATURE))
            .fixVsdxPackage()

        set(J_SIGNATURE, signature)
    } else {
        val type = getFixedType(get(J_TYPE))
            .fixVsdxPackage()

        set(J_TYPE, type)
    }
}

private fun getFixedType(type: String): String {
    TYPE_MAP.get(type)?.also {
        return it
    }

    if (COLLECTION_INTERFACES.any { type.startsWith(it) }) {
        return "yfiles.collections.$type"
    }

    if (type.startsWith("[$JS_STRING,")) {
        return JS_STRING
    }

    if (type.startsWith("{")) {
        return JS_ANY
    }

    return type
}

private fun fixTypes(source: VsdxSource) {
    source.types()
        .filter { it.has(IMPLEMENTS) }
        .forEach {
            it[IMPLEMENTS] = it[IMPLEMENTS]
                .asSequence()
                .map { it as String }
                .map { getFixedType(it) }
                .toList()
        }

    source.types()
        .flatMap {
            (it.optJsequence(CONSTRUCTORS) + it.optJsequence(STATIC_METHODS) + it.optJsequence(METHODS))
                .flatMap {
                    it.optJsequence(PARAMETERS) + if (it.has(RETURNS)) {
                        sequenceOf(it[RETURNS])
                    } else {
                        emptySequence()
                    }
                }
                .plus(it.optJsequence(PROPERTIES))
        }
        .forEach { it.fixType() }

    source.functionSignatures
        .run {
            keySet().asSequence().map {
                getJSONObject(it)
            }
        }
        .filter { it.has(PARAMETERS) }
        .jsequence(PARAMETERS)
        .forEach { it.fixType() }
}

private fun fixOptionTypes(source: VsdxSource) {
    source.type("CachingMasterProvider")
        .jsequence(CONSTRUCTORS)
        .single()
        .apply {
            parameter("optionsOrNodeStyleType").apply {
                set(J_NAME, "nodeStyleType")
                set(J_TYPE, "$YCLASS<yfiles.styles.INodeStyle>")
            }

            parameter("edgeStyleType")
                .addGeneric("yfiles.styles.IEdgeStyle")
            parameter("portStyleType")
                .addGeneric("yfiles.styles.IPortStyle")
            parameter("labelStyleType")
                .addGeneric("yfiles.styles.ILabelStyle")
        }

    source.type("CustomEdgeProvider")
        .jsequence(CONSTRUCTORS)
        .single()
        .parameter("edgeStyleType")
        .addGeneric("yfiles.styles.IEdgeStyle")

    source.type("VssxStencilProviderFactory").apply {
        sequenceOf(
            "createMappedEdgeProvider" to "yfiles.styles.IEdgeStyle",
            "createMappedLabelProvider" to "yfiles.styles.ILabelStyle",
            "createMappedNodeProvider" to "yfiles.styles.INodeStyle",
            "createMappedPortProvider" to "yfiles.styles.IPortStyle"
        ).forEach { (methodName, styleGeneric) ->
            methodParameters(methodName, "styleType")
                .forEach { it.addGeneric(styleGeneric) }
        }
    }
}

private fun fixGeneric(source: VsdxSource) {
    source.type("Value").apply {
        staticMethod("fetch")
            .apply {
                setSingleTypeParameter("TValue")
                firstParameter[J_NAME] = "o"
            }

        staticMethod("formula")
            .setSingleTypeParameter("TValue")
    }
}

private fun fixMethodModifier(source: VsdxSource) {
    source.types("IMasterProvider", "IShapeProcessingStep")
        .jsequence(METHODS)
        .forEach { it[J_MODIFIERS].put(ABSTRACT) }
}

private val YFILES_API_REGEX = Regex("<a href=\"https://docs.yworks.com/yfileshtml/#/api/([a-zA-Z]+)\">([a-zA-Z]+)</a>")
private val VSDX_API_REGEX = Regex("<a href=\"#/api/([a-zA-Z]+)\">([a-zA-Z]+)</a>")

private fun JSONObject.fixSummary() {
    if (!has(J_SUMMARY)) {
        return
    }

    val summary = get(J_SUMMARY)
        .replace(YFILES_API_REGEX) {
            val type = YFILES_TYPE_MAP.getValue(it.groupValues.get(1))
            "[$type]"
        }
        .replace(VSDX_API_REGEX, "[$1]")
        .replace("\r\n", " ")
        .replace("\r", " ")
        .replace("</p>", "")
        .replace("<p>", "\n\n")

    set(J_SUMMARY, summary)
}

private fun fixSummary(source: VsdxSource) {
    source.type("VsdxExport")
        .jsequence(METHODS)
        .jsequence(PARAMETERS)
        .filter { it.has(J_SUMMARY) }
        .forEach {
            it[J_SUMMARY] = it[J_SUMMARY]
                .replace("""data-member="createDefault()"""", """data-member="createDefault"""")
        }

    source.types()
        .onEach { it.fixSummary() }
        .onEach { it.optJsequence(PROPERTIES).forEach { it.fixSummary() } }
        .flatMap { it.optJsequence(CONSTRUCTORS) + it.optJsequence(STATIC_METHODS) + it.optJsequence(METHODS) }
        .onEach { it.fixSummary() }
        .flatMap { it.optJsequence(PARAMETERS) }
        .forEach { it.fixSummary() }

    source.types()
        .filter { it.has(METHODS) }
        .jsequence(METHODS)
        .filter { it.has(RETURNS) }
        .map { it[RETURNS] }
        .filter { it.has(DOC) }
        .forEach {
            it[DOC] = it[DOC]
                .replace("\r", " ")
        }
}

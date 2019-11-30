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
            val id = it[J_ID]
            it[J_ID] = "yfiles.$id"
        }

    source.types()
        .filter { it.has(J_EXTENDS) }
        .forEach {
            it[J_EXTENDS] = it[J_EXTENDS].fixVsdxPackage()
        }

    source.types()
        .filter { it.has(J_IMPLEMENTS) }
        .forEach {
            val implementedTypes = it[J_IMPLEMENTS]
                .asSequence()
                .map { it as String }
                .map { it.fixVsdxPackage() }
                .toList()

            it.put(J_IMPLEMENTS, implementedTypes)
        }

    source.functionSignatures.apply {
        keySet().toSet().forEach { id ->
            val functionSignature = getJSONObject(id)
            if (functionSignature.has(J_RETURNS)) {
                functionSignature[J_RETURNS]
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
        .filter { it.has(J_IMPLEMENTS) }
        .forEach {
            val implementedTypes = it[J_IMPLEMENTS]
                .asSequence()
                .map { it as String }
                .map { getFixedType(it) }
                .toList()

            it.put(J_IMPLEMENTS, implementedTypes)
        }

    source.types()
        .flatMap {
            (it.optJsequence(J_CONSTRUCTORS) + it.optJsequence(J_STATIC_METHODS) + it.optJsequence(J_METHODS))
                .flatMap {
                    it.optJsequence(J_PARAMETERS) + if (it.has(J_RETURNS)) {
                        sequenceOf(it[J_RETURNS])
                    } else {
                        emptySequence()
                    }
                }
                .plus(it.optJsequence(J_PROPERTIES))
        }
        .forEach { it.fixType() }

    source.functionSignatures
        .run {
            keySet().asSequence().map {
                getJSONObject(it)
            }
        }
        .filter { it.has(J_PARAMETERS) }
        .jsequence(J_PARAMETERS)
        .forEach { it.fixType() }
}

private fun fixOptionTypes(source: VsdxSource) {
    source.type("CachingMasterProvider")
        .jsequence(J_CONSTRUCTORS)
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
        .jsequence(J_CONSTRUCTORS)
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
        .jsequence(J_METHODS)
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
        .jsequence(J_METHODS)
        .jsequence(J_PARAMETERS)
        .filter { it.has(J_SUMMARY) }
        .forEach {
            it[J_SUMMARY] = it[J_SUMMARY]
                .replace("""data-member="createDefault()"""", """data-member="createDefault"""")
        }

    source.types()
        .onEach { it.fixSummary() }
        .onEach { it.optJsequence(J_PROPERTIES).forEach { it.fixSummary() } }
        .flatMap { it.optJsequence(J_CONSTRUCTORS) + it.optJsequence(J_STATIC_METHODS) + it.optJsequence(J_METHODS) }
        .onEach { it.fixSummary() }
        .flatMap { it.optJsequence(J_PARAMETERS) }
        .forEach { it.fixSummary() }

    source.types()
        .filter { it.has(J_METHODS) }
        .jsequence(J_METHODS)
        .filter { it.has(J_RETURNS) }
        .map { it[J_RETURNS] }
        .filter { it.has(J_DOC) }
        .forEach {
            it[J_DOC] = it[J_DOC]
                .replace("\r", " ")
        }
}

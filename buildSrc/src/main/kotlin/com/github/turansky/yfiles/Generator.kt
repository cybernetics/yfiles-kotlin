package com.github.turansky.yfiles

import com.github.turansky.yfiles.correction.*
import com.github.turansky.yfiles.vsdx.correction.applyVsdxHacks
import com.github.turansky.yfiles.vsdx.correction.correctVsdxNumbers
import com.github.turansky.yfiles.vsdx.correction.createVsdxDataClasses
import com.github.turansky.yfiles.vsdx.fakeVsdxInterfaces
import org.json.JSONObject
import java.io.File
import java.net.URL

private fun loadJson(
    path: String,
    action: JSONObject.() -> Unit
): JSONObject =
    URL(path)
        .readText(DEFAULT_CHARSET)
        .run { substring(indexOf("{")) }
        .run { JSONObject(this) }
        .apply(action)
        .run { toString() }
        .run { JSONObject(this) }

fun generateKotlinDeclarations(
    apiPath: String,
    sourceDir: File
) {
    val source = loadJson(apiPath) {
        applyHacks(this)
        excludeUnusedTypes(this)
        correctNumbers(this)
    }

    docBaseUrl = "https://docs.yworks.com/yfileshtml"

    val apiRoot = ApiRoot(source)
    val types = apiRoot.types
    val functionSignatures = apiRoot.functionSignatures

    ClassRegistry.instance = ClassRegistry(types)

    val moduleName = "yfiles"
    val fileGenerator = KotlinFileGenerator(moduleName, types, functionSignatures.values)
    fileGenerator.generate(sourceDir)

    generateIdUtils(sourceDir)
    generateInterfaceMarker(sourceDir)
    generateClassUtils(moduleName, sourceDir)
}

fun generateVsdxKotlinDeclarations(
    apiPath: String,
    sourceDir: File
) {
    val source = loadJson(apiPath) {
        applyVsdxHacks(this)
        correctVsdxNumbers(this)
    }

    docBaseUrl = "https://docs.yworks.com/vsdx-html"

    val apiRoot = ApiRoot(source)
    val types = apiRoot.rootTypes
    val functionSignatures = apiRoot.functionSignatures

    ClassRegistry.instance = ClassRegistry(types + fakeVsdxInterfaces())

    val fileGenerator = KotlinFileGenerator("yfiles/vsdx", types, functionSignatures.values)
    fileGenerator.generate(sourceDir)

    generateInterfaceMarker(sourceDir)
    createVsdxDataClasses(sourceDir)
}

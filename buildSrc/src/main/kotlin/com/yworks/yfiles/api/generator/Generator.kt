@file:JvmName("Generator")

package com.yworks.yfiles.api.generator

import com.yworks.yfiles.api.generator.Hacks.getAdditionalContent
import com.yworks.yfiles.api.generator.Hacks.getParameterType
import com.yworks.yfiles.api.generator.TypeParser.getGenericString
import com.yworks.yfiles.api.generator.TypeParser.parseType
import com.yworks.yfiles.api.generator.Types.CLASS_TYPE
import com.yworks.yfiles.api.generator.Types.ENUM_TYPE
import com.yworks.yfiles.api.generator.Types.NODE_TYPE
import com.yworks.yfiles.api.generator.Types.OBJECT_TYPE
import com.yworks.yfiles.api.generator.Types.UNIT
import org.gradle.api.GradleException
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.*
import kotlin.reflect.KProperty

fun generateKotlinWrappers(sourceData: String, sourceDir: File) {
    val source = JSONObject(sourceData)

    val types = JAPIRoot(source)
            .namespaces.first { it.id == "yfiles" }
            .namespaces.flatMap { it.types }

    ClassRegistry.instance = ClassRegistryImpl(types)

    val fileGenerator = FileGenerator(types)
    fileGenerator.generate(sourceDir)

    sourceDir.resolve("yfiles/lang/Boolean.kt").delete()
    sourceDir.resolve("yfiles/lang/Number.kt").delete()
    sourceDir.resolve("yfiles/lang/String.kt").delete()
    sourceDir.resolve("yfiles/lang/Struct.kt").delete()

    // TODO: generate from signatures
    sourceDir.resolve("yfiles/input/EventRecognizer.kt")
            .writeText(
                    "package yfiles.input\n" +
                            "typealias EventRecognizer = (yfiles.lang.Object, yfiles.lang.EventArgs) -> Boolean",
                    Charset.forName("UTF-8")
            )

    sourceDir.resolve("system").mkdir()
    sourceDir.resolve("system/Comparison.kt")
            .writeText(
                    "package system\n" +
                            "typealias Comparison<T> = (T, T) -> Number",
                    Charset.forName("UTF-8")
            )
}

abstract class JsonWrapper(val source: JSONObject)
abstract class JDeclaration : JsonWrapper {
    companion object {
        fun code(vararg lines: String): String {
            return lines.joinToString("\n")
        }
    }

    val id: String by StringDelegate()
    val name: String by StringDelegate()
    protected val modifiers: JModifiers by ModifiersDelegate()

    val summary: String by StringDelegate()
    val remarks: String by StringDelegate()

    val fqn: String
    val nameOfClass: String

    constructor(source: JSONObject) : super(source) {
        this.fqn = id
        this.nameOfClass = fqn.split(".").last()
    }

    constructor(fqn: String, source: JSONObject) : super(source) {
        this.fqn = fqn
        this.nameOfClass = fqn.split(".").last()
    }

    override fun toString(): String {
        throw GradleException("toString() method must be overridden for object " + this)
    }
}

class JAPIRoot(source: JSONObject) : JsonWrapper(source) {
    val namespaces: List<JNamespace> by ArrayDelegate { JNamespace(it) }
    val functionSignatures: Map<String, JSignature> by MapDelegate { name, source -> JSignature(name, source) }
}

class JNamespace(source: JSONObject) : JsonWrapper(source) {
    companion object {
        fun parseType(source: JSONObject): JType {
            val group = source.getString("group")
            return when (group) {
                "class" -> JClass(source)
                "interface" -> JInterface(source)
                "enum" -> JEnum(source)
                else -> throw GradleException("Undefined type group '$group'")
            }
        }
    }

    val id: String by StringDelegate()
    val name: String by StringDelegate()

    val namespaces: List<JNamespace> by ArrayDelegate { JNamespace(it) }
    val types: List<JType> by ArrayDelegate { parseType(it) }
}

class JSignature(val fqn: String, source: JSONObject) : JsonWrapper(source) {
    val summary: String by StringDelegate()
    val parameters: List<JSignatureParameter> by ArrayDelegate { JSignatureParameter(it) }
    val typeparameters: List<JTypeParameter> by ArrayDelegate { JTypeParameter(it) }
    val returns: JSignatureReturns? by SignatureReturnsDelegate()
}

class JSignatureParameter(source: JSONObject) : JsonWrapper(source) {
    val name: String by StringDelegate()
    val type: String by TypeDelegate { TypeParser.parse(it) }
    val summary: String by StringDelegate()
}

class JSignatureReturns(source: JSONObject) : JsonWrapper(source) {
    val type: String by StringDelegate()
}

abstract class JType(source: JSONObject) : JDeclaration(source) {
    val fields: List<JField> by ArrayDelegate { JField(this.fqn, it) }

    val properties: List<JProperty> by ArrayDelegate { JProperty(this.fqn, it) }
    val staticProperties: List<JProperty> by ArrayDelegate { JProperty(this.fqn, it) }

    val methods: List<JMethod> by ArrayDelegate({ JMethod(this.fqn, it) }, { !Hacks.redundantMethod(it) })
    val staticMethods: List<JMethod> by ArrayDelegate({ JMethod(this.fqn, it) }, { !Hacks.redundantMethod(it) })

    val typeparameters: List<JTypeParameter> by ArrayDelegate { JTypeParameter(it) }

    val extends: String? by NullableStringDelegate()
    val implements: List<String> by StringArrayDelegate()

    fun genericParameters(): String {
        return getGenericString(typeparameters)
    }

    fun extendedType(): String? {
        if (Hacks.ignoreExtendedType(fqn)) {
            return null
        }

        val type = extends ?: return null
        return parseType(type)
    }

    fun implementedTypes(): List<String> {
        var types = Hacks.getImplementedTypes(fqn)
        if (types != null) {
            return types
        }

        types = implements.map { parseType(it) }
        return MixinHacks.getImplementedTypes(fqn, types)
    }
}

class JClass(source: JSONObject) : JType(source) {
    val static = modifiers.static
    val final = modifiers.final
    val open = !final
    val abstract = modifiers.abstract

    val modificator = when {
        abstract -> "abstract" // no such cases (JS specific?)
        open -> "open"
        else -> ""
    }

    val constructors: List<JConstructor> by ArrayDelegate { JConstructor(this.fqn, it) }
}

class JModifiers(flags: List<String>) {
    val static = flags.contains("static")
    val final = flags.contains("final")
    val readOnly = flags.contains("ro")
    val abstract = flags.contains("abstract")
    val protected = flags.contains("protected")
}

class JInterface(source: JSONObject) : JType(source)

class JEnum(source: JSONObject) : JType(source) {
    val constructors: List<JConstructor> by ArrayDelegate { JConstructor(this.fqn, it) }
}

abstract class JTypedDeclaration(fqn: String, source: JSONObject) : JDeclaration(fqn, source) {
    val type: String by TypeDelegate { TypeParser.parse(it) }
}

class JConstructor(fqn: String, source: JSONObject) : JMethodBase(fqn, source) {
    val protected = modifiers.protected

    val modificator: String = when {
        protected -> "protected "
        else -> ""
    }

    override fun toString(): String {
        return "${modificator}constructor(${parametersString()})"
    }
}

class JField(fqn: String, source: JSONObject) : JTypedDeclaration(fqn, source) {
    override fun toString(): String {
        val type = Hacks.correctStaticFieldGeneric(Hacks.getFieldType(this) ?: this.type)
        return "val $name: $type = definedExternally"
    }
}

class JProperty(fqn: String, source: JSONObject) : JTypedDeclaration(fqn, source) {
    val static = modifiers.static
    val protected = modifiers.protected
    val getterSetter = !modifiers.readOnly

    val abstract = modifiers.abstract

    override fun toString(): String {
        var str = ""
        val classRegistry: ClassRegistry = ClassRegistry.instance

        if (classRegistry.propertyOverriden(fqn, name)) {
            str += "override "
        } else {
            if (protected) {
                str += "protected "
            }

            str += when {
                abstract -> "abstract "
                !static && !classRegistry.isFinalClass(fqn) -> "open "
                else -> ""
            }
        }

        str += if (getterSetter) "var " else "val "

        val type = Hacks.getPropertyType(this) ?: this.type
        str += "$name: $type"
        if (!abstract) {
            str += "\n    get() = definedExternally"
            if (getterSetter) {
                str += "\n    set(value) = definedExternally"
            }
        }
        return str
    }
}

class JMethod(fqn: String, source: JSONObject) : JMethodBase(fqn, source) {
    val abstract = modifiers.abstract
    val static = modifiers.static
    val protected = modifiers.protected

    val typeparameters: List<JTypeParameter> by ArrayDelegate { JTypeParameter(it) }
    val returns: JReturns? by ReturnsDelegate()

    val generics: String
        get() = Hacks.getFunctionGenerics(fqn, name) ?: getGenericString(typeparameters)

    private fun modificator(canBeOpen: Boolean = true): String {
        val classRegistry: ClassRegistry = ClassRegistry.instance

        // TODO: add abstract modificator if needed
        if (classRegistry.functionOverriden(fqn, name)) {
            return "override "
        }

        val result = when {
            abstract -> "abstract "
            canBeOpen && !static && !classRegistry.isFinalClass(fqn) -> "open "
            else -> ""
        }
        return result + when {
            protected -> "protected "
            else -> ""
        }
    }

    override fun toString(): String {
        val returnType = returns?.type ?: UNIT
        val body = if (abstract) "" else " = definedExternally"
        return "${modificator()}fun $generics$name(${parametersString()}): $returnType$body"
    }
}

abstract class JMethodBase(fqn: String, source: JSONObject) : JDeclaration(fqn, source) {
    val parameters: List<JParameter> by ArrayDelegate({ JParameter(this, it) }, { !it.artificial })

    protected fun mapString(parameters: List<JParameter>): String {
        return "mapOf<String, Any?>(\n" +
                parameters.map { "\"${it.getCorrectedName()}\" to ${it.getCorrectedName()}" }.joinToString(",\n") +
                "\n)\n"
    }

    protected fun parametersString(checkOverriding: Boolean = true): String {
        val overridden = checkOverriding && ClassRegistry.instance.functionOverriden(fqn, name)
        return parameters.map {
            if (overridden) {
                if (it.optional && !overridden) {
                    println("Optional - ${fqn}.${name} -  ${it.getCorrectedName()}")
                }
            }

            val body = if (it.optional && !overridden) " = definedExternally" else ""
            "${it.getCorrectedName()}: ${getParameterType(this, it) ?: it.type}" + body

        }
                .joinToString(", ")
    }

    override fun hashCode(): Int {
        return Objects.hash(fqn, name, parametersString(false))
    }

    override fun equals(other: Any?): Boolean {
        return other is JMethodBase
                && Objects.equals(fqn, other.fqn)
                && Objects.equals(name, other.name)
                && Objects.equals(parametersString(false), other.parametersString(false))
    }
}

class JParameter(private val method: JMethodBase, source: JSONObject) : JsonWrapper(source) {
    private val name: String by StringDelegate()
    val artificial: Boolean by BooleanDelegate()
    val type: String by TypeDelegate { TypeParser.parse(it) }
    val summary: String? by NullableStringDelegate()
    val optional: Boolean by BooleanDelegate()

    fun getCorrectedName(): String {
        return Hacks.fixParameterName(method, name) ?: name
    }
}

class JTypeParameter(source: JSONObject) : JsonWrapper(source) {
    val name: String by StringDelegate()
}

class JReturns(val type: String, source: JSONObject) : JsonWrapper(source)

class ArrayDelegate<T> {

    private val transform: (JSONObject) -> T
    private val filter: (T) -> Boolean

    constructor(transform: (JSONObject) -> T) : this(transform, { true })

    constructor(transform: (JSONObject) -> T, filter: (T) -> Boolean) {
        this.transform = transform
        this.filter = filter
    }

    operator fun getValue(thisRef: JsonWrapper, property: KProperty<*>): List<T> {
        val source = thisRef.source
        val key = property.name

        if (!source.has(key)) {
            return emptyList()
        }

        val array = source.getJSONArray(key)
        val length = array.length()
        if (length == 0) {
            return emptyList()
        }

        val list = mutableListOf<T>()
        for (i in 0..length - 1) {
            val item = transform(array.getJSONObject(i))
            if (filter(item)) {
                list.add(item)
            }
        }
        return list.toList()
    }
}

class StringArrayDelegate {
    companion object {
        fun value(thisRef: JsonWrapper, property: KProperty<*>): List<String> {
            val source = thisRef.source
            val key = property.name

            if (!source.has(key)) {
                return emptyList()
            }

            val array = source.getJSONArray(key)
            val length = array.length()
            if (length == 0) {
                return emptyList()
            }

            val list = mutableListOf<String>()
            for (i in 0..length - 1) {
                list.add(array.getString(i))
            }
            return list.toList()
        }
    }

    operator fun getValue(thisRef: JsonWrapper, property: KProperty<*>): List<String> {
        return value(thisRef, property)
    }
}

class MapDelegate<T>(private val transform: (name: String, source: JSONObject) -> T) {

    operator fun getValue(thisRef: JsonWrapper, property: KProperty<*>): Map<String, T> {
        val source = thisRef.source
        val key = property.name

        if (!source.has(key)) {
            return emptyMap()
        }

        val data = source.getJSONObject(key)
        val keys: List<String> = data.keySet()?.toList() ?: emptyList<String>()
        if (keys.isEmpty()) {
            return emptyMap()
        }

        return keys.associateBy({ it }, { transform(it, data.getJSONObject(it)) })
    }
}

class NullableStringDelegate {
    operator fun getValue(thisRef: JsonWrapper, property: KProperty<*>): String? {
        val source = thisRef.source
        val key = property.name

        return if (source.has(key)) source.getString(key) else null
    }
}

class StringDelegate {
    companion object {
        fun value(thisRef: JsonWrapper, property: KProperty<*>): String {
            return thisRef.source.getString(property.name)
        }
    }

    operator fun getValue(thisRef: JsonWrapper, property: KProperty<*>): String {
        return value(thisRef, property)
    }
}

class BooleanDelegate {
    operator fun getValue(thisRef: JsonWrapper, property: KProperty<*>): Boolean {
        val source = thisRef.source
        val key = property.name

        if (!source.has(key)) {
            return false
        }

        val value = source.getString(key)
        return when (value) {
            "!0" -> true
            "!1" -> false
            else -> source.getBoolean(key)
        }
    }
}

class TypeDelegate(private val parse: (String) -> String) {
    operator fun getValue(thisRef: JsonWrapper, property: KProperty<*>): String {
        return parse(StringDelegate.value(thisRef, property))
    }
}

class ModifiersDelegate {
    operator fun getValue(thisRef: JsonWrapper, property: KProperty<*>): JModifiers {
        return JModifiers(StringArrayDelegate.value(thisRef, property))
    }
}

class SignatureReturnsDelegate {
    operator fun getValue(thisRef: JsonWrapper, property: KProperty<*>): JSignatureReturns? {
        val source = thisRef.source
        val key = property.name

        return if (source.has(key)) {
            JSignatureReturns(source.getJSONObject(key))
        } else {
            null
        }
    }
}

class ReturnsDelegate {
    operator fun getValue(thisRef: JMethod, property: KProperty<*>): JReturns? {
        val source = thisRef.source
        val key = property.name

        return if (source.has(key)) {
            val data = source.getJSONObject(key)
            var type = TypeParser.parse(data.getString("type"))
            type = Hacks.getReturnType(thisRef, type) ?: type
            JReturns(type, data)
        } else {
            null
        }
    }
}

object Types {
    val UNIT = "Unit"
    val OBJECT_TYPE = "yfiles.lang.Object"
    val CLASS_TYPE = "yfiles.lang.Class"
    val ENUM_TYPE = "yfiles.lang.Enum"

    val NODE_TYPE = "yfiles.graph.INode"
    val EDGE_TYPE = "yfiles.graph.IEdge"
    val PORT_TYPE = "yfiles.graph.IPort"
    val LABEL_TYPE = "yfiles.graph.ILabel"
    val BEND_TYPE = "yfiles.graph.IBend"
    val ROW_TYPE = "yfiles.graph.IRow"
    val COLUMN_TYPE = "yfiles.graph.IColumn"
}

object TypeParser {
    private val FUNCTION_START = "function("
    private val FUNCTION_END = "):"
    private val FUNCTION_END_VOID = ")"

    private val GENERIC_START = "<"
    private val GENERIC_END = ">"

    private val STANDARD_TYPE_MAP = mapOf(
            "Object" to OBJECT_TYPE,
            "object" to OBJECT_TYPE,
            "boolean" to "Boolean",
            "string" to "String",
            "number" to "Number",
            "Date" to "kotlin.js.Date",
            "void" to UNIT,
            "Function" to "() -> $UNIT",

            "Event" to "org.w3c.dom.events.Event",
            "KeyboardEvent" to "org.w3c.dom.events.KeyboardEvent",
            "Document" to "org.w3c.dom.Document",
            "Node" to "org.w3c.dom.Node",
            "Element" to "org.w3c.dom.Element",
            "HTMLElement" to "org.w3c.dom.HTMLElement",
            "HTMLInputElement" to "org.w3c.dom.HTMLInputElement",
            "HTMLDivElement" to "org.w3c.dom.HTMLDivElement",
            "SVGElement" to "org.w3c.dom.svg.SVGElement",
            "SVGDefsElement" to "org.w3c.dom.svg.SVGDefsElement",
            "SVGGElement" to "org.w3c.dom.svg.SVGGElement",
            "SVGImageElement" to "org.w3c.dom.svg.SVGImageElement",
            "SVGPathElement" to "org.w3c.dom.svg.SVGPathElement",
            "SVGTextElement" to "org.w3c.dom.svg.SVGTextElement",
            "CanvasRenderingContext2D" to "org.w3c.dom.CanvasRenderingContext2D",

            // TODO: check if Kotlin promises is what we need in yFiles
            "Promise" to "kotlin.js.Promise"
    )

    fun parse(type: String): String {
        if (type.startsWith(FUNCTION_START)) {
            return parseFunctionType(type)
        }
        return parseType(type)
    }

    fun parseType(type: String): String {
        // TODO: Fix for SvgDefsManager and SvgVisual required (2 constructors from 1)
        if (type.contains("|")) {
            return parseType(type.split("|")[0])
        }

        val standardType = STANDARD_TYPE_MAP[type]
        if (standardType != null) {
            return standardType
        }

        if (!type.contains(GENERIC_START)) {
            return type
        }

        val mainType = parseType(StringUtil.till(type, GENERIC_START))
        val parametrizedTypes = parseGenericParameters(StringUtil.between(type, GENERIC_START, GENERIC_END))
        return "$mainType<${parametrizedTypes.joinToString(", ")}>"
    }

    fun getGenericString(parameters: List<JTypeParameter>): String {
        return if (parameters.isEmpty()) "" else "<${parameters.map { it.name }.joinToString(", ")}> "
    }

    fun parseGenericParameters(parameters: String): List<String> {
        // TODO: temp hack for generic, logic check required
        if (!parameters.contains(GENERIC_START)) {
            return if (parameters.contains(FUNCTION_START)) {
                // TODO: realize full logic if needed
                parameters.split(delimiters = ",", limit = 2).map {
                    if (it.startsWith(FUNCTION_START)) parseFunctionType(it) else parseType(it)
                }
            } else {
                parameters.split(",").map { parseType(it) }
            }
        }

        val firstType = firstGenericType(parameters)
        if (firstType == parameters) {
            return listOf(parseType(firstType))
        }

        val types = mutableListOf(firstType)
        types.addAll(parseGenericParameters(parameters.substring(firstType.length + 1)))
        return types.toList()
    }

    fun firstGenericType(parameters: String): String {
        var semafor = 0
        var index = 0

        while (true) {
            val indexes = listOf(
                    parameters.indexOf(",", index),
                    parameters.indexOf("<", index),
                    parameters.indexOf(">", index)
            )

            if (indexes.all { it == -1 }) {
                return parameters
            }

            // TODO: check calculation
            index = indexes.map({ if (it == -1) 100000 else it })
                    .minWith(Comparator { o1, o2 -> Math.min(o1, o2) }) ?: -1

            if (index == -1 || index == parameters.lastIndex) {
                return parameters
            }

            when (indexes.indexOf(index)) {
                0 -> if (semafor == 0) return parameters.substring(0, index)
                1 -> semafor++
                2 -> semafor--
            }
            index++
        }
    }

    fun parseFunctionType(type: String): String {
        val voidResult = type.endsWith(FUNCTION_END_VOID)
        val functionEnd = if (voidResult) FUNCTION_END_VOID else FUNCTION_END
        val parameterTypes = StringUtil.between(type, FUNCTION_START, functionEnd)
                .split(",").map({ parseType(it) })
        val resultType = if (voidResult) UNIT else parseType(StringUtil.from(type, FUNCTION_END))
        return "(${parameterTypes.joinToString(", ")}) -> $resultType"
    }
}

interface ClassRegistry {
    companion object {
        private var _instance: ClassRegistry = EmptyClassRegistry()

        var instance: ClassRegistry
            get() {
                return _instance
            }
            set(value) {
                _instance = value
            }
    }

    fun isInterface(className: String): Boolean
    fun isFinalClass(className: String): Boolean
    fun functionOverriden(className: String, functionName: String): Boolean
    fun propertyOverriden(className: String, functionName: String): Boolean

    private class EmptyClassRegistry : ClassRegistry {
        override fun isInterface(className: String): Boolean {
            return false
        }

        override fun isFinalClass(className: String): Boolean {
            return false
        }

        override fun functionOverriden(className: String, functionName: String): Boolean {
            return false
        }

        override fun propertyOverriden(className: String, functionName: String): Boolean {
            return false
        }

    }
}

class ClassRegistryImpl(types: List<JType>) : ClassRegistry {
    private val instances = types.associateBy({ it.fqn }, { it })

    private val functionsMap = types.associateBy(
            { it.fqn },
            { it.methods.map { it.name } }
    )

    private val propertiesMap = types.associateBy(
            { it.fqn },
            { it.properties.map { it.name } }
    )

    private val propertiesMap2 = types.associateBy(
            { it.fqn },
            { it.properties.associateBy({ it.name }, { it.getterSetter }) }
    )

    private fun getParents(className: String): List<String> {
        val instance = instances[className] ?: throw GradleException("Unknown instance type: $className")

        return mutableListOf<String>()
                .union(listOf(instance.extendedType()).filterNotNull())
                .union(instance.implementedTypes())
                .map { if (it.contains("<")) StringUtil.till(it, "<") else it }
                .toList()
    }

    private fun functionOverriden(className: String, functionName: String, checkCurrentClass: Boolean): Boolean {
        if (checkCurrentClass) {
            val funs = functionsMap[className] ?: throw GradleException("No functions found for type: $className")
            if (funs.contains(functionName)) {
                return true
            }
        }
        return getParents(className).any {
            functionOverriden(it, functionName, true)
        }
    }

    private fun propertyOverriden(className: String, propertyName: String, checkCurrentClass: Boolean): Boolean {
        if (checkCurrentClass) {
            val props = propertiesMap[className] ?: throw GradleException("No properties found for type: $className")
            if (props.contains(propertyName)) {
                return true
            }
        }
        return getParents(className).any {
            propertyOverriden(it, propertyName, true)
        }
    }

    override fun isInterface(className: String): Boolean {
        return instances[className] is JInterface
    }

    override fun isFinalClass(className: String): Boolean {
        val instance = instances[className]
        return instance is JClass && instance.final
    }

    override fun functionOverriden(className: String, functionName: String): Boolean {
        return functionOverriden(className, functionName, false)
    }

    override fun propertyOverriden(className: String, functionName: String): Boolean {
        return propertyOverriden(className, functionName, false)
    }
}

class FileGenerator(private val types: List<JType>) {
    fun generate(directory: File) {
        directory.mkdirs()
        directory.deleteRecursively()

        types.forEach {
            val generatedFile = when (it) {
                is JClass -> ClassFile(it)
                is JInterface -> InterfaceFile(it)
                is JEnum -> EnumFile(it)
                else -> throw GradleException("Undefined type for generation: " + it)
            }

            generate(directory, generatedFile)
        }
    }

    private fun generate(directory: File, generatedFile: GeneratedFile) {
        val fqn = generatedFile.fqn
        val dir = directory.resolve(fqn.path)
        dir.mkdirs()

        val file = dir.resolve("${fqn.name}.kt")
        file.writeText("${generatedFile.header}\n${generatedFile.content()}")
    }

    class FQN(val fqn: String) {
        private val names = fqn.split(".")
        private val packageNames = names.subList(0, names.size - 1)

        val name = names.last()
        val packageName = packageNames.joinToString(separator = ".")
        val path = packageNames.joinToString(separator = "/")

        override fun equals(other: Any?): Boolean {
            return other is FQN && other.fqn == fqn
        }

        override fun hashCode(): Int {
            return fqn.hashCode()
        }
    }

    abstract class GeneratedFile(private val declaration: JType) {
        val className = declaration.fqn
        val fqn: FQN = FQN(className)

        val properties: List<JProperty>
            get() = declaration.properties
                    .sortedBy { it.name }

        val staticFields: List<JField>
            get() = declaration.fields
                    .sortedBy { it.name }

        val staticProperties: List<JProperty>
            get() = declaration.staticProperties
                    .sortedBy { it.name }

        val staticFunctions: List<JMethod>
            get() = declaration.staticMethods
                    .sortedBy { it.name }

        val staticDeclarations: List<JDeclaration>
            get() {
                return mutableListOf<JDeclaration>()
                        .union(staticFields)
                        .union(staticProperties)
                        .union(staticFunctions)
                        .toList()
            }

        val memberProperties: List<JProperty>
            get() = properties.filter { !it.static }

        val memberFunctions: List<JMethod>
            get() = declaration.methods
                    .sortedBy { it.name }

        val header: String
            get() = "package ${fqn.packageName}\n"

        open protected fun parentTypes(): List<String> {
            return declaration.implementedTypes()
        }

        protected fun parentString(): String {
            val parentTypes = parentTypes()
            if (parentTypes.isEmpty()) {
                return ""
            }
            return ": " + parentTypes.joinToString(", ")
        }

        fun genericParameters(): String {
            return declaration.genericParameters()
        }

        open protected fun isStatic(): Boolean {
            return false
        }

        protected fun companionContent(): String {
            val items = staticDeclarations.map {
                it.toString()
            }

            if (items.isEmpty()) {
                return when {
                    isStatic() -> ""
                // TODO: add companion only if needed
                    else -> "    companion object {} \n\n"
                }
            }

            val result = items.joinToString("\n") + "\n"
            if (isStatic()) {
                return result
            }

            return "    companion object {\n" +
                    result +
                    "    }\n"
        }

        open fun content(): String {
            return listOf<JDeclaration>()
                    .union(memberProperties)
                    .union(memberFunctions)
                    .union(listOf(getAdditionalContent(declaration.fqn)))
                    .joinToString("\n") + "\n"
        }
    }

    class ClassFile(private val declaration: JClass) : GeneratedFile(declaration) {
        override fun isStatic(): Boolean {
            return declaration.static
        }

        private fun type(): String {
            if (isStatic()) {
                return "object"
            }

            val modificator = if (memberFunctions.any { it.abstract } || memberProperties.any { it.abstract }) {
                "abstract"
            } else {
                declaration.modificator
            }

            return modificator + " class"
        }

        private fun constructors(): String {
            val constructorSet = declaration.constructors.toSet()
            return constructorSet.map {
                it.toString()
            }.joinToString("\n") + "\n"
        }

        override fun parentTypes(): List<String> {
            val extendedType = declaration.extendedType()
            if (extendedType == null) {
                return super.parentTypes()
            }

            return listOf(extendedType)
                    .union(super.parentTypes())
                    .toList()
        }

        override fun content(): String {
            return "external ${type()} ${fqn.name}${genericParameters()}${parentString()} {\n" +
                    companionContent() +
                    constructors() +
                    super.content() + "\n" +
                    "}"
        }
    }

    class InterfaceFile(declaration: JInterface) : GeneratedFile(declaration) {
        override fun content(): String {
            var content = super.content()
            val likeAbstractClass = MixinHacks.defineLikeAbstractClass(className, memberFunctions, memberProperties)
            if (!likeAbstractClass) {
                content = content.replace("abstract ", "")
                        .replace("open fun", "fun")
                        .replace("\n    get() = definedExternally", "")
                        .replace("\n    set(value) = definedExternally", "")
                        .replace(" = definedExternally", "")
            }

            val type = if (likeAbstractClass) "abstract class" else "interface"
            return "external $type ${fqn.name}${genericParameters()}${parentString()} {\n" +
                    companionContent() +
                    content + "\n" +
                    "}\n"
        }
    }

    class EnumFile(private val declaration: JEnum) : GeneratedFile(declaration) {
        override fun content(): String {
            val values = declaration.fields
                    .map { "    val ${it.name}: ${it.nameOfClass} = definedExternally" }
                    .joinToString("\n")
            return "external object ${fqn.name}: ${ENUM_TYPE} {\n" +
                    values + "\n\n" +
                    super.content() + "\n" +
                    "}\n"
        }
    }

    class JsInfo {
        private val COMPLETE = "yfiles/complete"
        private val VIEW = "yfiles/view"
        private val LAYOUT = "yfiles/layout"

        private val LANG = "yfiles/lang"

        private val VIEW_COMPONENT = "yfiles/view-component"
        private val VIEW_EDITOR = "yfiles/view-editor"
        private val VIEW_FOLDING = "yfiles/view-folding"
        private val VIEW_TABLE = "yfiles/view-table"
        private val VIEW_GRAPHML = "yfiles/view-graphml"
        private val VIEW_LAYOUT_BRIDGE = "yfiles/view-layout-bridge"
        private val ALGORITHMS = "yfiles/algorithms"
        private val LAYOUT_TREE = "yfiles/layout-tree"
        private val LAYOUT_ORGANIC = "yfiles/layout-organic"
        private val LAYOUT_HIERARCHIC = "yfiles/layout-hierarchic"
        private val LAYOUT_ORTHOGONAL = "yfiles/layout-orthogonal"
        private val LAYOUT_ORTHOGONAL_COMPACT = "yfiles/layout-orthogonal-compact"
        private val LAYOUT_FAMILYTREE = "yfiles/layout-familytree"
        private val LAYOUT_MULTIPAGE = "yfiles/layout-multipage"
        private val LAYOUT_RADIAL = "yfiles/layout-radial"
        private val LAYOUT_SERIESPARALLEL = "yfiles/layout-seriesparallel"
        private val ROUTER_POLYLINE = "yfiles/router-polyline"
        private val ROUTER_OTHER = "yfiles/router-other"
    }
}

object StringUtil {
    fun between(str: String, start: String, end: String, firstEnd: Boolean = false): String {
        val startIndex = str.indexOf(start)
        if (startIndex == -1) {
            throw GradleException("String '$str' doesn't contains '$start'")
        }

        val endIndex = if (firstEnd) {
            str.indexOf(end)
        } else {
            str.lastIndexOf(end)
        }
        if (endIndex == -1) {
            throw GradleException("String '$str' doesn't contains '$end'")
        }

        return str.substring(startIndex + start.length, endIndex)
    }

    fun hardBetween(str: String, start: String, end: String): String {
        if (!str.startsWith(start)) {
            throw GradleException("String '$str' not started from '$start'")
        }

        if (!str.endsWith(end)) {
            throw GradleException("String '$str' not ended with '$end'")
        }

        return str.substring(start.length, str.length - end.length)
    }

    fun till(str: String, end: String): String {
        val endIndex = str.indexOf(end)
        if (endIndex == -1) {
            throw GradleException("String '$str' doesn't contains '$end'")
        }

        return str.substring(0, endIndex)
    }

    fun from(str: String, start: String): String {
        val startIndex = str.lastIndexOf(start)
        if (startIndex == -1) {
            throw GradleException("String '$str' doesn't contains '$start'")
        }

        return str.substring(startIndex + start.length)
    }

    fun from2(str: String, start: String): String {
        val startIndex = str.indexOf(start)
        if (startIndex == -1) {
            throw GradleException("String '$str' doesn't contains '$start'")
        }

        return str.substring(startIndex + start.length)
    }
}

object Hacks {
    val SYSTEM_FUNCTIONS = listOf("hashCode", "toString")

    fun redundantMethod(method: JMethod): Boolean {
        return method.name in SYSTEM_FUNCTIONS && method.parameters.isEmpty()
    }

    fun correctStaticFieldGeneric(type: String): String {
        // TODO: Check. Quick fix for generics in constants
        // One case - IListEnumerable.EMPTY
        return type.replace("<T>", "<out Any>")
    }

    fun getFunctionGenerics(className: String, name: String): String? {
        return when {
            className == "yfiles.collections.List" && name == "fromArray" -> "<T>"
            else -> null
        }
    }

    fun filterConstructorAdapters(className: String, adapters: List<JConstructor>): List<JConstructor> {
        return when (className) {
            "yfiles.styles.NodeStylePortStyleAdapter" -> adapters.toSet().toList()
            else -> adapters
        }
    }

    val LAYOUT_GRAPH_CLASSES = listOf(
            "yfiles.layout.CopiedLayoutGraph",
            "yfiles.layout.DefaultLayoutGraph",
            "yfiles.layout.LayoutGraph"
    )

    fun getReturnType(method: JMethod, type: String): String? {
        val className = method.fqn
        val methodName = method.name

        when {
            className == "yfiles.algorithms.EdgeList" && methodName == "getEnumerator" -> return "yfiles.collections.IEnumerator<$OBJECT_TYPE>"
            className == "yfiles.algorithms.NodeList" && methodName == "getEnumerator" -> return "yfiles.collections.IEnumerator<$OBJECT_TYPE>"
        }

        if (type != "Array") {
            return null
        }

        val generic = when {
            className == "yfiles.collections.List" && methodName == "toArray" -> "T"
            className == "yfiles.algorithms.Dendrogram" && methodName == "getClusterNodes" -> "yfiles.algorithms.NodeList"
            className == "yfiles.algorithms.EdgeList" && methodName == "toEdgeArray" -> "yfiles.algorithms.Edge"
            className == "yfiles.algorithms.Graph" && methodName == "getEdgeArray" -> "yfiles.algorithms.Edge"
            className == "yfiles.algorithms.Graph" && methodName == "getNodeArray" -> "yfiles.algorithms.Node"
            className == "yfiles.algorithms.NodeList" && methodName == "toNodeArray" -> "yfiles.algorithms.Node"
            className == "yfiles.algorithms.PlanarEmbedding" && methodName == "getDarts" -> "yfiles.algorithms.Dart"
            className == "yfiles.algorithms.YList" && methodName == "toArray" -> OBJECT_TYPE
            className == "yfiles.algorithms.YPointPath" && methodName == "toArray" -> "yfiles.algorithms.YPoint"
            className == "yfiles.collections.IEnumerable" && methodName == "toArray" -> "T"
            className == "yfiles.lang.Class" && methodName == "getAttributes" -> "yfiles.lang.Attribute"
            className == "yfiles.lang.Class" && methodName == "getProperties" -> "yfiles.lang.PropertyInfo"
            className == "yfiles.lang.PropertyInfo" && methodName == "getAttributes" -> "yfiles.lang.Attribute"
            className in LAYOUT_GRAPH_CLASSES && methodName == "getLabelLayout" -> {
                when (method.parameters.first().getCorrectedName()) {
                    "node" -> "yfiles.layout.INodeLabelLayout"
                    "edge" -> "yfiles.layout.IEdgeLabelLayout"
                    else -> null // TODO: throw error
                }
            }
            className == "yfiles.layout.LayoutGraphAdapter" && methodName == "getEdgeLabelLayout" -> "yfiles.layout.IEdgeLabelLayout"
            className == "yfiles.layout.LayoutGraphAdapter" && methodName == "getNodeLabelLayout" -> "yfiles.layout.INodeLabelLayout"
            className == "yfiles.layout.TableLayoutConfigurator" && methodName == "getColumnLayout" -> "Number"
            className == "yfiles.layout.TableLayoutConfigurator" && methodName == "getRowLayout" -> "Number"
            className == "yfiles.router.EdgeInfo" && methodName == "calculateLineSegments" -> "yfiles.algorithms.LineSegment"
            className == "yfiles.tree.TreeLayout" && methodName == "getRootsArray" -> "yfiles.algorithms.Node"

            className == "yfiles.algorithms.Bfs" && methodName == "getLayers" -> "yfiles.algorithms.NodeList"
            className == "yfiles.algorithms.Cursors" && methodName == "toArray" -> OBJECT_TYPE
            className == "yfiles.algorithms.GraphConnectivity" && methodName == "biconnectedComponents" -> "yfiles.algorithms.EdgeList"
            className == "yfiles.algorithms.GraphConnectivity" && methodName == "connectedComponents" -> "yfiles.algorithms.NodeList"
            className == "yfiles.algorithms.GraphConnectivity" && methodName == "stronglyConnectedComponents" -> "yfiles.algorithms.NodeList"
            className == "yfiles.algorithms.GraphConnectivity" && methodName == "toEdgeListArray" -> "yfiles.algorithms.EdgeList"
            className == "yfiles.algorithms.GraphConnectivity" && methodName == "toNodeListArray" -> "yfiles.algorithms.NodeList"
            className == "yfiles.algorithms.IndependentSets" && methodName == "getIndependentSets" -> "yfiles.algorithms.NodeList"
            className == "yfiles.algorithms.Paths" && methodName == "findAllChains" -> "yfiles.algorithms.EdgeList"
            className == "yfiles.algorithms.Paths" && methodName == "findAllPaths" -> "yfiles.algorithms.EdgeList"
            className == "yfiles.algorithms.Paths" && methodName == "findAllPaths" -> "yfiles.algorithms.EdgeList"
            className == "yfiles.algorithms.ShortestPaths" && methodName == "shortestPair" -> "yfiles.algorithms.EdgeList"
            className == "yfiles.algorithms.ShortestPaths" && methodName == "uniformCost" -> "Number"
            className == "yfiles.algorithms.Sorting" && methodName == "sortNodesByDegree" -> "yfiles.algorithms.Node"
            className == "yfiles.algorithms.Sorting" && methodName == "sortNodesByIntKey" -> "yfiles.algorithms.Node"
            className == "yfiles.algorithms.Trees" && methodName == "getTreeEdges" -> "yfiles.algorithms.EdgeList"
            className == "yfiles.algorithms.Trees" && methodName == "getTreeNodes" -> "yfiles.algorithms.NodeList"
            className == "yfiles.algorithms.Trees" && methodName == "getUndirectedTreeNodes" -> "yfiles.algorithms.NodeList"
            className == "yfiles.algorithms.YOrientedRectangle" && methodName == "calcPoints" -> "YPoint"
            className == "yfiles.algorithms.YOrientedRectangle" && methodName == "calcPointsInDouble" -> "Number"
            className == "yfiles.lang.delegate" && methodName == "getInvocationList" -> "yfiles.lang.delegate"
            className == "yfiles.router.BusRepresentations" && methodName == "toEdgeLists" -> "yfiles.algorithms.EdgeList"

            else -> throw GradleException("Unable find array generic for className: '$className' and method: '$methodName'")
        }

        return "Array<$generic>"
    }


    fun ignoreExtendedType(className: String): Boolean {
        return when (className) {
            "yfiles.lang.Exception" -> true
            else -> false
        }
    }

    fun getImplementedTypes(className: String): List<String>? {
        return when (className) {
            "yfiles.algorithms.EdgeList" -> emptyList()
            "yfiles.algorithms.NodeList" -> emptyList()
            else -> null
        }
    }

    private val ARRAY_GENERIC_CORRECTION = mapOf(
            ParameterData("yfiles.algorithms.Cursors", "toArray", "dest") to OBJECT_TYPE,

            ParameterData("yfiles.algorithms.DataProviders", "createEdgeDataProvider", "data") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createEdgeDataProviderForArrays", "boolData") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createEdgeDataProviderForArrays", "doubleData") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createEdgeDataProviderForArrays", "intData") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createEdgeDataProviderForArrays", "objectData") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createEdgeDataProviderForBoolean", "data") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createEdgeDataProviderForInt", "data") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createEdgeDataProviderForNumber", "data") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createNodeDataProvider", "data") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createNodeDataProviderForBoolean", "data") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createNodeDataProviderForInt", "data") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createNodeDataProviderForNumber", "data") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createNodeDataProviderWithArrays", "boolData") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createNodeDataProviderWithArrays", "doubleData") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createNodeDataProviderWithArrays", "intData") to "Any",
            ParameterData("yfiles.algorithms.DataProviders", "createNodeDataProviderWithArrays", "objectData") to "Any",

            ParameterData("yfiles.algorithms.EdgeList", "constructor", "a") to "yfiles.algorithms.Edge",
            ParameterData("yfiles.algorithms.GraphConnectivity", "reachable", "forbidden") to "Boolean",
            ParameterData("yfiles.algorithms.GraphConnectivity", "reachable", "reached") to "Boolean",
            ParameterData("yfiles.algorithms.Groups", "kMeansClustering", "centroids") to "YPoint",

            ParameterData("yfiles.algorithms.Maps", "createIndexEdgeMap", "data") to OBJECT_TYPE,
            ParameterData("yfiles.algorithms.Maps", "createIndexEdgeMapForBoolean", "data") to "Boolean",
            ParameterData("yfiles.algorithms.Maps", "createIndexEdgeMapForInt", "data") to "Number",
            ParameterData("yfiles.algorithms.Maps", "createIndexEdgeMapForNumber", "data") to "Number",
            ParameterData("yfiles.algorithms.Maps", "createIndexEdgeMapFromArrays", "boolData") to "Boolean",
            ParameterData("yfiles.algorithms.Maps", "createIndexEdgeMapFromArrays", "doubleData") to "Number",
            ParameterData("yfiles.algorithms.Maps", "createIndexEdgeMapFromArrays", "intData") to "Number",
            ParameterData("yfiles.algorithms.Maps", "createIndexEdgeMapFromArrays", "objectData") to OBJECT_TYPE,
            ParameterData("yfiles.algorithms.Maps", "createIndexNodeMap", "data") to OBJECT_TYPE,
            ParameterData("yfiles.algorithms.Maps", "createIndexNodeMapForBoolean", "data") to "Boolean",
            ParameterData("yfiles.algorithms.Maps", "createIndexNodeMapForInt", "data") to "Number",
            ParameterData("yfiles.algorithms.Maps", "createIndexNodeMapForNumber", "data") to "Number",
            ParameterData("yfiles.algorithms.Maps", "createIndexNodeMapFromArrays", "boolData") to "Boolean",
            ParameterData("yfiles.algorithms.Maps", "createIndexNodeMapFromArrays", "doubleData") to "Number",
            ParameterData("yfiles.algorithms.Maps", "createIndexNodeMapFromArrays", "intData") to "Number",
            ParameterData("yfiles.algorithms.Maps", "createIndexNodeMapFromArrays", "objectData") to OBJECT_TYPE,

            ParameterData("yfiles.algorithms.NodeList", "constructor", "a") to "yfiles.algorithms.Node",
            ParameterData("yfiles.algorithms.NodeOrders", "dfsCompletion", "order") to "Number",
            ParameterData("yfiles.algorithms.NodeOrders", "st", "stOrder") to "Number",
            ParameterData("yfiles.algorithms.NodeOrders", "toNodeList", "order") to "Number",
            ParameterData("yfiles.algorithms.NodeOrders", "toNodeMap", "order") to "Number",
            ParameterData("yfiles.algorithms.NodeOrders", "topological", "order") to "Number",
            ParameterData("yfiles.algorithms.RankAssignments", "simple", "minLength") to "Number",
            ParameterData("yfiles.algorithms.RankAssignments", "simple", "rank") to "Number",

            ParameterData("yfiles.algorithms.ShortestPaths", "acyclic", "cost") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "acyclic", "dist") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "acyclic", "pred") to "yfiles.algorithms.Edge",
            ParameterData("yfiles.algorithms.ShortestPaths", "allPairs", "cost") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "allPairs", "dist") to "Array<Number>",
            ParameterData("yfiles.algorithms.ShortestPaths", "bellmanFord", "cost") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "bellmanFord", "dist") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "bellmanFord", "pred") to "yfiles.algorithms.Edge",
            ParameterData("yfiles.algorithms.ShortestPaths", "constructEdgePath", "pred") to "yfiles.algorithms.Edge",
            ParameterData("yfiles.algorithms.ShortestPaths", "constructNodePath", "pred") to "yfiles.algorithms.Edge",
            ParameterData("yfiles.algorithms.ShortestPaths", "dijkstra", "cost") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "dijkstra", "dist") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "dijkstra", "pred") to "yfiles.algorithms.Edge",
            ParameterData("yfiles.algorithms.ShortestPaths", "singleSource", "cost") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "singleSource", "dist") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "singleSource", "pred") to "yfiles.algorithms.Edge",
            ParameterData("yfiles.algorithms.ShortestPaths", "singleSourceSingleSink", "cost") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "singleSourceSingleSink", "pred") to "yfiles.algorithms.Edge",
            ParameterData("yfiles.algorithms.ShortestPaths", "uniform", "dist") to "Number",
            ParameterData("yfiles.algorithms.ShortestPaths", "uniform", "pred") to "yfiles.algorithms.Edge",

            ParameterData("yfiles.algorithms.Trees", "getTreeEdges", "treeNodes") to "yfiles.algorithms.NodeList",
            ParameterData("yfiles.algorithms.YList", "constructor", "a") to OBJECT_TYPE,
            ParameterData("yfiles.algorithms.YList", "copyTo", "array") to OBJECT_TYPE,
            ParameterData("yfiles.algorithms.YPointPath", "constructor", "path") to "YPoint",
            ParameterData("yfiles.collections.ICollection", "copyTo", "array") to "T",
            ParameterData("yfiles.collections.List", "copyTo", "array") to "T",
            ParameterData("yfiles.collections.List", "fromArray", "array") to "T",
            ParameterData("yfiles.collections.Map", "copyTo", "array") to "MapEntry<TKey, TValue>",
            ParameterData("yfiles.collections.ObservableCollection", "copyTo", "array") to "T",

            ParameterData("yfiles.geometry.GeneralPathCursor", "getCurrent", "coordinates") to "Number",
            ParameterData("yfiles.geometry.GeneralPathCursor", "getCurrentEndPoint", "coordinates") to "Number",
            ParameterData("yfiles.graph.GroupingSupport", "getNearestCommonAncestor", "nodes") to NODE_TYPE,
            ParameterData("yfiles.hierarchic.IItemFactory", "createDistanceNode", "edges") to "yfiles.algorithms.Edge",
            ParameterData("yfiles.hierarchic.MultiComponentLayerer", "sort", "nodeLists") to "yfiles.algorithms.NodeList",
            ParameterData("yfiles.input.EventRecognizers", "createAndRecognizer", "recognizers") to "yfiles.input.EventRecognizer",
            ParameterData("yfiles.input.EventRecognizers", "createOrRecognizer", "recognizers") to "yfiles.input.EventRecognizer",
            ParameterData("yfiles.input.GraphInputMode", "findItems", "tests") to "yfiles.graph.GraphItemTypes",
            ParameterData("yfiles.input.IPortCandidateProvider", "combine", "providers") to "IPortCandidateProvider",
            ParameterData("yfiles.input.IPortCandidateProvider", "fromCandidates", "candidates") to "IPortCandidate",
            ParameterData("yfiles.input.IPortCandidateProvider", "fromShapeGeometry", "ratios") to "Number",
            ParameterData("yfiles.lang.Class", "injectInterfaces", "traits") to OBJECT_TYPE,

            ParameterData("yfiles.lang.delegate", "createDelegate", "functions") to "yfiles.lang.delegate",
            ParameterData("yfiles.lang.delegate", "dynamicInvoke", "args") to OBJECT_TYPE,

            ParameterData("yfiles.lang.Class", "injectInterfaces", "interfaces") to "Interface",

            ParameterData("yfiles.layout.ComponentLayout", "arrangeComponents", "bbox") to "yfiles.algorithms.YRectangle",
            ParameterData("yfiles.layout.ComponentLayout", "arrangeComponents", "boxes") to "yfiles.algorithms.Rectangle2D",
            ParameterData("yfiles.layout.ComponentLayout", "arrangeComponents", "edges") to "yfiles.algorithms.EdgeList",
            ParameterData("yfiles.layout.ComponentLayout", "arrangeComponents", "nodes") to "yfiles.algorithms.NodeList",
            ParameterData("yfiles.layout.ComponentLayout", "arrangeFields", "bbox") to "yfiles.algorithms.YRectangle",
            ParameterData("yfiles.layout.ComponentLayout", "arrangeFields", "boxes") to "yfiles.algorithms.Rectangle2D",
            ParameterData("yfiles.layout.ComponentLayout", "arrangeFields", "edges") to "yfiles.algorithms.EdgeList",
            ParameterData("yfiles.layout.ComponentLayout", "arrangeFields", "nodes") to "yfiles.algorithms.NodeList",

            ParameterData("yfiles.layout.LayoutGraphUtilities", "arrangeRectangleGrid", "rectangles") to "yfiles.algorithms.Rectangle2D",
            ParameterData("yfiles.layout.LayoutGraphUtilities", "arrangeRectangleMultiRows", "rectangles") to "yfiles.algorithms.Rectangle2D",
            ParameterData("yfiles.layout.LayoutGraphUtilities", "arrangeRectangleRows", "rectangles") to "yfiles.algorithms.Rectangle2D",

            ParameterData("yfiles.partial.PartialLayout", "placeSubgraphs", "subgraphComponents") to "yfiles.algorithms.NodeList",
            ParameterData("yfiles.router.BusRepresentations", "replaceHubsBySubgraph", "hubEdgesLists") to "yfiles.algorithms.EdgeList",

            ParameterData("yfiles.router.PathSearch", "calculateCosts", "costs") to "Number",
            ParameterData("yfiles.router.PathSearch", "calculateCosts", "enterIntervals") to "yfiles.router.OrthogonalInterval",
            ParameterData("yfiles.router.PathSearch", "calculateCosts", "lastEdgeCellInfos") to "yfiles.router.EdgeCellInfo",
            ParameterData("yfiles.router.PathSearch", "calculateCosts", "maxAllowedCosts") to "Number",

            ParameterData("yfiles.view.CanvasComponent", "schedule", "args") to OBJECT_TYPE,
            ParameterData("yfiles.view.DashStyle", "constructor", "dashes") to "Number",

            ParameterData("yfiles.view.IAnimation", "createEdgeSegmentAnimation", "endBends") to "yfiles.geometry.IPoint",
            ParameterData("yfiles.view.IAnimation", "createTableAnimation", "columnLayout") to "Number",
            ParameterData("yfiles.view.IAnimation", "createTableAnimation", "rowLayout") to "Number",

            ParameterData("yfiles.view.TableAnimation", "constructor", "columnLayout") to "Number",
            ParameterData("yfiles.view.TableAnimation", "constructor", "rowLayout") to "Number"
    )

    fun getFieldType(field: JField): String? {
        return when {
            field.fqn == "yfiles.tree.RootNodeAlignment" && field.name == "ALL" -> "Array<RootNodeAlignment>"
            else -> null
        }
    }

    fun getPropertyType(property: JProperty): String? {
        if (property.type != "Array") {
            return null
        }

        val className = property.fqn
        val name = property.name
        val generic = when {
            className == "yfiles.algorithms.Graph" && name == "dataProviderKeys" -> OBJECT_TYPE
            className == "yfiles.algorithms.Graph" && name == "registeredEdgeMaps" -> "yfiles.algorithms.IEdgeMap"
            className == "yfiles.algorithms.Graph" && name == "registeredNodeMaps" -> "yfiles.algorithms.INodeMap"
            className == "yfiles.geometry.Matrix" && name == "elements" -> "Number"
            className == "yfiles.graphml.GraphMLAttribute" && name == "singletonContainers" -> CLASS_TYPE
            className == "yfiles.input.GraphInputMode" && name == "clickHitTestOrder" -> "yfiles.graph.GraphItemTypes"
            className == "yfiles.input.GraphInputMode" && name == "doubleClickHitTestOrder" -> "yfiles.graph.GraphItemTypes"
            className == "yfiles.layout.LayoutGraphAdapter" && name == "dataProviderKeys" -> OBJECT_TYPE
            className == "yfiles.view.DragDropItem" && name == "types" -> "String"
            else -> throw GradleException("Unable find array generic for className: '$className' and property: '$name'")
        }

        return "Array<$generic>"
    }

    fun getParameterType(method: JMethodBase, parameter: JParameter): String? {
        val className = method.fqn
        val methodName = when (method) {
            is JConstructor -> "constructor"
            is JMethod -> method.name
            else -> ""
        }
        val parameterName = parameter.getCorrectedName()

        if (className == "yfiles.view.IAnimation" && methodName == "createGraphAnimation" && parameterName == "targetBendLocations") {
            return "yfiles.collections.IMapper<yfiles.graph.IEdge,Array<yfiles.geometry.IPoint>>"
        }

        if (parameter.type != "Array") {
            return null
        }

        val generic = when {
            className in LAYOUT_GRAPH_CLASSES && methodName == "setLabelLayout" && parameterName == "layout" -> {
                when (method.parameters.first().getCorrectedName()) {
                    "node" -> "yfiles.layout.INodeLabelLayout"
                    "edge" -> "yfiles.layout.IEdgeLabelLayout"
                    else -> null // TODO: throw error
                }
            }
            else -> ARRAY_GENERIC_CORRECTION[ParameterData(className, methodName, parameterName)] ?: throw GradleException("Unable find array generic for className: '$className' and method: '$methodName' and parameter '$parameterName'")
        }
        return "Array<$generic>"
    }

    val CLONE_REQUIRED = listOf(
            "yfiles.geometry.Matrix",
            "yfiles.geometry.MutablePoint",
            "yfiles.geometry.MutableSize"
    )

    val INVALID_PLACERS = listOf(
            "yfiles.tree.AssistantNodePlacer",
            "yfiles.tree.BusNodePlacer",
            "yfiles.tree.DelegatingNodePlacer",
            "yfiles.tree.DoubleLineNodePlacer",
            "yfiles.tree.FreeNodePlacer",
            "yfiles.tree.GridNodePlacer",
            "yfiles.tree.LayeredNodePlacer",
            "yfiles.tree.LeftRightNodePlacer",
            "yfiles.tree.SimpleNodePlacer"
    )

    val MULTI_STAGE_LAYOUT_CLASSES = listOf(
            "yfiles.layout.GraphTransformer",
            "yfiles.tree.BalloonLayout",
            "yfiles.genealogy.FamilyTreeLayout",
            "yfiles.hierarchic.HierarchicLayout",
            "yfiles.hierarchic.HierarchicLayoutCore",
            "yfiles.circular.CircularLayout",
            "yfiles.circular.SingleCycleLayout",
            "yfiles.organic.ClassicOrganicLayout",
            "yfiles.organic.OrganicLayout",
            "yfiles.orthogonal.OrthogonalLayout",
            "yfiles.radial.RadialLayout",
            "yfiles.seriesparallel.SeriesParallelLayout",
            "yfiles.tree.AspectRatioTreeLayout",
            "yfiles.tree.ClassicTreeLayout",
            "yfiles.tree.TreeLayout"
    )

    private val CLONE_OVERRIDE = "override fun clone(): $OBJECT_TYPE = definedExternally"

    fun getAdditionalContent(className: String): String {
        return when {
            className == "yfiles.algorithms.YList"
            -> lines("override val isReadOnly: Boolean",
                    "    get() = definedExternally",
                    "override fun add(item: $OBJECT_TYPE) = definedExternally")


            className in CLONE_REQUIRED
            -> CLONE_OVERRIDE

            className == "yfiles.graph.CompositeUndoUnit"
            -> lines("override fun tryMergeUnit(unit: IUndoUnit): Boolean = definedExternally",
                    "override fun tryReplaceUnit(unit: IUndoUnit): Boolean = definedExternally")

            className == "yfiles.graph.EdgePathLabelModel" || className == "yfiles.graph.EdgeSegmentLabelModel"
            -> lines("override fun findBestParameter(label: ILabel, model: ILabelModel, layout: yfiles.geometry.IOrientedRectangle): ILabelModelParameter = definedExternally",
                    "override fun getParameters(label: ILabel, model: ILabelModel): yfiles.collections.IEnumerable<ILabelModelParameter> = definedExternally",
                    "override fun getGeometry(label: ILabel, layoutParameter: ILabelModelParameter): yfiles.geometry.IOrientedRectangle = definedExternally")

            className == "yfiles.graph.FreeLabelModel"
            -> "override fun findBestParameter(label: ILabel, model: ILabelModel, layout: yfiles.geometry.IOrientedRectangle): ILabelModelParameter = definedExternally"

            className == "yfiles.graph.GenericLabelModel"
            -> lines("override fun canConvert(context: yfiles.graphml.IWriteContext, value: $OBJECT_TYPE): Boolean = definedExternally",
                    "override fun getParameters(label: ILabel, model: ILabelModel): yfiles.collections.IEnumerable<ILabelModelParameter> = definedExternally",
                    "override fun convert(context: yfiles.graphml.IWriteContext, value: $OBJECT_TYPE): yfiles.graphml.MarkupExtension = definedExternally",
                    "override fun getGeometry(label: ILabel, layoutParameter: ILabelModelParameter): yfiles.geometry.IOrientedRectangle = definedExternally")

            className == "yfiles.graph.GenericPortLocationModel"
            -> lines("override fun canConvert(context: yfiles.graphml.IWriteContext, value: $OBJECT_TYPE): Boolean = definedExternally",
                    "override fun convert(context: yfiles.graphml.IWriteContext, value: $OBJECT_TYPE): yfiles.graphml.MarkupExtension = definedExternally",
                    "override fun getEnumerator(): yfiles.collections.IEnumerator<IPortLocationModelParameter> = definedExternally")

            className == "yfiles.input.PortRelocationHandleProvider"
            -> "override fun getHandle(context: IInputModeContext, edge: yfiles.graph.IEdge, sourceHandle: Boolean): IHandle = definedExternally"

            className == "yfiles.styles.Arrow"
            -> lines("override val length: Number",
                    "    get() = definedExternally",
                    "override fun getBoundsProvider(edge: yfiles.graph.IEdge, atSource: Boolean, anchor: yfiles.geometry.Point, directionVector: yfiles.geometry.Point): yfiles.view.IBoundsProvider = definedExternally",
                    "override fun getVisualCreator(edge: yfiles.graph.IEdge, atSource: Boolean, anchor: yfiles.geometry.Point, direction: yfiles.geometry.Point): yfiles.view.IVisualCreator = definedExternally",
                    CLONE_OVERRIDE)

            className == "yfiles.styles.GraphOverviewSvgVisualCreator" || className == "yfiles.view.GraphOverviewCanvasVisualCreator"
            -> lines("override fun createVisual(context: yfiles.view.IRenderContext): yfiles.view.Visual = definedExternally",
                    "override fun updateVisual(context: yfiles.view.IRenderContext, oldVisual: yfiles.view.Visual): yfiles.view.Visual = definedExternally")

            className == "yfiles.view.DefaultPortCandidateDescriptor"
            -> lines("override fun createVisual(context: IRenderContext): Visual = definedExternally",
                    "override fun updateVisual(context: IRenderContext, oldVisual: Visual): Visual = definedExternally",
                    "override fun isInBox(context: yfiles.input.IInputModeContext, rectangle: yfiles.geometry.Rect): Boolean = definedExternally",
                    "override fun isVisible(context: ICanvasContext, rectangle: yfiles.geometry.Rect): Boolean = definedExternally",
                    "override fun getBounds(context: ICanvasContext): yfiles.geometry.Rect = definedExternally",
                    "override fun isHit(context: yfiles.input.IInputModeContext, location: yfiles.geometry.Point): Boolean = definedExternally")

            className == "yfiles.styles.VoidPathGeometry"
            -> lines("override fun getPath(): yfiles.geometry.GeneralPath = definedExternally",
                    "override fun getSegmentCount(): Number = definedExternally",
                    "override fun getTangent(ratio: Number): yfiles.geometry.Tangent = definedExternally",
                    "override fun getTangent(segmentIndex: Number, ratio: Number): yfiles.geometry.Tangent = definedExternally")

            else -> ""
        }
    }

    private fun lines(vararg lines: String): String {
        return lines.joinToString("\n")
    }

    private val PARAMETERS_CORRECTION = mapOf(
            ParameterData("yfiles.lang.IComparable", "compareTo", "obj") to "o",
            ParameterData("yfiles.lang.TimeSpan", "compareTo", "obj") to "o",
            ParameterData("yfiles.collections.IEnumerable", "includes", "value") to "item",

            ParameterData("yfiles.algorithms.YList", "elementAt", "i") to "index",
            ParameterData("yfiles.algorithms.YList", "includes", "o") to "item",
            ParameterData("yfiles.algorithms.YList", "indexOf", "obj") to "item",
            ParameterData("yfiles.algorithms.YList", "insert", "element") to "item",
            ParameterData("yfiles.algorithms.YList", "remove", "o") to "item",

            ParameterData("yfiles.graph.DefaultGraph", "setLabelPreferredSize", "size") to "preferredSize",

            ParameterData("yfiles.input.GroupingNodePositionHandler", "cancelDrag", "inputModeContext") to "context",
            ParameterData("yfiles.input.GroupingNodePositionHandler", "dragFinished", "inputModeContext") to "context",
            ParameterData("yfiles.input.GroupingNodePositionHandler", "handleMove", "inputModeContext") to "context",
            ParameterData("yfiles.input.GroupingNodePositionHandler", "initializeDrag", "inputModeContext") to "context",
            ParameterData("yfiles.input.GroupingNodePositionHandler", "setCurrentParent", "inputModeContext") to "context",

            ParameterData("yfiles.layout.CopiedLayoutGraph", "getLabelLayout", "copiedNode") to "node",
            ParameterData("yfiles.layout.CopiedLayoutGraph", "getLabelLayout", "copiedEdge") to "edge",
            ParameterData("yfiles.layout.CopiedLayoutGraph", "getLayout", "copiedNode") to "node",
            ParameterData("yfiles.layout.CopiedLayoutGraph", "getLayout", "copiedEdge") to "edge",

            ParameterData("yfiles.layout.DiscreteEdgeLabelLayoutModel", "createModelParameter", "sourceNode") to "sourceLayout",
            ParameterData("yfiles.layout.DiscreteEdgeLabelLayoutModel", "createModelParameter", "targetNode") to "targetLayout",
            ParameterData("yfiles.layout.DiscreteEdgeLabelLayoutModel", "getLabelCandidates", "label") to "labelLayout",
            ParameterData("yfiles.layout.DiscreteEdgeLabelLayoutModel", "getLabelCandidates", "sourceNode") to "sourceLayout",
            ParameterData("yfiles.layout.DiscreteEdgeLabelLayoutModel", "getLabelCandidates", "targetNode") to "targetLayout",
            ParameterData("yfiles.layout.DiscreteEdgeLabelLayoutModel", "getLabelPlacement", "sourceNode") to "sourceLayout",
            ParameterData("yfiles.layout.DiscreteEdgeLabelLayoutModel", "getLabelPlacement", "targetNode") to "targetLayout",
            ParameterData("yfiles.layout.DiscreteEdgeLabelLayoutModel", "getLabelPlacement", "param") to "parameter",

            ParameterData("yfiles.layout.FreeEdgeLabelLayoutModel", "getLabelPlacement", "sourceNode") to "sourceLayout",
            ParameterData("yfiles.layout.FreeEdgeLabelLayoutModel", "getLabelPlacement", "targetNode") to "targetLayout",
            ParameterData("yfiles.layout.FreeEdgeLabelLayoutModel", "getLabelPlacement", "param") to "parameter",

            ParameterData("yfiles.layout.SliderEdgeLabelLayoutModel", "createModelParameter", "sourceNode") to "sourceLayout",
            ParameterData("yfiles.layout.SliderEdgeLabelLayoutModel", "createModelParameter", "targetNode") to "targetLayout",
            ParameterData("yfiles.layout.SliderEdgeLabelLayoutModel", "getLabelPlacement", "sourceNode") to "sourceLayout",
            ParameterData("yfiles.layout.SliderEdgeLabelLayoutModel", "getLabelPlacement", "targetNode") to "targetLayout",
            ParameterData("yfiles.layout.SliderEdgeLabelLayoutModel", "getLabelPlacement", "para") to "parameter",

            ParameterData("yfiles.layout.INodeLabelLayoutModel", "getLabelPlacement", "param") to "parameter",
            ParameterData("yfiles.layout.FreeNodeLabelLayoutModel", "getLabelPlacement", "param") to "parameter",

            ParameterData("yfiles.tree.NodeOrderComparer", "compare", "edge1") to "x",
            ParameterData("yfiles.tree.NodeOrderComparer", "compare", "edge2") to "y",

            ParameterData("yfiles.seriesparallel.DefaultOutEdgeComparer", "compare", "o1") to "x",
            ParameterData("yfiles.seriesparallel.DefaultOutEdgeComparer", "compare", "o2") to "y",

            ParameterData("yfiles.view.LinearGradient", "accept", "item") to "node",
            ParameterData("yfiles.view.RadialGradient", "accept", "item") to "node",

            ParameterData("yfiles.graphml.GraphMLParseValueSerializerContext", "lookup", "serviceType") to "type",
            ParameterData("yfiles.graphml.GraphMLWriteValueSerializerContext", "lookup", "serviceType") to "type",

            ParameterData("yfiles.layout.LayoutData", "apply", "layoutGraphAdapter") to "adapter",
            ParameterData("yfiles.layout.MultiStageLayout", "applyLayout", "layoutGraph") to "graph",

            ParameterData("yfiles.hierarchic.DefaultLayerSequencer", "sequenceNodeLayers", "glayers") to "layers",
            ParameterData("yfiles.hierarchic.IncrementalHintItemMapping", "provideMapperForContext", "hintsFactory") to "context",
            ParameterData("yfiles.input.ReparentStripeHandler", "reparent", "stripe") to "movedStripe",
            ParameterData("yfiles.input.StripeDropInputMode", "updatePreview", "newLocation") to "dragLocation",
            ParameterData("yfiles.multipage.IElementFactory", "createConnectorNode", "edgesIds") to "edgeIds",
            ParameterData("yfiles.router.DynamicObstacleDecomposition", "init", "partitionBounds") to "bounds",
            ParameterData("yfiles.view.StripeSelection", "isSelected", "stripe") to "item"
    )

    fun fixParameterName(method: JMethodBase, parameterName: String): String? {
        return PARAMETERS_CORRECTION[ParameterData(method.fqn, method.name, parameterName)]
    }

    private data class ParameterData(val className: String, val functionName: String, val parameterName: String)
}

object MixinHacks {
    fun getImplementedTypes(className: String, implementedTypes: List<String>): List<String> {
        return when (className) {
            "yfiles.collections.Map" -> listOf("yfiles.collections.IMap<TKey, TValue>")
            "yfiles.geometry.IRectangle" -> existedItem("yfiles.geometry.IPoint", implementedTypes)
            "yfiles.geometry.IMutableRectangle" -> existedItem("yfiles.geometry.IRectangle", implementedTypes)
            "yfiles.geometry.MutableRectangle" -> existedItem("yfiles.geometry.IMutableRectangle", implementedTypes)
            "yfiles.geometry.IMutableOrientedRectangle" -> existedItem("yfiles.geometry.IOrientedRectangle", implementedTypes)
            "yfiles.geometry.OrientedRectangle" -> existedItem("yfiles.geometry.IMutableOrientedRectangle", implementedTypes)
            else -> implementedTypes
        }
    }

    private fun existedItem(item: String, items: List<String>): List<String> {
        if (items.contains(item)) {
            return listOf(item)
        }

        throw GradleException("Item '$item' not contains in item list '$items'")
    }

    val MUST_BE_ABSTRACT_CLASSES = listOf(
            "yfiles.collections.ICollection",
            "yfiles.collections.IList",
            "yfiles.collections.IMap",
            "yfiles.collections.IListEnumerable",
            "yfiles.collections.IObservableCollection",
            "yfiles.view.ICanvasObjectGroup",
            "yfiles.view.ISelectionModel",
            "yfiles.view.IStripeSelection",
            "yfiles.view.IGraphSelection",

            "yfiles.graph.IColumn",
            "yfiles.graph.IRow"
    )

    fun defineLikeAbstractClass(className: String, functions: List<JMethod>, properties: List<JProperty>): Boolean {
        if (className in MUST_BE_ABSTRACT_CLASSES) {
            return true
        }

        return functions.any { !it.abstract } || properties.any { !it.abstract }
    }
}
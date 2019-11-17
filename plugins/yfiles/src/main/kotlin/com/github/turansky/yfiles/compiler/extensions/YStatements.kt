package com.github.turansky.yfiles.compiler.extensions

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.jsAssignment
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.prototypeOf
import org.jetbrains.kotlin.ir.backend.js.utils.Namer.JS_OBJECT_CREATE_FUNCTION
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name.identifier
import org.jetbrains.kotlin.resolve.DescriptorUtils.getFunctionByName

private val YCLASS_ID = ClassId(LANG_PACKAGE, YCLASS_NAME)
private val FIX_TYPE = identifier("fixType")

private val __PROTO__ = "__proto__"
private val CONSTRUCTOR = "constructor"
private val CALL = "call"

private val SUPER_CONSTRUCTOR_CALL = listOf(
    __PROTO__,
    __PROTO__,
    CONSTRUCTOR,
    CALL
)

private fun nameReference(
    qualifier: JsExpression,
    idents: List<String>
): JsNameRef =
    idents.asSequence()
        .drop(1)
        .fold(JsNameRef(idents.first(), qualifier)) { ref, ident ->
            JsNameRef(ident, ref)
        }

internal fun TranslationContext.fixType(
    descriptor: ClassDescriptor
): JsStatement {
    val yclassCompanion = currentModule.findClassAcrossModuleDependencies(YCLASS_ID)!!
        .companionObjectDescriptor!!

    val fixType = getFunctionByName(
        yclassCompanion.unsubstitutedMemberScope,
        FIX_TYPE
    )

    return JsInvocation(
        toValueReference(fixType),
        toValueReference(descriptor),
        JsStringLiteral(descriptor.name.identifier)
    ).makeStmt()
}

internal fun TranslationContext.baseClass(
    interfaces: List<ClassDescriptor>
): JsExpression {
    val arguments = interfaces
        .map { toValueReference(it) }
        .toTypedArray()

    return JsInvocation(
        findFunction(LANG_PACKAGE, BASE_CLASS_NAME),
        *arguments
    )
}

internal fun baseSuperCall(): JsStatement =
    JsInvocation(
        nameReference(JsThisRef(), SUPER_CONSTRUCTOR_CALL),
        JsThisRef()
    ).makeStmt()

internal fun TranslationContext.setBaseClassPrototype(
    descriptor: ClassDescriptor,
    baseClass: JsExpression
): JsStatement =
    jsAssignment(
        prototypeOf(toValueReference(descriptor)),
        JsInvocation(JS_OBJECT_CREATE_FUNCTION, prototypeOf(baseClass))
    ).makeStmt()
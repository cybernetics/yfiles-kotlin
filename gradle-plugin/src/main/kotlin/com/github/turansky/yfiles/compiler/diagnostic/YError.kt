package com.github.turansky.yfiles.compiler.diagnostic

import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.diagnostics.Severity.ERROR
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import kotlin.reflect.KClass

private fun <T : KtElement> errorDiagnosticFactory(): DiagnosticFactory0<T> =
    DiagnosticFactory0.create(ERROR)

private fun initialize(klass: KClass<*>) {
    Errors.Initializer.initializeFactoryNamesAndDefaultErrorMessages(klass.java, YMessagesExtension)
}

internal object BaseClassErrors {
    val INTERFACE_IMPLEMENTING_NOT_SUPPORTED: DiagnosticFactory0<KtClassOrObject> =
        errorDiagnosticFactory()

    val INTERFACE_MIXING_NOT_SUPPORTED: DiagnosticFactory0<KtClassOrObject> =
        errorDiagnosticFactory()

    val INLINE_CLASS_NOT_SUPPORTED: DiagnosticFactory0<KtClassOrObject> =
        errorDiagnosticFactory()

    init {
        initialize(BaseClassErrors::class)
    }
}

internal object YObjectErrors {
    val INTERFACE_IMPLEMENTING_NOT_SUPPORTED: DiagnosticFactory0<KtClassOrObject> =
        errorDiagnosticFactory()

    val COMPANION_OBJECT_NOT_SUPPORTED: DiagnosticFactory0<KtClassOrObject> =
        errorDiagnosticFactory()

    init {
        initialize(YObjectErrors::class)
    }
}

internal object ClassMetadataErrors {
    val INVALID_TYPE_PARAMETER: DiagnosticFactory0<KtClassOrObject> =
        errorDiagnosticFactory()

    init {
        initialize(ClassMetadataErrors::class)
    }
}

package com.github.turansky.yfiles

private val HAS_CONSTRUCTOR_METHOD = setOf(
    "Matrix",
    "GeneralPath",
    "GridNodePlacer"
)

internal fun Class.toConstructorMethodCode(): String? {
    if (!hasConstructorMethod()) {
        return null
    }

    return """
            |inline fun $name(
            |    block: $name.() -> Unit
            |): $name {
            |    return $name()
            |        .apply(block)
            |}
        """.trimMargin()
}

private fun Class.hasConstructorMethod(): Boolean {
    return when {
        name in HAS_CONSTRUCTOR_METHOD -> true
        !canHaveConstructorMethod() -> false
        primaryConstructor == null -> false
        secondaryConstructors.any { it.public } -> false
        else -> primaryConstructor.isConstructorMethodSource()
    }
}

private fun Class.canHaveConstructorMethod(): Boolean =
    when {
        abstract -> false
        generics.isNotEmpty() -> false
        extendedType() == null && properties.none { it.public && it.getterSetter } -> false
        else -> true
    }

private fun Constructor.isConstructorMethodSource(): Boolean =
    public && isDefault()
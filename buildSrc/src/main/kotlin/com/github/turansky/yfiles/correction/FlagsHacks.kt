package com.github.turansky.yfiles.correction

import java.io.File

internal fun generateFlagsUtils(sourceDir: File) {
    sourceDir.resolve("yfiles/lang/Flags.kt")
        .writeText(
            // language=kotlin
            """
                |@file:Suppress("NOTHING_TO_INLINE")
                |
                |package yfiles.lang
                |
                |external interface Flags<T>
                |        where T : Flags<T>,
                |              T : YEnum<T>
                |
                |inline infix fun <T> T.or(other: T): T
                |        where T : Flags<T>,
                |              T : YEnum<T> {
                |    return unsafeCast<Int>()
                |        .or(other.unsafeCast<Int>())
                |        .unsafeCast<T>()
                |}
            """.trimMargin()
        )
}
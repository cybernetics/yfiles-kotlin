package com.github.turansky.yfiles.correction

import com.github.turansky.yfiles.GeneratorContext
import com.github.turansky.yfiles.JS_ANY
import com.github.turansky.yfiles.json.get

private const val HIERARCHIC_MEMENTOS = "yfiles.hierarchic.Mementos"
private const val TREE_MEMENTOS = "yfiles.tree.Mementos"

private const val LAYER_CONSTRAINTS_MEMENTO = "yfiles.hierarchic.LayerConstraintsMemento"
private const val SEQUENCE_CONSTRAINTS_MEMENTO = "yfiles.hierarchic.SequenceConstraintsMemento"

private const val COMPACT_STRATEGY_MEMENTO = "yfiles.tree.CompactStrategyMemento"

internal fun generateMementoUtils(context: GeneratorContext) {
    // language=kotlin
    context[HIERARCHIC_MEMENTOS] = """
            @JsName("Object")
            external class LayerConstraintsMemento
            private constructor() 
            
            @JsName("Object")
            external class SequenceConstraintsMemento
            private constructor() 
        """.trimIndent()

    // language=kotlin
    context[TREE_MEMENTOS] = """
            @JsName("Object")
            external class CompactStrategyMemento
            private constructor() 
        """.trimIndent()
}

internal fun applyMementoHacks(source: Source) {
    source.type("ILayerConstraintFactory")
        .property("memento")[TYPE] = LAYER_CONSTRAINTS_MEMENTO

    source.type("ISequenceConstraintFactory")
        .property("memento")[TYPE] = SEQUENCE_CONSTRAINTS_MEMENTO

    source.type("HierarchicLayout")
        .get(CONSTANTS).apply {
            sequenceOf(
                "LAYER_CONSTRAINTS_MEMENTO_DP_KEY" to LAYER_CONSTRAINTS_MEMENTO,
                "SEQUENCE_CONSTRAINTS_MEMENTO_DP_KEY" to SEQUENCE_CONSTRAINTS_MEMENTO
            ).forEach { (name, typeParameter) ->
                get(name).also {
                    require(it[TYPE] == graphDpKey(JS_ANY))
                    it[TYPE] = graphDpKey(typeParameter)
                }
            }
        }

    source.type("TreeLayoutData")
        .property("compactNodePlacerStrategyMementos")
        .also { it.replaceInType(",$JS_ANY>", ",$COMPACT_STRATEGY_MEMENTO>") }

    source.type("CompactNodePlacer")
        .constant("STRATEGY_MEMENTO_DP_KEY")
        .also {
            require(it[TYPE] == nodeDpKey(JS_ANY))
            it[TYPE] = nodeDpKey(COMPACT_STRATEGY_MEMENTO)
        }
}

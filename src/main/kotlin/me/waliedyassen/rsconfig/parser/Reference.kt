package me.waliedyassen.rsconfig.parser

import me.waliedyassen.rsconfig.symbol.SymbolType

/**
 * Represents a reference to a configuration symbol that is yet to be resolved.
 */
data class Reference(val type: SymbolType<*>, val span: Span, val name: String) {
    fun get(): Int = error("Reference is not resolved: ${type.literal}, $name")
}
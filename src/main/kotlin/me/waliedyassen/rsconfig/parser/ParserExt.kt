package me.waliedyassen.rsconfig.parser

import me.waliedyassen.rsconfig.symbol.SymbolType

/**
 * Parse a parameter key and value and store them in the specified [params] map.
 */
fun Parser.parseParam(params: MutableMap<Int, Any>) {
    val paramValue = parseReference(SymbolType.Param)
    val paramId = if (paramValue == null) null else compiler.resolveReference(paramValue)
    parseComma()
    if (paramId != null && paramId != -1) {
        val param = compiler.sym.lookupList(SymbolType.Param).lookupById(paramId)!!
        val value = parseDynamic(param.type)
        if (value != null) {
            params[paramId] = value
        }
    } else {
        parseString()
    }
}

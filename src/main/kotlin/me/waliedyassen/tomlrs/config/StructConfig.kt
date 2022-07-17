package me.waliedyassen.tomlrs.config

import com.fasterxml.jackson.databind.JsonNode
import me.waliedyassen.tomlrs.CompilationContext
import me.waliedyassen.tomlrs.binary.BinaryEncoder
import me.waliedyassen.tomlrs.binary.codeParams
import me.waliedyassen.tomlrs.parser.Parser
import me.waliedyassen.tomlrs.parser.Span
import me.waliedyassen.tomlrs.parser.parseParam
import me.waliedyassen.tomlrs.symbol.SymbolType
import me.waliedyassen.tomlrs.util.asValue

/**
 * Implementation for 'struct' type configuration.
 *
 * @author Walied K. Yassen
 */
class StructConfig(name: String) : Config(name, SymbolType.STRUCT) {

    /**
     * The 'params' attribute of the struct type.
     */
    private var params = LinkedHashMap<Int, Any>()

    override fun parseToml(node: JsonNode, context: CompilationContext) {
        node.fields().forEach { (key, value) ->
            val param = context.sym.lookupOrNull(SymbolType.PARAM, key)
            if (param == null) {
                context.reportError(Span.empty(), "Unresolved param reference to '${key}'")
                return@forEach
            }
            params[param.id] = value.asValue(param.content!!, context)
        }
    }

    override fun parseProperty(name: String, parser: Parser) {
        when (name) {
            "param" -> parser.parseParam(params)
            else -> parser.unknownProperty()
        }
    }

    override fun verifyProperties(parser: Parser) {
        // Do nothing.
    }

    override fun encode(): ByteArray {
        val packet = BinaryEncoder(32)
        packet.codeParams(params)
        packet.terminateCode()
        return packet.toByteArray()
    }
}
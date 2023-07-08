package me.waliedyassen.rscp.symbol

import me.waliedyassen.rscp.format.dbtable.DbColumnProp

/**
 * The separator used to separate the symbol file fields.
 */
private const val FIELD_SEPARATOR = "!"

/**
 * Handles serialization operations for a symbol of type [T].
 */
abstract class SymbolSerializer<T : Symbol> {

    /**
     * Deserializes a [T] object from the given [String].
     */
    abstract fun deserialize(line: String): T

    /**
     * Serializes the given [T] object to a [String]
     */
    abstract fun serialize(symbol: T): String
}

/**
 * A [SymbolSerializer] implementation for [BasicSymbol] type.
 */
object BasicSymbolSerializer : SymbolSerializer<BasicSymbol>() {

    override fun deserialize(line: String): BasicSymbol {
        val parts = line.split(FIELD_SEPARATOR)
        val name = parts[0]
        val id = parts[1].toInt()
        return BasicSymbol(name, id)
    }

    override fun serialize(symbol: BasicSymbol) = "${symbol.name}$FIELD_SEPARATOR${symbol.id}"
}

/**
 * A [SymbolSerializer] implementation for [TypedSymbol] type.
 */
object TypedSymbolSerializer : SymbolSerializer<TypedSymbol>() {

    override fun deserialize(line: String): TypedSymbol {
        val parts = line.split(FIELD_SEPARATOR)
        val name = parts[0]
        val id = parts[1].toInt()
        val type = SymbolType.lookup(parts[2])
        return TypedSymbol(name, id, type)
    }

    override fun serialize(symbol: TypedSymbol) = "${symbol.name}$FIELD_SEPARATOR${symbol.id}$FIELD_SEPARATOR${symbol.type.literal}"
}

/**
 * A [SymbolSerializer] implementation for [TypedSymbol] type.
 */
object ConfigSymbolSerializer : SymbolSerializer<ConfigSymbol>() {

    override fun deserialize(line: String): ConfigSymbol {
        val parts = line.split(FIELD_SEPARATOR, limit = 4)
        val name = parts[0]
        val id = parts[1].toInt()
        val type = SymbolType.lookup(parts[2])
        val transmit = parts[3].toBooleanStrict()
        return ConfigSymbol(name, id, type, transmit)
    }

    override fun serialize(symbol: ConfigSymbol) =
        "${symbol.name}$FIELD_SEPARATOR${symbol.id}$FIELD_SEPARATOR${symbol.type.literal}$FIELD_SEPARATOR${symbol.transmit}"
}

/**
 * A [SymbolSerializer] implementation for [ConstantSymbol] type.
 */
object ConstantSymbolSerializer : SymbolSerializer<ConstantSymbol>() {

    override fun deserialize(line: String): ConstantSymbol {
        val parts = line.split(FIELD_SEPARATOR, limit = 2)
        val name = parts[0]
        val value = parts[1]
        return ConstantSymbol(name, value)
    }

    override fun serialize(symbol: ConstantSymbol) = "${symbol.name}$FIELD_SEPARATOR${symbol.value}"
}

/**
 * A [SymbolSerializer] implementation for [ClientScriptSymbol] type.
 */
object ClientScriptSymbolSerializer : SymbolSerializer<ClientScriptSymbol>() {

    override fun deserialize(line: String): ClientScriptSymbol {
        val parts = line.split(FIELD_SEPARATOR, limit = 3)
        val name = parts[0]
        val id = parts[1].toInt()
        val arguments =
            if (parts[2].isBlank()) emptyList() else parts[2].split(",").map { SymbolType.lookup(it) }.toList()
        return ClientScriptSymbol(name, id, arguments)
    }

    override fun serialize(symbol: ClientScriptSymbol) =
        "${symbol.name}$FIELD_SEPARATOR${symbol.id}$FIELD_SEPARATOR${symbol.arguments.joinToString(",") { it.literal }}"
}

/**
 * A [SymbolSerializer] implementation for [DbColumnSymbol] type.
 */
object DbColumnSymbolSerializer : SymbolSerializer<DbColumnSymbol>() {

    override fun deserialize(line: String): DbColumnSymbol {
        val parts = line.split(FIELD_SEPARATOR)
        val name = parts[0]
        val id = parts[1].toInt()
        val types = if (parts[2].isBlank()) {
            emptyList()
        } else
            parts[2].splitToSequence(",").map { SymbolType.lookup(it) }.toList()
        val props = if (parts[3].isBlank()) {
            emptySet()
        } else {
            parts[3].splitToSequence(",").map { literal ->
                enumValues<DbColumnProp>().find { it.literal == literal }
                    ?: error("Could not find property for literal '$literal'")
            }.toSet()
        }
        return DbColumnSymbol(name, id, types, props)
    }

    override fun serialize(symbol: DbColumnSymbol): String {
        val types = symbol.types.joinToString(",") { it.literal }
        val props = symbol.props.joinToString(",") { it.literal }
        return "${symbol.name}$FIELD_SEPARATOR${symbol.id}$FIELD_SEPARATOR$types$FIELD_SEPARATOR$props"
    }
}
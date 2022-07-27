package me.waliedyassen.rscp

import com.github.michaelbull.logging.InlineLogger
import me.waliedyassen.rscp.config.Config
import me.waliedyassen.rscp.config.value.Constant
import me.waliedyassen.rscp.parser.Diagnostic
import me.waliedyassen.rscp.parser.DiagnosticKind
import me.waliedyassen.rscp.parser.Parser
import me.waliedyassen.rscp.parser.Reference
import me.waliedyassen.rscp.parser.SemanticInfo
import me.waliedyassen.rscp.parser.Span
import me.waliedyassen.rscp.symbol.Symbol
import me.waliedyassen.rscp.symbol.SymbolContributor
import me.waliedyassen.rscp.symbol.SymbolList
import me.waliedyassen.rscp.symbol.SymbolTable
import me.waliedyassen.rscp.symbol.SymbolType
import java.io.File

class Compiler(private val extractMode: ExtractMode) {

    /**
     * The symbol table of the compiler.
     */
    val sym = SymbolTable()

    /**
     * A list of all the tracked semantic information so far.
     */
    val semanticInfo = mutableListOf<SemanticInfo>()

    /**
     * A list of all the diagnostics generated by the compiler so far.
     */
    val diagnostics = mutableListOf<Diagnostic>()

    /**
     * Read all the symbols from the specified [directory] and store them in the symbol table.
     */
    fun readSymbols(directory: File) {
        check(directory.exists()) { "The specified symbols directory does not exist" }
        directory.listFiles()?.forEach { file ->
            val result = SYMBOL_FILE_REGEX.matchEntire(file.name) ?: return@forEach
            val literal = result.groupValues[1]
            val type = SymbolType.lookup(literal)
            sym.read(type, file)
            logger.info { "Parsed a total of ${sym.lookupList(type).symbols.size} '$literal' symbol entries" }
        }
    }

    /**
     * Write all the symbols in the symbol table to the specified [directory].
     */
    fun writeSymbols(directory: File) {
        for (type in SymbolType.values) {
            val list = sym.lookupList(type)
            if (list.modified) {
                logger.info { "Writing symbol table changes for '${type.literal}'" }
                sym.write(type, directory.resolve("${type.literal}.sym"))
            }
        }
    }

    /**
     * Generate the symbol table information for the specified list of [Config].
     */
    fun generateSymbols(contribs: List<SymbolContributor>) {
        contribs.forEach { contrib ->
            val type = contrib.symbolType
            val name = contrib.name
            val old = sym.lookupSymbol(type, name)
            val id = old?.id ?: sym.generateId(type)
            val new = contrib.createSymbol(id)
            if (old != new) {
                @Suppress("UNCHECKED_CAST")
                val list = sym.lookupList(type) as SymbolList<Symbol>
                if (old != null) {
                    list.remove(old)
                }
                list.add(new)
            }
        }
    }

    /**
     * Compile all the configs within the specified [directory].
     */
    fun compileDirectory(directory: File): List<SymbolContributor> {
        logger.info { "Compiling configs from directory $directory" }
        val parsers = directory.walkTopDown().map { createParser(it) }.filterNotNull()
        val (constantParsers, configParsers) = parsers.partition { it.type == SymbolType.Constant }
        val configs = mutableListOf<Config>()
        val constants = mutableListOf<Constant>()
        // TODO(Walied): Right now constants are evaluated at parse time, so we need to parse and register all
        // of the constants before we parse configs.
        for (parser in constantParsers) {
            constants += parser.parseConstants()
            if (extractMode == ExtractMode.SemInfo) {
                semanticInfo += parser.semInfo
            }
        }
        generateSymbols(constants)
        for (parser in configParsers) {
            configs += parser.parseConfigs()
            if (extractMode == ExtractMode.SemInfo) {
                semanticInfo += parser.semInfo
            }
        }
        generateSymbols(configs)
        configs.forEach { it.resolveReferences(this) }
        return configs + constants
    }

    /**
     * Compile the specified config [file].
     */
    fun compileFile(file: File): List<SymbolContributor> {
        val parser = createParser(file) ?: return emptyList()
        return runParser(parser)
    }

    /**
     * Run the parse operation of the specified [Parser].
     */
    private fun runParser(parser: Parser): List<SymbolContributor> {
        val units = if (parser.type == SymbolType.Constant) parser.parseConstants() else parser.parseConfigs()
        if (extractMode == ExtractMode.SemInfo) {
            semanticInfo += parser.semInfo
        }
        generateSymbols(units)
        if (parser.type != SymbolType.Constant) {
            units.forEach { (it as Config).resolveReferences(this) }
        }
        return units
    }

    /**
     * Attempt to create a [Parser] object for the specified [file].
     */
    private fun createParser(file: File): Parser? {
        val extension = file.extension
        val type = SymbolType.lookupOrNull(extension) ?: return null
        val input = file.reader().use { it.readText() }
        return Parser(type, this, input, extractMode == ExtractMode.SemInfo)
    }

    /**
     * Generate byte code for the specified list of [configs] then write the generated byte code
     * to the disk.
     */
    fun generateCode(configs: List<Config>, directory: File) {
        if (configs.isEmpty()) return
        check(directory.exists() || directory.mkdirs()) { "Failed to create the output directory '${directory}'" }
        logger.info { "Writing ${configs.size} configs to $directory" }
        configs.forEach { config ->
            val type = config.symbolType
            val typeDirectory = directory.resolve(type.literal)
            check(typeDirectory.exists() || typeDirectory.mkdirs()) { "Failed to create the output directory '$typeDirectory'" }
            val file = typeDirectory.resolve("${sym.lookupSymbol(type, config.name)!!.id}")
            file.writeBytes(config.encode())
        }
    }

    /**
     * Add an error diagnostic to the generated diagnostic list.
     */
    fun addError(span: Span, message: String) {
        addDiagnostic(DiagnosticKind.Error, span, message)
    }

    /**
     * Add a diagnostic to the generated diagnostics list.
     */
    private fun addDiagnostic(kind: DiagnosticKind, span: Span, message: String) {
        diagnostics += Diagnostic(kind, span, message)
    }


    /**
     * Attempt to resolve the specified [reference] from the symbol table and return
     * the symbol id if the resolve was successful otherwise -1.
     */
    fun resolveReference(reference: Reference?, permitNulls: Boolean = true): Int {
        if (reference == null) {
            return -1
        }
        val symbol = sym.lookupSymbol(reference.type, reference.name)
        if (reference.name == "null") {
            if (!permitNulls) {
                addError(reference.span, "Null values are not permitted in here")
            }
            return -1
        }
        if (symbol == null) {
            val message = "Unresolved reference to '${reference.name}' of type '${reference.type.literal}'"
            addError(reference.span, message)
            return -1
        }
        return symbol.id

    }

    companion object {
        private val logger = InlineLogger()
        private val SYMBOL_FILE_REGEX = Regex("(\\w+)\\.sym")
    }
}
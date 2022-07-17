package me.waliedyassen.rsconfig

import com.github.michaelbull.logging.InlineLogger
import me.waliedyassen.rsconfig.config.Config
import me.waliedyassen.rsconfig.parser.Diagnostic
import me.waliedyassen.rsconfig.parser.DiagnosticKind
import me.waliedyassen.rsconfig.parser.Parser
import me.waliedyassen.rsconfig.parser.Reference
import me.waliedyassen.rsconfig.parser.SemanticInfo
import me.waliedyassen.rsconfig.parser.Span
import me.waliedyassen.rsconfig.symbol.Symbol
import me.waliedyassen.rsconfig.symbol.SymbolList
import me.waliedyassen.rsconfig.symbol.SymbolTable
import me.waliedyassen.rsconfig.symbol.SymbolType
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
    fun generateSymbols(configs: List<Config>) {
        configs.forEach { config ->
            val type = config.symbolType
            val name = config.name
            val old = sym.lookupSymbol(type, name)
            val id = old?.id ?: sym.generateId(type)
            val new = config.createSymbol(id)
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
    fun compileDirectory(directory: File): List<Config> {
        logger.info { "Compiling configs from directory $directory" }
        return directory.walkTopDown().flatMap { compileFile(it) }.toList()
    }

    /**
     * Compile the specified config [file].
     */
    fun compileFile(file: File): List<Config> {
        val parser = createParser(file) ?: return emptyList()
        return runParser(parser)
    }

    /**
     * Run the parse operation of the specified [Parser].
     */
    private fun runParser(parser: Parser): List<Config> {
        val configs = parser.parseConfigs()
        if (extractMode == ExtractMode.SemInfo) {
            semanticInfo += parser.semInfo
        }
        generateSymbols(configs)
        configs.forEach { it.resolveReferences(this) }
        return configs
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
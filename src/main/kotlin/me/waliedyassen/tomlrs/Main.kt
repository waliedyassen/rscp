package me.waliedyassen.tomlrs

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.michaelbull.logging.InlineLogger
import me.waliedyassen.tomlrs.symbol.SymbolTable
import me.waliedyassen.tomlrs.symbol.SymbolType
import java.io.File
import kotlin.system.measureTimeMillis

data class ParsingConfig(val name: String, val type: SymbolType, val node: JsonNode)

class CompilationContext(val sym: SymbolTable) {
    val errors = mutableListOf<String>()

    fun reportError(message: String) {
        errors += message
    }
}

object PackTool : CliktCommand() {

    private val logger = InlineLogger()

    private val symbolDirectory by option(help = "The symbol directory which contains the symbol table files")
        .file()
        .default(File("symbols"))

    private val inputDirectory by option(help = "The input directory which contains all the source files")
        .file()
        .default(File("input"))

    private val outputDirectory by option(help = "The output directory which the binary files will be written to")
        .file()
        .default(File("output"))


    override fun run() {
        val time = measureTimeMillis {
            val table = readSymbolTable()
            val context = CompilationContext(table)
            logger.info { "Parsing configs from $inputDirectory" }
            val parsingConfigs = parseConfigs()
            generateConfigId(parsingConfigs, table)
            val configs = parsingConfigs.map {
                val config = it.type.supplier()
                config.parseToml(it.node, context)
                it.name to config
            }
            if (context.errors.isNotEmpty()) {
                context.errors.forEach { logger.info { it } }
                return@measureTimeMillis
            }
            if (configs.isNotEmpty()) {
                check(outputDirectory.exists() || outputDirectory.mkdirs()) { "Failed to create the output directory '$outputDirectory'" }
                logger.info { "Writing ${configs.size} configs to $outputDirectory" }
                configs.forEach {
                    val name = it.first
                    val config = it.second
                    val type = config.symbolType
                    val directory = outputDirectory.resolve(type.literal)
                    check(directory.exists() || directory.mkdirs()) { "Failed to create the output directory '$directory'" }
                    val file = directory.resolve("${table.lookupOrNull(type, name)!!.id}")
                    file.writeBytes(config.encode())
                }
            }
            writeSymbolTable(table)
        }
        logger.info { "Finished. Took $time ms" }
    }

    private fun generateConfigId(parsingConfigs: List<ParsingConfig>, table: SymbolTable) {
        parsingConfigs.forEach { config ->
            val type = config.type
            val name = config.name
            if (table.lookupOrNull(type, name) == null) {
                table.insert(type, name, table.generateUniqueId(type))
            }
        }
    }

    private fun parseConfigs(): List<ParsingConfig> {
        val regex = Regex("(?:.+\\.)?([^.]+)\\.toml")
        val mapper = createMapper()
        val configs = mutableListOf<ParsingConfig>()
        inputDirectory.walkTopDown().forEach { file ->
            val result = regex.matchEntire(file.name) ?: return@forEach
            val literal = result.groupValues[1]
            val type = SymbolType.lookup(literal)
            val tree: JsonNode
            file.reader().use { tree = mapper.readTree(it) }
            tree.fields().asSequence().forEach {
                configs += ParsingConfig(it.key, type, it.value)
            }
        }
        return configs
    }

    private fun readSymbolTable(): SymbolTable {
        check(symbolDirectory.exists()) { "The specified symbols directory does not exist" }
        val table = SymbolTable()
        val regex = Regex("(\\w+)\\.sym")
        symbolDirectory.listFiles()?.forEach { file ->
            val result = regex.matchEntire(file.name) ?: return@forEach
            val literal = result.groupValues[1]
            val type = SymbolType.lookup(literal)
            table.read(type, file)
            logger.info { "Parsed a total of ${table.lookup(type).symbols.size} '$literal' symbol entries" }
        }
        return table
    }

    private fun writeSymbolTable(table: SymbolTable) {
        for (type in SymbolType.values()) {
            val list = table.lookupOrNull(type) ?: continue
            if (list.modified) {
                logger.info { "Writing symbol table changes for '${type.literal}'" }
                table.write(type, symbolDirectory.resolve("${type.literal}.sym"))
            }
        }
    }

    private fun createMapper(): TomlMapper {
        val mapper = TomlMapper()
        mapper.registerModule(kotlinModule {
            configure(KotlinFeature.NullToEmptyCollection, false)
            configure(KotlinFeature.NullToEmptyMap, false)
            configure(KotlinFeature.NullIsSameAsDefault, false)
            configure(KotlinFeature.SingletonSupport, false)
            configure(KotlinFeature.StrictNullChecks, false)
        })
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
        mapper.configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, false)
        return mapper
    }
}

fun main(args: Array<String>) {
    PackTool.main(args)
}
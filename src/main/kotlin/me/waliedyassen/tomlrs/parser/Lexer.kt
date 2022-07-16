package me.waliedyassen.tomlrs.parser

import me.waliedyassen.tomlrs.CompilationContext

/**
 * Returns `true` if [this] character is an ascii letter.
 */
private fun Char.isAsciiLetter() = this in 'A'..'Z' || this in 'a'..'z'

/**
 * Returns `true` if [this] character is an ascii digit.
 */
private fun Char.isAsciiDigit() = this in '0'..'9'

/**
 * Returns `true` if [this] character is either an ascii digit or an ascii letter.
 */
private fun Char.isAsciiLetterOrDigit() = isAsciiLetter() || isAsciiDigit()

/**
 * Returns `true` if [this] character is suitable to be part of an identifier.
 */
private fun Char.isIdentifierPart() = isAsciiLetterOrDigit() || this == '_' || this == '+'

/**
 * Returns `true` if [this] character is a line feed character.
 */
private fun Char.isLineDelimiter() = this == '\r' || this == '\n'

/**
 * Returns `true` if [this] character is a string quote.
 */
private fun Char.isQuote() = this == '\"'

/**
 * The lowest level of parsing, it takes an array of characters as input and transforms them into
 * meaningful [Token] objects while validating using specific rules.
 */
class Lexer(private val input: CharArray, private val context: CompilationContext) {

    /**
     * The current index of source code in the buffer.
     */
    private var index = 0

    /**
     * Returns `true` if a [Token.LBracket] can be parsed next.
     */
    fun isLBracket() = peek() == '['

    /**
     * Attempts to parse a [Token.LBracket] and returns [Token.Dummy] if it fails.
     */
    fun lexLBracket(): Token {
        if (!isLBracket()) {
            reportError(Span(index, index), "Expected a '[' but received '${peek()}'")
            return Token.Dummy(Span(index, index))
        }
        val start = index
        advance()
        return Token.LBracket(Span(start, index))
    }

    /**
     * Returns `true` if a [Token.RBracket] can be parsed next.
     */
    private fun isRBracket() = peek() == ']'

    /**
     * Attempts to parse a [Token.RBracket] and returns [Token.Dummy] if it fails.
     */
    fun lexRBracket(): Token {
        if (!isRBracket()) {
            reportError(Span(index, index), "Expected a ']' but received '${peek()}'")
            return Token.Dummy(Span(index, index))
        }
        val start = index
        advance()
        return Token.RBracket(Span(start, index))
    }


    /**
     * Returns `true` if a [Token.Equals] can be parsed next.
     */
    private fun isEquals() = peek() == '='

    /**
     * Attempts to parse a [Token.Equals] and returns [Token.Dummy] if it fails.
     */
    fun lexEquals(): Token {
        if (!isEquals()) {
            reportError(Span(index, index), "Expected a '=' but received '${peek()}'")
            return Token.Dummy(Span(index, index))
        }
        val start = index
        advance()
        return Token.Equals(Span(start, index))
    }

    /**
     * Returns `true` if a [Token.Comma] can be parsed next.
     */
    private fun isComma() = peek() == ','

    /**
     * Attempts to parse a [Token.Comma] and returns [Token.Dummy] if it fails.
     */
    fun lexComma(): Token {
        if (!isComma()) {
            reportError(Span(index, index), "Expected a ',' but received '${peek()}'")
            return Token.Dummy(Span(index, index))
        }
        val start = index
        advance()
        return Token.Comma(Span(start, index))
    }

    /**
     * Attempts to parse a [Token.Identifier] and returns [Token.Dummy] if it fails.
     */
    fun lexIdentifier(): Token {
        if (isEof()) {
            reportError(Span(index, index), "Expected an identifier but reached end of file")
            return Token.Dummy(Span(index, index))
        }
        if (!peek().isIdentifierPart()) {
            reportError(Span(index, index), "Expecting an identifier but received '${peek()}'")
            return Token.Dummy(Span(index, index))
        }
        val builder = StringBuilder()
        val start = index
        while (peek().isIdentifierPart()) {
            builder.append(peek())
            advance()
        }
        return Token.Identifier(Span(start, index), builder.toString())
    }

    /**
     * Attempts to parse unquoted string, the lexer will continue appending to the string until it meets
     * the end of the line or the end of the file,
     *
     * A [Token.Text] will be returned if the parsing is successful otherwise a [Token.Dummy] will be returned.
     */
    fun lexLine(): Token {
        if (isEof()) {
            reportError(Span(index, index), "Expected a string but reached end of file")
            return Token.Dummy(Span(index, index))
        }
        val builder = StringBuilder()
        val start = index
        while (!isEof() && !peek().isLineDelimiter()) {
            builder.append(peek())
            advance()
        }
        skipLine()
        return Token.Text(Span(start, index), builder.toString())
    }

    /**
     * Returns `true` if a quoted string can be lexxed next.
     */
    fun isQuotedString() = peek().isQuote()

    /**
     * Attempts to parse a quoted string, a quote is expected at the beginning and ending of the string.
     *
     * A [Token.Text] will be returned if the parsing is successful otherwise a [Token.Dummy] will be returned.
     */
    fun lexQuotedString(): Token {
        if (isEof()) {
            reportError(Span(index, index), "Expected a a quote but reached end of file")
            return Token.Dummy(Span(index, index))
        }
        if (!peek().isQuote()) {
            reportError(Span(index, index), "Expected a quote but received '${peek()}'")
            return Token.Dummy(Span(index, index))
        }
        advance()
        val builder = StringBuilder()
        val start = index
        while (true) {
            if (isEof() || peek().isLineDelimiter() || peek().isQuote()) {
                break
            }
            builder.append(peek())
            advance()
        }
        if (!peek().isQuote()) {
            val span = Span(start, index)
            reportError(span, "String is not closed properly")
            return Token.Dummy(span)
        }
        advance()
        return Token.Text(Span(start, index), builder.toString())
    }

    /**
     * Attempts to parse a 32-bit signed number.
     *
     * A [Token.Number] will be returned if the parsing is successful otherwise a [Token.Dummy] will be returned.
     */
    fun lexInteger(): Token {
        if (isEof()) {
            reportError(Span(index, index), "Expected a digit but reached end of file")
            return Token.Dummy(Span(index, index))
        }
        val builder = StringBuilder()
        val start = index
        if (peek() == '+' || peek() == '-') {
            builder.append(peek())
            advance()
        }
        if (!peek().isAsciiDigit()) {
            reportError(Span(index, index), "Expected a digit but received '${peek()}'")
            return Token.Dummy(Span(index, index))
        }
        while (peek().isAsciiDigit()) {
            builder.append(peek())
            advance()
        }
        val integer = builder.toString().toIntOrNull()
        if (integer == null) {
            reportError(Span(index, index), "Could not convert '${builder}' to a valid 32-bit number")
            return Token.Dummy(Span(index, index))
        }
        return Token.Number(Span(start, index), integer)

    }

    /**
     * Skip all the whitespace characters which are: line delimiters, tab characters and space characters.
     */
    fun skipWhitespace() = skipWhile { it.isLineDelimiter() || it == '\t' || it == ' ' }

    /**
     * Skip the current line and all the following line delimiter characters.
     */
    fun skipLine() {
        skipWhile { !isEof() && !it.isLineDelimiter() }
        skipWhile { it.isLineDelimiter() }
    }

    /**
     * Skip the next sequential characters that match the specified [predicate].
     */
    private fun skipWhile(predicate: (Char) -> (Boolean)): Boolean {
        var skipped = 0
        while (peek() != 0.toChar() && predicate(peek())) {
            skipped++
            advance()
        }
        return skipped > 0
    }

    /**
     * Returns `true` if the next character is an end-of-file character.
     */
    fun isEof() = peek() == 0.toChar()

    /**
     * Returns the current character in the source code without advancing.
     */
    private fun peek(): Char {
        return if (index == input.size) 0.toChar() else input[index]
    }

    /**
     * Advance the character position in the source code by one.
     */
    private fun advance() {
        if (index == input.size) {
            return
        }
        index++
    }

    /**
     * Report an error to the compilation context.
     */
    private fun reportError(span: Span, text: String) {
        context.reportError(text)
    }

    /**
     * Creates a span with the range of [begin] and [end]. The default values will point at the current character
     * in the source buffer.
     */
    private fun span(begin: Int = index, end: Int = index) = Span(begin, end)
}
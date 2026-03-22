package app.remodex.android

import java.io.File
import java.util.Locale

data class RemodexCommandSummary(
    val summary: String,
    val dedupeKey: String,
)

object RemodexCommandSummaryParser {
    fun summarize(rawCommand: String): RemodexCommandSummary? {
        val normalized = normalizeCommand(rawCommand) ?: return null
        if (containsUnsafeShellConstruct(normalized)) {
            return null
        }

        val tokens = tokenizeShell(normalized) ?: return null
        if (tokens.isEmpty()) {
            return null
        }

        val segments = splitPipeSegments(tokens) ?: return null
        val first = segments.firstOrNull() ?: return null
        val summary = parseReadSummary(segments)
            ?: parseSearchSummary(segments)
            ?: parseListedSummary(segments)
            ?: parseCreatedSummary(first)
            ?: parseDeletedSummary(first)
            ?: parseRenamedSummary(first)
            ?: return null

        return RemodexCommandSummary(
            summary = summary,
            dedupeKey = dedupeKey(normalized),
        )
    }

    private fun normalizeCommand(rawCommand: String): String? {
        val compact = rawCommand.trim().replace(Regex("\\s+"), " ")
        if (compact.isEmpty()) {
            return null
        }
        return unwrapShellCommandIfPresent(compact).trim().ifEmpty { null }
    }

    private fun dedupeKey(command: String): String {
        return command.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.US)
    }

    private fun containsUnsafeShellConstruct(command: String): Boolean {
        val unsafeSubstrings = listOf("&&", "||", ";", "$(", "`", ">", "<")
        return unsafeSubstrings.any(command::contains)
    }

    private fun tokenizeShell(command: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }

        var index = 0
        while (index < command.length) {
            val char = command[index]
            if (escaping) {
                current.append(char)
                escaping = false
                index += 1
                continue
            }

            if (quote != null) {
                when {
                    char == quote -> quote = null
                    char == '\\' && quote == '"' -> escaping = true
                    else -> current.append(char)
                }
                index += 1
                continue
            }

            when {
                char.isWhitespace() -> {
                    flush()
                    index += 1
                }

                char == '\'' || char == '"' -> {
                    quote = char
                    index += 1
                }

                char == '\\' -> {
                    escaping = true
                    index += 1
                }

                char == '|' -> {
                    flush()
                    if (command.getOrNull(index + 1) == '|') {
                        return null
                    }
                    tokens += "|"
                    index += 1
                }

                char == '&' || char == ';' || char == '>' || char == '<' -> {
                    return null
                }

                else -> {
                    current.append(char)
                    index += 1
                }
            }
        }

        if (escaping || quote != null) {
            return null
        }

        flush()
        return tokens
    }

    private fun splitPipeSegments(tokens: List<String>): List<List<String>>? {
        val segments = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        for (token in tokens) {
            if (token == "|") {
                if (current.isEmpty()) {
                    return null
                }
                segments += current.toList()
                current.clear()
            } else {
                current += token
            }
        }
        if (current.isEmpty()) {
            return null
        }
        segments += current.toList()
        return segments
    }

    private fun parseReadSummary(segments: List<List<String>>): String? {
        val first = segments.firstOrNull() ?: return null
        val readTarget = readTarget(first) ?: return null
        if (!segments.drop(1).all(::isSafeReadFilterSegment)) {
            return null
        }
        return "Read ${displayPath(readTarget)}"
    }

    private fun parseSearchSummary(segments: List<List<String>>): String? {
        val first = segments.firstOrNull() ?: return null
        val parsed = parseSearchSegment(first) ?: return null
        if (!segments.drop(1).all(::isSafeReadFilterSegment)) {
            return null
        }
        return buildString {
            append("Searched for ")
            append(parsed.pattern)
            parsed.target?.let {
                append(" in ")
                append(displayPath(it))
            }
        }
    }

    private fun parseListedSummary(segments: List<List<String>>): String? {
        val first = segments.firstOrNull() ?: return null
        val target = when (first.firstOrNull()?.lowercase(Locale.US)) {
            "ls", "tree" -> singleTrailingOperand(
                args = first.drop(1),
                valueFlags = setOf("-L", "-I", "--filelimit"),
            ) ?: "."

            "rg" -> if (first.drop(1).firstOrNull() == "--files") {
                singleTrailingOperand(first.drop(2)) ?: "."
            } else {
                null
            }

            else -> null
        } ?: return null

        if (!segments.drop(1).all(::isSafeReadFilterSegment)) {
            return null
        }
        return "Listed ${displayPath(target)}"
    }

    private fun parseCreatedSummary(segment: List<String>): String? {
        if (segment.isEmpty()) {
            return null
        }
        return when (segment.first().lowercase(Locale.US)) {
            "touch", "mkdir" -> singleTrailingOperand(segment.drop(1))
                ?.let { "Created ${displayPath(it)}" }

            else -> null
        }
    }

    private fun parseDeletedSummary(segment: List<String>): String? {
        if (segment.isEmpty()) {
            return null
        }
        return when (segment.first().lowercase(Locale.US)) {
            "rm", "rmdir" -> singleTrailingOperand(segment.drop(1))
                ?.let { "Deleted ${displayPath(it)}" }

            else -> null
        }
    }

    private fun parseRenamedSummary(segment: List<String>): String? {
        if (segment.firstOrNull()?.lowercase(Locale.US) != "mv") {
            return null
        }
        val operands = positionalOperands(segment.drop(1))
        if (operands.size != 2) {
            return null
        }
        return "Renamed ${displayPath(operands[0])} -> ${displayPath(operands[1])}"
    }

    private fun readTarget(segment: List<String>): String? {
        if (segment.isEmpty()) {
            return null
        }
        return when (segment.first().lowercase(Locale.US)) {
            "cat" -> singleTrailingOperand(segment.drop(1))
            "head", "tail" -> singleTrailingOperand(
                args = segment.drop(1),
                valueFlags = setOf("-n", "-c"),
            )

            "nl" -> singleTrailingOperand(segment.drop(1))
            "sed" -> sedFileOperand(segment.drop(1))
            else -> null
        }
    }

    private fun isSafeReadFilterSegment(segment: List<String>): Boolean {
        if (segment.isEmpty()) {
            return false
        }
        return when (segment.first().lowercase(Locale.US)) {
            "head", "tail" -> positionalOperands(
                args = segment.drop(1),
                valueFlags = setOf("-n", "-c"),
            ).isEmpty()

            "sed" -> positionalOperands(segment.drop(1)).size <= 1
            else -> false
        }
    }

    private data class SearchSegment(
        val pattern: String,
        val target: String?,
    )

    private fun parseSearchSegment(segment: List<String>): SearchSegment? {
        if (segment.isEmpty()) {
            return null
        }
        val command = segment.first().lowercase(Locale.US)
        if (command != "rg" && command != "grep") {
            return null
        }
        if (command == "rg" && segment.drop(1).firstOrNull() == "--files") {
            return null
        }

        val positionals = positionalOperands(
            args = segment.drop(1),
            valueFlags = setOf("-e", "--regexp", "-g", "--glob", "-m", "--max-count", "-A", "-B", "-C", "-t"),
        )
        val pattern = positionals.firstOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val target = positionals.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
        return SearchSegment(pattern = pattern, target = target)
    }

    private fun singleTrailingOperand(
        args: List<String>,
        valueFlags: Set<String> = emptySet(),
    ): String? {
        return positionalOperands(args, valueFlags).singleOrNull()
    }

    private fun sedFileOperand(args: List<String>): String? {
        if ("-n" !in args) {
            return null
        }
        val operands = positionalOperands(args)
        return if (operands.size >= 2) operands.last() else null
    }

    private fun positionalOperands(
        args: List<String>,
        valueFlags: Set<String> = emptySet(),
    ): List<String> {
        val operands = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            if (token == "--") {
                operands += args.drop(index + 1)
                break
            }
            if (token in valueFlags) {
                index += 2
                continue
            }
            if (token.startsWith("-")) {
                index += 1
                continue
            }
            operands += token
            index += 1
        }
        return operands.map(String::trim).filter(String::isNotEmpty)
    }

    private fun displayPath(rawPath: String): String {
        val trimmed = rawPath.trim().trim('"', '\'')
        if (trimmed.isEmpty() || trimmed == ".") {
            return "current directory"
        }
        return File(trimmed).name.ifEmpty { trimmed }
    }

    private fun unwrapShellCommandIfPresent(command: String): String {
        val tokens = command.split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) {
            return command
        }

        val shellNames = listOf("bash", "zsh", "sh", "fish")
        var shellIndex = 0
        if (tokens.size >= 2) {
            val first = tokens[0].lowercase(Locale.US)
            val second = tokens[1].lowercase(Locale.US)
            if ((first == "env" || first.endsWith("/env")) &&
                shellNames.any { second == it || second.endsWith("/$it") }
            ) {
                shellIndex = 1
            }
        }

        val shell = tokens[shellIndex].lowercase(Locale.US)
        if (shellNames.none { shell == it || shell.endsWith("/$it") }) {
            return command
        }

        var index = shellIndex + 1
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == "-c" || token == "-lc" || token == "-cl" || token == "-ic" || token == "-ci") {
                index += 1
                return if (index < tokens.size) {
                    stripWrappingQuotes(tokens.drop(index).joinToString(separator = " "))
                } else {
                    command
                }
            }
            if (token.startsWith("-")) {
                index += 1
                continue
            }
            return stripWrappingQuotes(tokens.drop(index).joinToString(separator = " "))
        }

        return command
    }

    private fun stripWrappingQuotes(input: String): String {
        val trimmed = input.trim()
        if (trimmed.length < 2) {
            return trimmed
        }
        return if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))
        ) {
            trimmed.drop(1).dropLast(1)
        } else {
            trimmed
        }
    }
}

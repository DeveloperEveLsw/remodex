package app.remodex.android

import app.remodex.android.core.model.CodexCommandExecutionPhase
import app.remodex.android.core.model.CodexMessage
import java.util.Locale

data class RemodexCommandExecutionPresentation(
    val collapsedSummary: String,
    val phase: CodexCommandExecutionPhase,
    val detailStatusLabel: String,
    val rawCommand: String?,
)

object RemodexCommandExecutionPresenter {
    fun present(message: CodexMessage): RemodexCommandExecutionPresentation? {
        val details = message.commandExecutionDetails
        val rawCommand = details?.rawCommand?.trim()?.takeIf(String::isNotEmpty)
        val phase = details?.phase
            ?: parsePhase(message.text)
            ?: if (message.isStreaming) {
                CodexCommandExecutionPhase.Running
            } else {
                CodexCommandExecutionPhase.Completed
            }
        val safeSummary = rawCommand
            ?.let(RemodexCommandSummaryParser::summarize)
            ?.summary
            ?: details?.summary?.trim()?.takeIf(String::isNotEmpty)
        val fallbackPreview = rawCommand?.let(::shortCommandPreview)
            ?: previewFromMessageText(message.text)
            ?: "command"
        val collapsedSummary = safeSummary ?: buildFallbackSummary(
            phase = phase,
            preview = fallbackPreview,
        )

        return RemodexCommandExecutionPresentation(
            collapsedSummary = collapsedSummary,
            phase = phase,
            detailStatusLabel = detailStatusLabel(phase),
            rawCommand = rawCommand,
        )
    }

    private fun buildFallbackSummary(
        phase: CodexCommandExecutionPhase,
        preview: String,
    ): String {
        return when (phase) {
            CodexCommandExecutionPhase.Running -> "Running $preview"
            CodexCommandExecutionPhase.Completed -> "Ran $preview"
            CodexCommandExecutionPhase.Failed -> "Failed $preview"
            CodexCommandExecutionPhase.Stopped -> "Stopped $preview"
        }
    }

    private fun detailStatusLabel(phase: CodexCommandExecutionPhase): String {
        return when (phase) {
            CodexCommandExecutionPhase.Running -> "Running"
            CodexCommandExecutionPhase.Completed -> "Succeeded"
            CodexCommandExecutionPhase.Failed -> "Failed"
            CodexCommandExecutionPhase.Stopped -> "Stopped"
        }
    }

    private fun previewFromMessageText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val words = trimmed.split(Regex("\\s+")).filter(String::isNotBlank)
        val phase = parsePhase(trimmed)
        return if (phase != null) {
            words.drop(1).joinToString(separator = " ").trim().ifEmpty { null }
        } else {
            trimmed
        }
    }

    private fun parsePhase(text: String): CodexCommandExecutionPhase? {
        val token = text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase(Locale.US) ?: return null
        return when (token) {
            "running" -> CodexCommandExecutionPhase.Running
            "completed" -> CodexCommandExecutionPhase.Completed
            "failed" -> CodexCommandExecutionPhase.Failed
            "stopped" -> CodexCommandExecutionPhase.Stopped
            else -> null
        }
    }

    private fun shortCommandPreview(rawCommand: String, maxLength: Int = 92): String {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) {
            return "command"
        }
        val compact = trimmed.replace(Regex("\\s+"), " ")
        val unwrapped = unwrapShellCommandIfPresent(compact)
        var preview = unwrapped.replace(Regex("\\s+"), " ").trim()
        if (preview.isEmpty()) {
            preview = "command"
        }
        if (preview.length > maxLength) {
            preview = preview.take(maxLength - 1) + "..."
        }
        return preview
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

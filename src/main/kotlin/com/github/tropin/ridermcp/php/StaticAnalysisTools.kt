package com.github.tropin.ridermcp.php

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class StaticAnalysisToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs PHPStan static analysis. " +
            "action='analyse' runs static analysis (optional paths, level 0-9). " +
            "action='baseline' generates baseline file. " +
            "action='clear-result-cache' clears result cache. " +
            "projectPath scopes to subdirectory (where phpstan.neon lives)."
    )
    suspend fun rider_phpstan(
        @McpDescription("Action: analyse (default), baseline, clear-result-cache") action: String = "analyse",
        @McpDescription("Paths to analyse (comma-separated, e.g., 'src,tests')") paths: String? = null,
        @McpDescription("Analysis level 0-9 (default: 5)") level: Int = 5,
        @McpDescription("Project-relative path to phpstan.neon config") configFile: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Additional PHPStan arguments (e.g., '--no-progress', '--memory-limit=1G')") extraArgs: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "analyse" -> analyse(workDir, paths, level, configFile, extraArgs)
            "baseline" -> baseline(workDir, configFile, extraArgs)
            "clear-result-cache" -> clearCache(workDir)
            else -> mcpFail("Unknown action '$action'. Use: analyse, baseline, clear-result-cache")
        }
    }

    private suspend fun analyse(
        workDir: String, paths: String?, level: Int, configFile: String?, extraArgs: String?
    ): String {
        val cmd = mutableListOf<String>("./vendor/bin/phpstan", "analyse")
        paths?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        cmd.addAll(listOf("--level", level.toString()))
        configFile?.let { cmd.addAll(listOf("-c", it)) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 300)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "clean" else "issues_found"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun baseline(workDir: String, configFile: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("./vendor/bin/phpstan", "generate-baseline")
        configFile?.let { cmd.add("-c"); cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "generated" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun clearCache(workDir: String): String {
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, listOf("./vendor/bin/phpstan", "clear-result-cache"), timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "cleared" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startsWith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 300): Pair<Int, String> {
        val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
            .withWorkDirectory(coroutineContext.project.basePath!!)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}
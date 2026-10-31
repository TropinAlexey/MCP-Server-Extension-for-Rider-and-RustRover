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
import com.github.tropin.ridermcp.SessionManager
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class PHPUnitToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs PHPUnit tests. " +
            "action='run' executes tests (optional filter, returns sessionId for polling). " +
            "action='run_and_wait' blocks until completion (timeoutMs max 300000). " +
            "action='coverage' runs with coverage report. " +
            "action='list' lists tests without running. " +
            "action='filter' lists tests matching filter. " +
            "projectPath scopes to subdirectory (where phpunit.xml lives)."
    )
    suspend fun rider_phpunit(
        @McpDescription("Action: run (default), run_and_wait, coverage, list, filter") action: String = "run",
        @McpDescription("Test filter (e.g., 'MyTestClass', 'MyTestClass::testMethod')") filter: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Path to phpunit.xml config (relative to projectPath)") configFile: String? = null,
        @McpDescription("Run with coverage (requires xdebug or pcov)") coverage: Boolean = false,
        @McpDescription("Coverage output format: clover, xml, text, html") coverageFormat: String = "clover",
        @McpDescription("Additional PHPUnit arguments (e.g., '--verbose', '--testdox')") extraArgs: String? = null,
        @McpDescription("Timeout in ms for run_and_wait (default 120000)") timeoutMs: Int = 120000,
        @McpDescription("Session ID for run_and_wait (from previous run)") sessionId: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "run" -> runTests(workDir, filter, extraArgs, wait = false)
            "run_and_wait" -> runTests(workDir, filter, extraArgs, wait = true, timeoutMs = timeoutMs)
            "coverage" -> runCoverage(workDir, filter, extraArgs, coverageFormat)
            "list" -> listTests(workDir, filter)
            "filter" -> filterTests(workDir, filter)
            else -> mcpFail("Unknown action '$action'. Use: run, run_and_wait, coverage, list, filter")
        }
    }

    private suspend fun runTests(
        workDir: String, filter: String?, extraArgs: String?,
        wait: Boolean, timeoutMs: Int = 120000
    ): String {
        val cmd = mutableListOf<String>("./vendor/bin/phpunit")
        filter?.let { cmd.addAll(listOf("--filter", it)) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }

        if (wait) {
            val (exitCode, output) = runCmd(workDir, cmd, timeout = (timeoutMs / 1000 + 10).toLong())
            val exitCodeLong: Long = exitCode.toLong()
            return JsonObject(mapOf(
                "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
                "exitCode" to JsonPrimitive(exitCodeLong),
                "output" to JsonPrimitive(output.trim())
            )).toString()
        }

        // Async run with session polling
        val session = SessionManager.create("phpunit_test")
        try {
            val cmd = mutableListOf<String>("./vendor/bin/phpunit")
            filter?.let { cmd.addAll(listOf("--filter", it)) }
            extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
                .withWorkDirectory(coroutineContext.project.basePath!!)
                .withRedirectErrorStream(true)
            session.appendLine("Running: ${commandLine.commandLineString}")
            val handler = com.intellij.execution.process.OSProcessHandler(commandLine)
            session.tag = handler
            handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text.trimEnd('\n', '\r')
                    if (text.isNotEmpty()) session.appendLine(text)
                }
                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                    session.exitCode = event.exitCode
                    session.status = if (event.exitCode == 0) "passed" else "failed"
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return JsonObject(mapOf("sessionId" to JsonPrimitive(session.id))).toString()
    }

    private suspend fun runCoverage(workDir: String, filter: String?, extraArgs: String?, coverageFormat: String): String {
        val cmd = mutableListOf<String>("./vendor/bin/phpunit", "--coverage-$coverageFormat")
        filter?.let { cmd.addAll(listOf("--filter", it)) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        val exitCodeLong: Long = exitCode.toLong()
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
            "exitCode" to JsonPrimitive(exitCodeLong),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun listTests(workDir: String, filter: String?): String {
        val cmd = mutableListOf<String>("./vendor/bin/phpunit", "--list-tests")
        filter?.let { cmd.addAll(listOf("--filter", it)) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 30)
        if (exitCode != 0) mcpFail("phpunit --list-tests failed:\n$output")
        val tests = output.trim().split("\n").filter { it.isNotBlank() }.map { it.trim() }
        val testsArray = JsonArray(tests.map { JsonPrimitive(it) })
        return JsonObject(mapOf("tests" to testsArray)).toString()
    }

    private suspend fun filterTests(workDir: String, filter: String?): String {
        val cmd = mutableListOf<String>("./vendor/bin/phpunit", "--filter")
        filter?.let { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 30)
        if (exitCode != 0) mcpFail("phpunit --filter failed:\n$output")
        val tests = output.trim().split("\n").filter { it.isNotBlank() }.map { it.trim() }
        val testsArray = JsonArray(tests.map { JsonPrimitive(it) })
        return JsonObject(mapOf("tests" to testsArray)).toString()
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startsWith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 60): Pair<Int, String> {
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
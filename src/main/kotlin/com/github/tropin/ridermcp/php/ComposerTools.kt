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

class ComposerToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages PHP packages via Composer. " +
            "action='install' installs dependencies from composer.json/lock. " +
            "action='update' updates packages (optional package names). " +
            "action='require' adds a package (requires name; optional version, dev). " +
            "action='remove' removes a package. " +
            "action='show' shows package info. " +
            "action='outdated' checks for outdated packages. " +
            "action='validate' validates composer.json. " +
            "action='dump-autoload' regenerates autoloader. " +
            "action='create-project' creates new project from package. " +
            "action='run-script' runs a Composer script. " +
            "action='audit' checks for security vulnerabilities (requires composer-audit plugin). " +
            "projectPath scopes to subdirectory (where composer.json lives)."
    )
    suspend fun rider_composer(
        @McpDescription("Action: install (default), update, require, remove, show, outdated, validate, dump-autoload, create-project, run-script, audit") action: String = "install",
        @McpDescription("Package name (required for require/remove/show)") name: String? = null,
        @McpDescription("Package version constraint (e.g., '^1.0', '1.0.0', 'dev-main')") version: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Install as dev dependency (require only)") dev: Boolean = false,
        @McpDescription("Additional Composer arguments (e.g., '--no-dev', '--prefer-dist', '--optimize-autoloader')") extraArgs: String? = null,
        @McpDescription("Script name for run-script action") script: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "install" -> install(workDir, extraArgs)
            "update" -> update(workDir, name, extraArgs)
            "require" -> {
                if (name.isNullOrBlank()) mcpFail("name is required for require")
                requirePackage(workDir, name, version, dev, extraArgs)
            }
            "remove" -> {
                if (name.isNullOrBlank()) mcpFail("name is required for remove")
                removePackage(workDir, name, extraArgs)
            }
            "show" -> {
                if (name.isNullOrBlank()) mcpFail("name is required for show")
                showPackage(workDir, name, extraArgs)
            }
            "outdated" -> outdated(workDir, extraArgs)
            "validate" -> validate(workDir, extraArgs)
            "dump-autoload" -> dumpAutoload(workDir, extraArgs)
            "create-project" -> {
                if (name.isNullOrBlank()) mcpFail("name is required for create-project")
                createProject(workDir, name, version, extraArgs)
            }
            "run-script" -> {
                if (name.isNullOrBlank()) mcpFail("script is required for run-script")
                runScript(workDir, name, extraArgs)
            }
            "audit" -> audit(workDir, extraArgs)
            else -> mcpFail("Unknown action '$action'. Use: install, update, require, remove, show, outdated, validate, dump-autoload, create-project, run-script, audit")
        }
    }

    private suspend fun install(workDir: String, extraArgs: String?): String {
        val cmd = mutableListOf<String>("composer", "install")
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "installed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun update(workDir: String, name: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("composer", "update")
        name?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "updated" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun requirePackage(workDir: String, name: String?, version: String?, dev: Boolean, extraArgs: String?): String {
        val cmd = mutableListOf<String>("composer", "require")
        if (dev) cmd.add("--dev")
        val pkg = when {
            version != null -> "$name:$version"
            else -> name
        }
        cmd.add(pkg!!)
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "required" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun removePackage(workDir: String, name: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("composer", "remove")
        name?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 120)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "removed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun showPackage(workDir: String, name: String?, extraArgs: String?): String {
        val cmd = listOf("composer", "show", name!!)
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun outdated(workDir: String, extraArgs: String?): String {
        val cmd = listOf("composer", "outdated", "--format=json")
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 60)
        val outdatedArray = JsonArray(
            try {
                val json = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(output)
                (json["installed"] as? List<*>)?.mapNotNull { pkg ->
                    val p = pkg as? Map<String, Any>
                    p?.let {
                        JsonObject(mapOf(
                            "name" to JsonPrimitive(it["name"]?.toString() ?: ""),
                            "version" to JsonPrimitive(it["version"]?.toString() ?: ""),
                            "latest" to JsonPrimitive(it["latest"]?.toString() ?: ""),
                            "latest_status" to JsonPrimitive(it["latest-status"]?.toString() ?: "")
                        ))
                    }
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        )
        return JsonObject(mapOf("outdated" to JsonArray(outdatedArray))).toString()
    }

    private suspend fun validate(workDir: String, extraArgs: String?): String {
        val cmd = listOf("composer", "validate", "--strict")
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "valid" else "invalid"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun dumpAutoload(workDir: String, extraArgs: String?): String {
        val cmd = listOf("composer", "dump-autoload", "--optimize")
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun createProject(workDir: String, name: String?, version: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("composer", "create-project")
        name?.let { cmd.add(it) }
        version?.let { cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 300)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "created" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun runScript(workDir: String, script: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("composer", "run-script")
        script?.let { cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun audit(workDir: String, extraArgs: String?): String {
        val cmd = listOf("composer", "audit", "--format=json")
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 120)
        val vulnsArray = JsonArray(
            try {
                val json = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(output)
                (json["advisories"] as? Map<String, Any>)?.values?.mapNotNull { adv ->
                    val advMap = adv as? Map<String, Any>
                    advMap?.let {
                        JsonObject(mapOf(
                            "name" to JsonPrimitive(it["name"]?.toString() ?: ""),
                            "version" to JsonPrimitive(it["version"]?.toString() ?: ""),
                            "advisory_id" to JsonPrimitive(it["advisory_id"]?.toString() ?: ""),
                            "title" to JsonPrimitive(it["title"]?.toString() ?: ""),
                            "link" to JsonPrimitive(it["link"]?.toString() ?: "")
                        ))
                    }
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        )
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "clean" else "vulnerabilities_found"),
            "vulnerabilities" to JsonArray(vulnsArray)
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

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 180): Pair<Int, String> {
        val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
            .withWorkDirectory(workDir)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}
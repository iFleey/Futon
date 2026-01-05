/*
 * Futon Deployment Contract Plugin
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import com.google.gson.Gson
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.process.ExecOperations
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject

data class DeploymentRules(
  val description: String? = null,
  val rules: List<DeploymentRule>,
  val architectures: List<String>,
)

data class DeploymentRule(
  val comment: String? = null,
  val appVersionRange: VersionRange,
  val module: ModuleSpec,
)

data class VersionRange(
  val from: Int,
  val to: Int? = null,
)

data class ModuleSpec(
  val daemonHash: String,
  val moduleVersion: Int,
  val patchVersion: Int = 0,
)

class DeploymentContractPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.tasks.register<BuildDaemonTask>("buildDaemon") {
      group = "deployment"
      description = "Build the native daemon binary"
    }

    project.tasks.register<ValidateDeploymentRulesTask>("validateDeploymentRules") {
      group = "deployment"
      description = "Validate deployment-rules.json"
    }

    project.tasks.register<ComputeDaemonHashTask>("computeDaemonHash") {
      group = "deployment"
      description = "Compute SHA256 hash of daemon binary"
      dependsOn("buildDaemon")
    }

    project.tasks.register<VerifyDeploymentContractTask>("verifyDeploymentContract") {
      group = "deployment"
      description = "Verify daemon hash matches deployment rules"
      dependsOn("buildDaemon")
    }

    project.tasks.register<GenerateModuleTask>("generateFutonModule") {
      group = "deployment"
      description = "Generate Futon module with daemon binary"
    }

    project.afterEvaluate {
      configureTasks(project)
    }
  }

  private fun configureTasks(project: Project) {
    val android = project.extensions.findByName("android")
      ?: throw GradleException("DeploymentContractPlugin requires Android Application plugin")

    val defaultConfig = android.javaClass.getMethod("getDefaultConfig").invoke(android)
    val versionCode =
      defaultConfig.javaClass.getMethod("getVersionCode").invoke(defaultConfig) as? Int ?: 1

    val ndk = defaultConfig.javaClass.getMethod("getNdk").invoke(defaultConfig)

    @Suppress("UNCHECKED_CAST")
    val abiFilters =
      ndk.javaClass.getMethod("getAbiFilters").invoke(ndk) as? Set<String> ?: emptySet()
    val architecture = abiFilters.firstOrNull() ?: "arm64-v8a"

    val isReleaseBuild = project.gradle.startParameter.taskNames.any {
      it.contains("release", ignoreCase = true)
    }

    // Allow auto-update in CI environment even for release builds
    val isCiEnvironment = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
    val autoUpdateRules = !isReleaseBuild || isCiEnvironment

    val rulesFile = project.rootProject.file("deployment-rules.json")
    val daemonDir = project.rootProject.file("daemon")
    val daemonBuildDir = project.rootProject.file("daemon/build")
    val daemonBinary = project.rootProject.file("daemon/build/futon_daemon")
    val moduleSource = project.rootProject.file("su-module")
    val moduleTemplate = project.rootProject.file("su-module/module.prop.template")
    val outputDir = project.layout.buildDirectory.dir("generated/assets/futon_module").get().asFile

    val ndkPath = findNdkPath(project)
    val cmakePath = findCmakePath(project)

    project.logger.lifecycle("Deployment Contract Configuration:")
    project.logger.lifecycle("  versionCode: $versionCode")
    project.logger.lifecycle("  architecture: $architecture")
    project.logger.lifecycle("  autoUpdateRules: $autoUpdateRules")

    project.tasks.withType<BuildDaemonTask>().configureEach {
      this.daemonDir.set(daemonDir)
      this.daemonBuildDir.set(daemonBuildDir)
      this.architecture.set(architecture)
      this.ndkPath.set(ndkPath)
      this.cmakePath.set(cmakePath)
      // Configure source files at configuration time (configuration cache compatible)
      this.daemonSourceFiles.from(
        project.fileTree(daemonDir) {
          include("**/*.cpp", "**/*.c", "**/*.h", "**/*.hpp")
          include("**/CMakeLists.txt")
          exclude("build/**", ".cxx/**", "third_party/**")
        },
      )
      this.daemonBinaryOutput.set(daemonBinary)
    }

    project.tasks.withType<ValidateDeploymentRulesTask>().configureEach {
      this.rulesFile.set(rulesFile)
    }

    project.tasks.withType<ComputeDaemonHashTask>().configureEach {
      this.daemonBinary.set(daemonBinary)
      this.architecture.set(architecture)
      this.hashOutputFile.set(project.layout.buildDirectory.file("daemon-hash.txt"))
    }

    project.tasks.withType<VerifyDeploymentContractTask>().configureEach {
      this.rulesFile.set(rulesFile)
      this.daemonBinary.set(daemonBinary)
      this.appVersionCode.set(versionCode)
      this.architecture.set(architecture)
      this.autoUpdateRules.set(autoUpdateRules)
    }

    project.tasks.withType<GenerateModuleTask>().configureEach {
      this.rulesFile.set(rulesFile)
      this.daemonBinary.set(daemonBinary)
      this.moduleSourceDir.set(moduleSource)
      this.moduleTemplate.set(moduleTemplate)
      this.littertSdkDir.set(project.rootProject.file("daemon/inference/litert_cc_sdk"))
      this.ndkPath.set(ndkPath)
      this.outputDir.set(outputDir)
      this.appVersionCode.set(versionCode)
      this.architecture.set(architecture)

      dependsOn("buildDaemon", "verifyDeploymentContract")
    }

    project.tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }
      .configureEach {
        dependsOn("generateFutonModule")
      }

    project.tasks.all {
      if (name.contains("lint", ignoreCase = true) || name.contains("Lint")) {
        dependsOn("generateFutonModule")
      }
    }
  }

  private fun findNdkPath(project: Project): String {
    val localProperties = project.rootProject.file("local.properties")
    if (localProperties.exists()) {
      val props = java.util.Properties()
      localProperties.inputStream().use { props.load(it) }
      props.getProperty("ndk.dir")?.let { return it }
    }

    System.getenv("ANDROID_NDK_HOME")?.let { return it }

    val androidHome = System.getenv("ANDROID_HOME")
      ?: project.rootProject.file("local.properties").let { file ->
        if (file.exists()) {
          val props = java.util.Properties()
          file.inputStream().use { props.load(it) }
          props.getProperty("sdk.dir")
        } else null
      }

    if (androidHome != null) {
      val ndkDir = File(androidHome, "ndk")
      if (ndkDir.exists()) {
        val versions = ndkDir.listFiles()?.filter { it.isDirectory }?.sortedDescending()
        versions?.firstOrNull()?.let { return it.absolutePath }
      }
    }

    throw GradleException("NDK not found")
  }

  private fun findCmakePath(project: Project): String {
    val localProperties = project.rootProject.file("local.properties")
    if (localProperties.exists()) {
      val props = java.util.Properties()
      localProperties.inputStream().use { props.load(it) }
      props.getProperty("cmake.dir")?.let { dir ->
        val cmake = File(dir, "bin/cmake").let { if (it.exists()) it else File(dir, "cmake") }
        if (cmake.exists()) return cmake.absolutePath
      }
    }

    val androidHome = System.getenv("ANDROID_HOME")
      ?: project.rootProject.file("local.properties").let { file ->
        if (file.exists()) {
          val props = java.util.Properties()
          file.inputStream().use { props.load(it) }
          props.getProperty("sdk.dir")
        } else null
      }

    if (androidHome != null) {
      val cmakeDir = File(androidHome, "cmake")
      if (cmakeDir.exists()) {
        val versions = cmakeDir.listFiles()?.filter { it.isDirectory }?.sortedDescending()
        versions?.forEach { version ->
          val cmake = File(version, "bin/cmake")
          if (cmake.exists()) return cmake.absolutePath
        }
      }
    }

    val commonPaths = listOf(
      "/usr/local/bin/cmake",
      "/opt/homebrew/bin/cmake",
      "/usr/bin/cmake",
    )
    commonPaths.forEach { path ->
      if (File(path).exists()) return path
    }

    return "cmake"
  }
}

abstract class BuildDaemonTask @Inject constructor(
  private val execOperations: ExecOperations,
) : DefaultTask() {
  @get:Input
  abstract val architecture: Property<String>

  @get:Input
  abstract val ndkPath: Property<String>

  @get:Input
  abstract val cmakePath: Property<String>

  // Daemon source directory (used for cmake path, not for up-to-date check)
  @get:Internal
  abstract val daemonDir: DirectoryProperty

  @get:OutputDirectory
  abstract val daemonBuildDir: DirectoryProperty

  // This MUST be configured at configuration time, not execution time
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:SkipWhenEmpty
  abstract val daemonSourceFiles: org.gradle.api.file.ConfigurableFileCollection

  // Output binary for up-to-date checking
  @get:OutputFile
  abstract val daemonBinaryOutput: RegularFileProperty

  @TaskAction
  fun build() {
    val arch = architecture.get()
    val ndk = ndkPath.get()
    val cmake = cmakePath.get()
    val sourceDir = daemonDir.get().asFile
    val buildDir = daemonBuildDir.get().asFile

    val androidAbi = when (arch) {
      "arm64-v8a" -> "arm64-v8a"
      "armeabi-v7a" -> "armeabi-v7a"
      "x86_64" -> "x86_64"
      "x86" -> "x86"
      else -> throw GradleException("Unsupported architecture: $arch")
    }

    buildDir.mkdirs()

    val toolchainFile = File(ndk, "build/cmake/android.toolchain.cmake")
    if (!toolchainFile.exists()) {
      throw GradleException("NDK toolchain not found: ${toolchainFile.absolutePath}")
    }

    logger.lifecycle("Building daemon for $androidAbi")

    val configureResult = execOperations.exec {
      workingDir = buildDir
      commandLine(
        cmake,
        "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
        "-DANDROID_ABI=$androidAbi",
        "-DANDROID_PLATFORM=android-30",
        "-DANDROID_STL=c++_shared",
        "-DCMAKE_BUILD_TYPE=Release",
        sourceDir.absolutePath,
      )
    }

    if (configureResult.exitValue != 0) {
      throw GradleException("CMake configure failed")
    }

    val buildResult = execOperations.exec {
      workingDir = buildDir
      commandLine(
        cmake,
        "--build",
        ".",
        "--target",
        "futon_daemon",
        "-j",
        Runtime.getRuntime().availableProcessors().toString(),
      )
    }

    if (buildResult.exitValue != 0) {
      throw GradleException("CMake build failed")
    }

    val outputBinary = File(buildDir, "futon_daemon")
    if (!outputBinary.exists()) {
      throw GradleException("Daemon binary not found: ${outputBinary.absolutePath}")
    }

    val unstrippedSize = outputBinary.length()

    val llvmStrip = findLlvmStrip(ndk)
    if (llvmStrip != null) {
      val stripResult = execOperations.exec {
        commandLine(llvmStrip.absolutePath, outputBinary.absolutePath)
        isIgnoreExitValue = true
      }
      if (stripResult.exitValue == 0) {
        logger.lifecycle("Stripped: ${formatSize(unstrippedSize)} -> ${formatSize(outputBinary.length())}")
      }
    }

    logger.lifecycle("Daemon built: ${outputBinary.absolutePath} (${formatSize(outputBinary.length())})")
  }

  private fun findLlvmStrip(ndkPath: String): File? {
    val hostTag = when {
      System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
      System.getProperty("os.name").lowercase().contains("linux") -> "linux-x86_64"
      System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
      else -> return null
    }
    val llvmStrip = File(ndkPath, "toolchains/llvm/prebuilt/$hostTag/bin/llvm-strip")
    return if (llvmStrip.exists()) llvmStrip else null
  }

  private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
    else -> "$bytes bytes"
  }
}

abstract class ValidateDeploymentRulesTask : DefaultTask() {
  @get:InputFile
  abstract val rulesFile: RegularFileProperty

  @TaskAction
  fun validate() {
    val file = rulesFile.get().asFile
    if (!file.exists()) {
      throw GradleException("Deployment rules not found: ${file.absolutePath}")
    }

    val rules = try {
      Gson().fromJson(file.readText(), DeploymentRules::class.java)
    } catch (e: Exception) {
      throw GradleException("Failed to parse deployment rules: ${e.message}")
    }

    if (rules.rules.isEmpty()) {
      throw GradleException("Deployment rules must contain at least one rule")
    }

    if (rules.architectures.isEmpty()) {
      throw GradleException("Deployment rules must specify at least one architecture")
    }

    val sortedRules = rules.rules.sortedBy { it.appVersionRange.from }
    for (i in 0 until sortedRules.size - 1) {
      val current = sortedRules[i]
      val next = sortedRules[i + 1]
      val currentEnd = current.appVersionRange.to ?: Int.MAX_VALUE
      if (currentEnd >= next.appVersionRange.from) {
        throw GradleException("Version ranges overlap")
      }
    }

    rules.rules.forEach { rule ->
      val hash = rule.module.daemonHash
      if (!hash.matches(Regex("^sha256:[a-f0-9]{64}$")) && hash != "sha256:pending") {
        throw GradleException("Invalid daemon hash format: $hash")
      }
    }

    logger.lifecycle("Deployment rules validated: ${rules.rules.size} rules")
  }
}

abstract class ComputeDaemonHashTask : DefaultTask() {
  @get:InputFile
  abstract val daemonBinary: RegularFileProperty

  @get:Input
  abstract val architecture: Property<String>

  @get:OutputFile
  abstract val hashOutputFile: RegularFileProperty

  @TaskAction
  fun compute() {
    val binary = daemonBinary.get().asFile
    if (!binary.exists()) {
      throw GradleException("Daemon binary not found: ${binary.absolutePath}")
    }

    val hash = computeSha256(binary)
    val outputFile = hashOutputFile.get().asFile
    outputFile.parentFile.mkdirs()
    outputFile.writeText("sha256:$hash")

    logger.lifecycle("Daemon hash: sha256:$hash")
  }

  private fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(8192)
      var read: Int
      while (input.read(buffer).also { read = it } != -1) {
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}

abstract class VerifyDeploymentContractTask : DefaultTask() {
  @get:InputFile
  abstract val rulesFile: RegularFileProperty

  @get:InputFile
  abstract val daemonBinary: RegularFileProperty

  @get:Input
  abstract val appVersionCode: Property<Int>

  @get:Input
  abstract val architecture: Property<String>

  @get:Input
  abstract val autoUpdateRules: Property<Boolean>

  @TaskAction
  fun verify() {
    val rules = Gson().fromJson(rulesFile.get().asFile.readText(), DeploymentRules::class.java)
    val versionCode = appVersionCode.get()

    val matchingRule = rules.rules.find { rule ->
      val from = rule.appVersionRange.from
      val to = rule.appVersionRange.to ?: Int.MAX_VALUE
      versionCode in from..to
    } ?: throw GradleException("No deployment rule found for versionCode $versionCode")

    val actualHash = "sha256:" + computeSha256(daemonBinary.get().asFile)
    val expectedHash = matchingRule.module.daemonHash

    logger.lifecycle("Expected hash: $expectedHash")
    logger.lifecycle("Actual hash:   $actualHash")

    when {
      actualHash == expectedHash -> {
        logger.lifecycle("Deployment contract verified")
      }

      autoUpdateRules.get() -> {
        logger.warn("Daemon hash changed, auto-updating deployment-rules.json")
        updateRulesFile(actualHash, matchingRule, rules)
      }

      expectedHash == "sha256:pending" -> {
        throw GradleException(
          "Release build blocked: daemon hash is pending\n" +
            "Update deployment-rules.json with: $actualHash",
        )
      }

      else -> {
        throw GradleException(
          "Deployment contract violation\n" +
            "Expected: $expectedHash\n" +
            "Actual:   $actualHash",
        )
      }
    }
  }

  private fun updateRulesFile(
    actualHash: String,
    matchingRule: DeploymentRule,
    rules: DeploymentRules,
  ) {
    val updatedRules = rules.rules.map { rule ->
      if (rule === matchingRule) {
        rule.copy(module = rule.module.copy(daemonHash = actualHash))
      } else {
        rule
      }
    }
    val updatedDeploymentRules = rules.copy(rules = updatedRules)
    rulesFile.get().asFile.writeText(
      Gson().newBuilder().setPrettyPrinting().create().toJson(updatedDeploymentRules),
    )
    logger.lifecycle("Updated deployment-rules.json")
  }

  private fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(8192)
      var read: Int
      while (input.read(buffer).also { read = it } != -1) {
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}

abstract class GenerateModuleTask : DefaultTask() {
  @get:InputFile
  abstract val rulesFile: RegularFileProperty

  @get:InputFile
  abstract val daemonBinary: RegularFileProperty

  @get:InputDirectory
  abstract val moduleSourceDir: DirectoryProperty

  @get:InputFile
  abstract val moduleTemplate: RegularFileProperty

  @get:InputDirectory
  abstract val littertSdkDir: DirectoryProperty

  @get:Input
  abstract val ndkPath: Property<String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @get:Input
  abstract val appVersionCode: Property<Int>

  @get:Input
  abstract val architecture: Property<String>

  @TaskAction
  fun generate() {
    val rules = Gson().fromJson(rulesFile.get().asFile.readText(), DeploymentRules::class.java)
    val versionCode = appVersionCode.get()
    val arch = architecture.get()
    val output = outputDir.get().asFile

    val matchingRule = rules.rules.find { rule ->
      val from = rule.appVersionRange.from
      val to = rule.appVersionRange.to ?: Int.MAX_VALUE
      versionCode in from..to
    } ?: throw GradleException("No deployment rule found for versionCode $versionCode")

    val daemonHash = "sha256:" + computeSha256(daemonBinary.get().asFile)

    output.deleteRecursively()
    output.mkdirs()

    val sourceDir = moduleSourceDir.get().asFile
    sourceDir.listFiles()?.forEach { file ->
      if (file.name != "module.prop.template" && file.name != "bin" && file.name != "lib" && file.name != "README.md") {
        if (file.isDirectory) {
          file.copyRecursively(File(output, file.name), overwrite = true)
        } else {
          file.copyTo(File(output, file.name), overwrite = true)
        }
      }
    }

    val binDir = File(output, "bin/$arch")
    binDir.mkdirs()
    daemonBinary.get().asFile.copyTo(File(binDir, "futon_daemon"), overwrite = true)

    // Copy LiteRT shared libraries
    val libDir = File(output, "lib/$arch")
    libDir.mkdirs()
    val littertSdk = littertSdkDir.get().asFile
    val littertLibDir = File(littertSdk, "lib/$arch")
    if (littertLibDir.exists()) {
      littertLibDir.listFiles()?.filter { it.extension == "so" }?.forEach { soFile ->
        soFile.copyTo(File(libDir, soFile.name), overwrite = true)
        logger.lifecycle("  Copied: ${soFile.name}")
      }
    } else {
      logger.warn("LiteRT lib directory not found: ${littertLibDir.absolutePath}")
    }

    // Copy libc++_shared.so from NDK (required for c++_shared STL)
    val ndk = ndkPath.get()
    val libcppPath = File(ndk, "toolchains/llvm/prebuilt/${getHostTag()}/sysroot/usr/lib/${getAndroidTriple(arch)}/libc++_shared.so")
    if (libcppPath.exists()) {
      libcppPath.copyTo(File(libDir, "libc++_shared.so"), overwrite = true)
      logger.lifecycle("  Copied: libc++_shared.so")
    } else {
      logger.warn("libc++_shared.so not found at: ${libcppPath.absolutePath}")
    }

    val template = moduleTemplate.get().asFile.readText()
    val patchVersion = matchingRule.module.patchVersion
    val moduleVersion = "1.${matchingRule.module.moduleVersion}.$patchVersion"
    val moduleVersionCode = matchingRule.module.moduleVersion

    val moduleProp = template
      .replace($$"${MODULE_VERSION}", moduleVersion)
      .replace($$"${MODULE_VERSION_CODE}", moduleVersionCode.toString())
      .replace($$"${DAEMON_HASH}", daemonHash)
      .replace($$"${DAEMON_ARCH}", arch)
      .replace($$"${APP_VERSION_FROM}", matchingRule.appVersionRange.from.toString())
      .replace($$"${APP_VERSION_TO}", matchingRule.appVersionRange.to?.toString() ?: "open")
      .replace($$"${GENERATED_AT}", Instant.now().toString())

    File(output, "module.prop").writeText(moduleProp)

    output.listFiles()?.filter { it.extension == "sh" }?.forEach { script ->
      script.setExecutable(true)
    }

    logger.lifecycle("Generated Futon module: ${output.absolutePath}")
    logger.lifecycle("  Daemon: $arch/futon_daemon")
    logger.lifecycle("  Libraries: $arch/*.so")
    logger.lifecycle("  Module version: $moduleVersion")
  }

  private fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(8192)
      var read: Int
      while (input.read(buffer).also { read = it } != -1) {
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun getHostTag(): String = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux-x86_64"
    System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
    else -> "linux-x86_64"
  }

  private fun getAndroidTriple(arch: String): String = when (arch) {
    "arm64-v8a" -> "aarch64-linux-android"
    "armeabi-v7a" -> "arm-linux-androideabi"
    "x86_64" -> "x86_64-linux-android"
    "x86" -> "i686-linux-android"
    else -> "aarch64-linux-android"
  }
}

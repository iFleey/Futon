/*
 * Futon - Futon Daemon Client
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
package me.fleey.futon.debug

/**
 * Annotation to mark classes, functions, or properties that should only be used
 * in debug builds. Code annotated with this will be stripped by R8 in release builds.
 *
 * Usage:
 * ```kotlin
 * @RequiresDebugBuild
 * class DebugOnlyFeature {
 *     fun doDebugStuff() { ... }
 * }
 *
 * @RequiresDebugBuild
 * fun debugLog(message: String) { ... }
 * ```
 *
 * Note: This annotation serves as documentation and enables R8 stripping via ProGuard rules.
 * The actual stripping is configured in proguard-rules.pro.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.FIELD,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class RequiresDebugBuild(
  val reason: String = "",
)

/**
 * Utility object for debug build checks.
 * Provides runtime verification that debug-only code is not accidentally
 * executed in release builds.
 */
object DebugBuildChecker {

  /**
   * Returns true if this is a debug build.
   * Uses BuildConfig.DEBUG which is set by the build system.
   */
  val isDebugBuild: Boolean
    get() = try {
      val buildConfigClass = Class.forName("me.fleey.futon.BuildConfig")
      val debugField = buildConfigClass.getField("DEBUG")
      debugField.getBoolean(null)
    } catch (_: Exception) {
      false
    }

  /**
   * Executes the given block only in debug builds.
   * In release builds, this is a no-op.
   */
  inline fun runInDebugOnly(block: () -> Unit) {
    if (isDebugBuild) {
      block()
    }
  }

  /**
   * Asserts that the current build is a debug build.
   * Throws IllegalStateException in release builds.
   */
  fun assertDebugBuild(message: String = "This code should only run in debug builds") {
    check(isDebugBuild) { message }
  }
}

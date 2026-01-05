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
package me.fleey.futon.data.localmodel.validation

sealed class ValidationResult {
  data object Valid : ValidationResult()

  data class Invalid(val reasons: List<String>) : ValidationResult() {
    constructor(reason: String) : this(listOf(reason))
  }

  fun isValid(): Boolean = this is Valid

  fun isInvalid(): Boolean = this is Invalid

  fun getErrors(): List<String> = when (this) {
    is Valid -> emptyList()
    is Invalid -> reasons
  }
}

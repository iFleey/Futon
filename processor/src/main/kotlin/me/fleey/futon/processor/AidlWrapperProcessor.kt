/*
 * Futon - Futon Daemon Client Processor
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

package me.fleey.futon.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

class AidlWrapperProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) : SymbolProcessor {

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val symbols = resolver.getSymbolsWithAnnotation("me.fleey.futon.data.daemon.annotations.AidlWrapper")
    val ret = symbols.filter { !it.validate() }.toList()
    symbols
      .filter { it is KSClassDeclaration && it.classKind == ClassKind.INTERFACE && it.validate() }
      .forEach { it.accept(AidlWrapperVisitor(resolver), Unit) }
    return ret
  }

  inner class AidlWrapperVisitor(private val resolver: Resolver) : KSVisitorVoid() {
    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
      val packageName = classDeclaration.packageName.asString()
      val interfaceName = classDeclaration.simpleName.asString()
      val wrapperName = "${interfaceName}Wrapper"

      val annotation = classDeclaration.annotations.find {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == "me.fleey.futon.data.daemon.annotations.AidlWrapper"
      } ?: return

      val aidlClassName = annotation.arguments.find { it.name?.asString() == "aidlClassName" }?.value as String
      val aidlClass = ClassName(aidlClassName.substringBeforeLast("."), aidlClassName.substringAfterLast("."))
      val aidlClassDeclaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(aidlClassName))

      val executorClass = ClassName("me.fleey.futon.data.daemon.annotations", "AidlCallExecutor")
      val parameterizedExecutor = executorClass.parameterizedBy(aidlClass)

      val fileSpec = FileSpec.builder(packageName, wrapperName)
        .addType(
          TypeSpec.classBuilder(wrapperName)
            .primaryConstructor(
              FunSpec.constructorBuilder()
                .addParameter("aidl", aidlClass)
                .addParameter("executor", parameterizedExecutor)
                .build(),
            )
            .addProperty(
              PropertySpec.builder("aidl", aidlClass)
                .initializer("aidl")
                .addModifiers(KModifier.PRIVATE)
                .build(),
            )
            .addProperty(
              PropertySpec.builder("executor", parameterizedExecutor)
                .initializer("executor")
                .addModifiers(KModifier.PRIVATE)
                .build(),
            )
            .addSuperinterface(classDeclaration.toClassName())
            .apply {
              val aidlProperties =
                aidlClassDeclaration?.getAllProperties()?.map { it.simpleName.asString() }?.toSet() ?: emptySet()
              classDeclaration.getAllProperties().forEach { property ->
                val name = property.simpleName.asString()
                val type = property.type.resolve().toTypeName()
                if (name in aidlProperties) {
                  addProperty(
                    PropertySpec.builder(name, type)
                      .addModifiers(KModifier.OVERRIDE)
                      .getter(
                        FunSpec.getterBuilder()
                          .addCode("return aidl.$name\n")
                          .build(),
                      )
                      .build(),
                  )
                } else {
                  // Stub for members not in AIDL
                  addProperty(
                    PropertySpec.builder(name, type)
                      .addModifiers(KModifier.OVERRIDE)
                      .getter(
                        FunSpec.getterBuilder()
                          .addCode("throw UnsupportedOperationException(\"Not implemented in wrapper\")\n")
                          .build(),
                      )
                      .build(),
                  )
                }
              }

              val aidlFunctions =
                aidlClassDeclaration?.getAllFunctions()?.map { it.simpleName.asString() }?.toSet() ?: emptySet()
              classDeclaration.getAllFunctions()
                .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString") }
                .forEach { function ->
                  val name = function.simpleName.asString()
                  if (name in aidlFunctions) {
                    addFunction(generateWrapperFunction(function))
                  } else {
                    // Stub for members not in AIDL
                    addFunction(
                      FunSpec.builder(name)
                        .addModifiers(KModifier.OVERRIDE)
                        .apply { if (function.modifiers.contains(Modifier.SUSPEND)) addModifiers(KModifier.SUSPEND) }
                        .apply {
                          function.parameters.forEach { parameter ->
                            addParameter(parameter.name!!.asString(), parameter.type.resolve().toTypeName())
                          }
                        }
                        .returns(function.returnType?.resolve()?.toTypeName() ?: UNIT)
                        .addCode("throw UnsupportedOperationException(\"Not implemented in wrapper\")\n")
                        .build(),
                    )
                  }
                }
            }
            .build(),
        )

        .build()

      fileSpec.writeTo(codeGenerator, false)
    }

    private fun generateWrapperFunction(function: KSFunctionDeclaration): FunSpec {
      val name = function.simpleName.asString()
      val ksReturnType = function.returnType?.resolve()
      val returnType = ksReturnType?.toTypeName() ?: UNIT

      val isSuspend = function.modifiers.contains(Modifier.SUSPEND)
      val returnsResult = ksReturnType?.declaration?.qualifiedName?.asString() == "kotlin.Result"
      val returnsUnit = returnType == UNIT || ksReturnType?.declaration?.qualifiedName?.asString() == "kotlin.Unit"
      val params = function.parameters.joinToString { it.name!!.asString() }

      return FunSpec.builder(name)
        .addModifiers(KModifier.OVERRIDE)
        .apply { if (isSuspend) addModifiers(KModifier.SUSPEND) }
        .apply {
          function.parameters.forEach { parameter ->
            addParameter(parameter.name!!.asString(), parameter.type.resolve().toTypeName())
          }
        }
        .returns(returnType)
        .addCode(
          when {
            returnsResult -> {
              CodeBlock.of(
                "return executor.execute { it.$name($params) } as %T\n",
                returnType,
              )
            }

            returnsUnit -> {
              CodeBlock.of(
                "executor.execute { it.$name($params) }.getOrThrow()\n",
              )
            }

            else -> {
              CodeBlock.of(
                "return executor.execute { it.$name($params) }.getOrThrow()\n",
              )
            }
          },
        )
        .build()
    }

  }
}

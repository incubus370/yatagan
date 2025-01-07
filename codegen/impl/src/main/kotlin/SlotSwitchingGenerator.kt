/*
 * Copyright 2022 Yandex LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yandex.yatagan.codegen.impl

import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeVariableName
import com.yandex.yatagan.codegen.poetry.TypeSpecBuilder
import com.yandex.yatagan.codegen.poetry.buildExpression
import com.yandex.yatagan.core.graph.BindingGraph
import com.yandex.yatagan.core.graph.bindings.Binding
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.inject.Inject
import javax.inject.Singleton
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PRIVATE
import javax.lang.model.element.Modifier.STATIC

@Singleton
internal class SlotSwitchingGenerator @Inject constructor(
    private val thisGraph: BindingGraph,
    private val options: ComponentGenerator.Options,
    private val lock: LockGenerator,
) : ComponentGenerator.Contributor {
    private val maxSlotsPerSwitch = options.maxSlotsPerSwitch
    private val storeInstanceObjectsInArray = options.storeInstanceObjectsInArray

    init {
        require(maxSlotsPerSwitch > 1 || maxSlotsPerSwitch == -1) {
            "maxSlotsPerSwitch should be at least 2 or -1 (infinity)"
        }
    }

    private var isUsed = false
    private val bindings = arrayListOf<Binding>()
    private val seen = hashSetOf<Binding>()

    fun requestSlot(forBinding: Binding): Int {
        isUsed = true
        if (forBinding in seen) {
            return bindings.indexOf(forBinding)
        }
        bindings += forBinding
        seen += forBinding
        return bindings.lastIndex
    }

    override fun generate(builder: TypeSpecBuilder) {
        if (!isUsed) return

        if (storeInstanceObjectsInArray) {
            generateCreateSwitch(builder)
            if (thisGraph.requiresSynchronizedAccess) {
                generateCacheMultiThreadSwitch(builder)
            } else {
                generateSingleThreadSwitch(builder)
            }
            return
        }
        builder.method(UniversalFactoryAccessMethodName) {
            modifiers(/*package-private*/)
            returnType(ClassName.OBJECT)
            parameter(ClassName.INT, "slot")
            if (maxSlotsPerSwitch > 1 && bindings.size > maxSlotsPerSwitch) {
                // Strategy with two-level-nested switches

                val outerSlotsCount = bindings.size / maxSlotsPerSwitch
                controlFlow("switch(slot / $maxSlotsPerSwitch)") {
                    for(outerSlot in 0 .. outerSlotsCount) {
                        val nestedFactoryName = "$UniversalFactoryAccessMethodName\$$outerSlot"
                        builder.method(nestedFactoryName) {
                            modifiers(Modifier.PRIVATE)
                            returnType(ClassName.OBJECT)
                            parameter(ClassName.INT, "slot")
                            controlFlow("switch(slot)") {
                                val startIndex = outerSlot * maxSlotsPerSwitch
                                var nestedSlot = 0
                                while((startIndex + nestedSlot) < bindings.size && nestedSlot < maxSlotsPerSwitch) {
                                    val binding = bindings[startIndex + nestedSlot]
                                    +buildExpression {
                                        +"case $nestedSlot: return "
                                        binding.generateAccess(
                                            builder = this,
                                            inside = thisGraph,
                                            isInsideInnerClass = false,
                                        )
                                    }
                                    ++nestedSlot
                                }
                                +"default: throw new %T()".formatCode(Names.AssertionError)
                            }
                        }
                        +buildExpression {
                            +"case $outerSlot: return %N(slot %L 100)".formatCode(nestedFactoryName, "%")
                        }
                    }
                    +"default: throw new %T()".formatCode(Names.AssertionError)
                }
            } else {
                // Single switch statement

                controlFlow("switch(slot)") {
                    bindings.forEachIndexed { slot, binding ->
                        +buildExpression {
                            +"case $slot: return "
                            binding.generateAccess(
                                builder = this,
                                inside = thisGraph,
                                isInsideInnerClass = false,
                            )
                        }
                    }
                    +"default: throw new %T()".formatCode(Names.AssertionError)
                }
            }
        }
    }

    private fun generateCreateSwitch(builder: TypeSpecBuilder) {
        if (!isUsed) return
        builder.method(UnscopedFactoryAccessMethodName) {
            modifiers(/*package-private*/)
            returnType(ClassName.OBJECT)
            parameter(ClassName.INT, "slot")
            if (maxSlotsPerSwitch > 1 && bindings.size > maxSlotsPerSwitch) {
                // Strategy with two-level-nested switches

                val outerSlotsCount = bindings.size / maxSlotsPerSwitch
                controlFlow("switch(slot / $maxSlotsPerSwitch)") {
                    for(outerSlot in 0 .. outerSlotsCount) {
                        val nestedFactoryName = "$UnscopedFactoryAccessMethodName\$$outerSlot"
                        builder.method(nestedFactoryName) {
                            modifiers(Modifier.PRIVATE)
                            returnType(ClassName.OBJECT)
                            parameter(ClassName.INT, "slot")
                            controlFlow("switch(slot)") {
                                val startIndex = outerSlot * maxSlotsPerSwitch
                                var nestedSlot = 0
                                while((startIndex + nestedSlot) < bindings.size && nestedSlot < maxSlotsPerSwitch) {
                                    val binding = bindings[startIndex + nestedSlot]
                                    +buildExpression {
                                        +"case $nestedSlot: return "
                                        binding.generateCreation(
                                            builder = this,
                                            inside = thisGraph,
                                            isInsideInnerClass = false,
                                        )
                                    }
                                    ++nestedSlot
                                }
                                +"default: throw new %T()".formatCode(Names.AssertionError)
                            }
                        }
                        +buildExpression {
                            +"case $outerSlot: return %N(slot %L 100)".formatCode(nestedFactoryName, "%")
                        }
                    }
                    +"default: throw new %T()".formatCode(Names.AssertionError)
                }
            } else {
                // Single switch statement

                controlFlow("switch(slot)") {
                    bindings.forEachIndexed { slot, binding ->
                        +buildExpression {
                            +"case $slot: return "
                            binding.generateCreation(
                                builder = this,
                                inside = thisGraph,
                                isInsideInnerClass = false,
                            )
                        }
                    }
                    +"default: throw new %T()".formatCode(Names.AssertionError)
                }
            }
        }
    }


    private fun generateSingleThreadSwitch(builder: TypeSpecBuilder) = with(builder) {
        val instanceArrayFieldName = "instances"
        field(ArrayTypeName.of(ClassName.OBJECT), instanceArrayFieldName) {
            modifiers(PRIVATE, FINAL)  // PRIVATE: only accessed via its accessor.
            initializer{
                +"new %T[${bindings.size}]".formatCode(ClassName.OBJECT)
            }
        }

        method("cache") {
            modifiers(/*package-private*/)
            val targetType = TypeVariableName.get("I")
            generic(targetType)
            returnType(targetType)
            parameter(ClassName.INT, "slot")

            +"%T local = this.%N[slot]".formatCode(ClassName.OBJECT, instanceArrayFieldName)
            controlFlow("if (local == null)") {
                if (options.enableThreadChecks) {
                    +"%T.assertThreadAccess()".formatCode(Names.ThreadAssertions)
                }
                +buildExpression {
                    +"local = this.%N(slot)".formatCode(UnscopedFactoryAccessMethodName)
                }
                +"this.%N[slot] = local".formatCode(instanceArrayFieldName)
            }
            +"return (%T) local".formatCode(targetType)
        }
    }

    private fun generateCacheMultiThreadSwitch(builder: TypeSpecBuilder) = with(builder) {
        val instanceCollectionType =
            ParameterizedTypeName.get(ClassName.get(AtomicReferenceArray::class.java),
                ClassName.OBJECT)

        val lockName = lock.name
        method("createInstanceArray") {
            modifiers(STATIC)
            returnType(instanceCollectionType)
            parameter(ClassName.INT, "size")
            +"%T[] instances = new %T[size]".formatCode(ClassName.OBJECT, ClassName.OBJECT)
            controlFlow("for (int index = 0; index < size; index++)") {
                +"instances[index] = new %T()".formatCode(lockName)
            }
            +"return new %T(instances)".formatCode(instanceCollectionType)
        }

        val instanceArrayFieldName = "instances"
        field(instanceCollectionType, instanceArrayFieldName) {
            modifiers(PRIVATE, FINAL)  // PRIVATE: only accessed via its accessor.
            initializer{
                +"createInstanceArray(${bindings.size})"
            }
        }


        method("cache") {
            modifiers(/*package-private*/)
            val i = TypeVariableName.get("I")
            generic(i)
            returnType(i)
            parameter(ClassName.INT, "slot")
            +"%T local = this.%N.get(slot)".formatCode(ClassName.OBJECT, instanceArrayFieldName)
            controlFlow("if (local instanceof %T)".formatCode(lockName)) {
                controlFlow("synchronized (local)") {
                    +"local = this.%N.get(slot)".formatCode(instanceArrayFieldName)
                    controlFlow("if (local instanceof %T)".formatCode(lockName)) {
                        +buildExpression {
                            +"local = this.%N(slot)".formatCode(UnscopedFactoryAccessMethodName)
                        }
                        +"this.%N.set(slot, local)".formatCode(instanceArrayFieldName)
                    }
                }
            }
            +"return (%T) local".formatCode(i)
        }
    }

    companion object {
        const val UniversalFactoryAccessMethodName = "switch\$\$access"
        const val ScopedFactoryAccessMethodName = "cache"
        const val UnscopedFactoryAccessMethodName = "switch\$\$create"
    }
}
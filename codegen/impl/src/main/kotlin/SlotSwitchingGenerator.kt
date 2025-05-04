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
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeVariableName
import com.yandex.yatagan.codegen.poetry.TypeSpecBuilder
import com.yandex.yatagan.codegen.poetry.buildExpression
import com.yandex.yatagan.core.graph.BindingGraph
import com.yandex.yatagan.core.graph.bindings.Binding
import com.yandex.yatagan.core.model.NodeModel
import com.yandex.yatagan.core.model.component1
import com.yandex.yatagan.core.model.component2
import com.yandex.yatagan.core.model.isEager
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
    private val instanceHolder: InstanceHolder,
    lock: LockGenerator,
) : ComponentGenerator.Contributor {
    private val instanceHolderName = instanceHolder.name
    private val lockName = lock.name
    private val maxSlotsPerSwitch = options.maxSlotsPerSwitch
    private val storeInstanceObjectsInArray = options.storeInstanceObjectsInArray

    init {
        require(maxSlotsPerSwitch > 1 || maxSlotsPerSwitch == -1) {
            "maxSlotsPerSwitch should be at least 2 or -1 (infinity)"
        }
    }

    private var isUsed = false
    private var isProceed = false
    private val bindings = arrayListOf<Binding>()
    private val seen = hashSetOf<Binding>()

    fun requestSlot(forBinding: Binding): Int {
        isUsed = true
        if (forBinding in seen) {
            return bindings.indexOf(forBinding)
        }
        check(!isProceed) { "slot requested after topological sorting" }
        bindings += forBinding
        seen += forBinding
        return bindings.lastIndex
    }

    private fun topologicalSort(bindings: List<Binding>): MutableList<Binding> {
        val sortedBindings = bindings

        val topologicalSortedBindings = mutableListOf<Binding>()/*.apply {
            addAll(sortedBindings)
        }*/

        val nodes = sortedBindings.map { it.target }
        val remainingNodes = HashSet(nodes)
        val seenNodes = HashSet<NodeModel>()

        fun visit(binding: Binding) {
            // It's important to use non-alias binding here to work with real dependencies for topological sorting

            if (remainingNodes.isEmpty()) {
                // No more work to do.
                return
            }

            for (dependency in binding.dependencies) {
                val (dependencyNode, kind) = dependency
                if (!kind.isEager) {
                    continue
                }

                if (!seenNodes.add(dependencyNode)) {
                    // graph may be invalid and contain loops
                    continue
                }

                visit(binding.owner.resolveBinding(dependencyNode))
            }

            val node = binding.target
            if (remainingNodes.remove(node)) {
                topologicalSortedBindings += binding
            }
        }

        for (binding in sortedBindings) visit(binding)

        check(remainingNodes.isEmpty()) { "Not reached" }

        check(sortedBindings.size == topologicalSortedBindings.size) { "Topological binding is lost" }

        return topologicalSortedBindings
    }

    fun getSortedSlot(requestedSlot: Int) : String {
       return thisGraph[GeneratorComponent].implementationClassName.canonicalName() + "._slot_$requestedSlot"
    }

    override fun generate(builder: TypeSpecBuilder) {
        if (!isUsed) return

        isProceed = true

        val sortedBindings = arrayListOf<Binding>().apply {
            addAll(bindings)
        }.run {
            topologicalSort(this)
        }

        val sortedNodes = sortedBindings.map { it.target }


        if (storeInstanceObjectsInArray) {
            generateCreateSwitch(builder, sortedBindings)
            if (thisGraph.requiresSynchronizedAccess) {
                generateCacheMultiThreadSwitch(builder, sortedBindings)
            } else {
                generateSingleThreadSwitch(builder, sortedBindings)
            }

            bindings.forEachIndexed { index, binding ->
                val instanceArrayFieldName = "_slot_$index"
                builder.field(ClassName.INT, instanceArrayFieldName) {
                    modifiers(PRIVATE, STATIC, FINAL)
                    val realSlot = sortedNodes.indexOf(binding.target)
                    initializer{
                        +"$realSlot"
                    }
                }
            }

            return
        }
        builder.method(UniversalFactoryAccessMethodName) {
            modifiers(/*package-private*/)
            returnType(ClassName.OBJECT)
            parameter(ClassName.INT, "slot")
            if (maxSlotsPerSwitch > 1 && sortedBindings.size > maxSlotsPerSwitch) {
                // Strategy with two-level-nested switches

                val outerSlotsCount = sortedBindings.size / maxSlotsPerSwitch
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
                                while((startIndex + nestedSlot) < sortedBindings.size && nestedSlot < maxSlotsPerSwitch) {
                                    val binding = sortedBindings[startIndex + nestedSlot]
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
                    sortedBindings.forEachIndexed { slot, binding ->
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

    private fun generateCreateSwitch(builder: TypeSpecBuilder, sortedBindings: List<Binding>) {
        if (!isUsed) return
        builder.method(UnscopedFactoryAccessMethodName) {
            modifiers(/*package-private*/)
            returnType(ClassName.OBJECT)
            parameter(ClassName.INT, "slot")
            if (maxSlotsPerSwitch > 1 && sortedBindings.size > maxSlotsPerSwitch) {
                // Strategy with two-level-nested switches

                val outerSlotsCount = sortedBindings.size / maxSlotsPerSwitch
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
                                while((startIndex + nestedSlot) < sortedBindings.size && nestedSlot < maxSlotsPerSwitch) {
                                    val binding = sortedBindings[startIndex + nestedSlot]
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
                    sortedBindings.forEachIndexed { slot, binding ->
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


    private fun generateSingleThreadSwitch(builder: TypeSpecBuilder, sortedBindings: List<Binding>) = with(builder) {
        val instanceArrayFieldName = "instances"
        field(ArrayTypeName.of(ClassName.OBJECT), instanceArrayFieldName) {
            modifiers(PRIVATE, FINAL)  // PRIVATE: only accessed via its accessor.
            initializer{
                +"new %T[${sortedBindings.size}]".formatCode(ClassName.OBJECT)
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

    private fun generateCacheMultiThreadSwitch(builder: TypeSpecBuilder, sortedBindings: List<Binding>) = with(builder) {
        val instanceCollectionType = ArrayTypeName.of(TypeName.OBJECT)
        val instanceType = TypeName.OBJECT
        val lockName = lockName
        method("createInstanceArray") {
            modifiers(STATIC)
            returnType(instanceCollectionType)
            parameter(ClassName.INT, "size")
            +"%T[] instances = new %T[size]".formatCode(instanceType, instanceType)
            controlFlow("for (int index = 0; index < size; index++)") {
                +"instances[index] = new %T()".formatCode(lockName)
            }
            +"return instances"
        }

        val instanceArrayFieldName = "instances"
        field(instanceCollectionType, instanceArrayFieldName) {
            modifiers(PRIVATE, FINAL)  // PRIVATE: only accessed via its accessor.
            initializer{
                +"createInstanceArray(${sortedBindings.size})"
            }
        }


        method("cache") {
            modifiers(/*package-private*/)
            val i = TypeVariableName.get("I")
            generic(i)
            returnType(i)
            parameter(ClassName.INT, "slot")
            +"%T instanceHolder = this.%N[slot]".formatCode(ClassName.OBJECT, instanceArrayFieldName)
            controlFlow("if (instanceHolder instanceof %T)".formatCode(lockName)) {
                controlFlow("synchronized (instanceHolder)") {
                    +"instanceHolder = this.%N[slot]".formatCode(instanceArrayFieldName)
                    controlFlow("if (instanceHolder instanceof %T)".formatCode(lockName)) {
                        +"%T local = this.%N(slot)".formatCode(ClassName.OBJECT, UnscopedFactoryAccessMethodName)
                        +"this.%N[slot] = new %T(local)".formatCode(instanceArrayFieldName, instanceHolder.name)
                        +"return (%T) local".formatCode(i)
                    }
                }
            }
            +"return (%T) ((%T)instanceHolder).mValue".formatCode(i, instanceHolder.name)
        }
    }

    companion object {
        const val UniversalFactoryAccessMethodName = "switch\$\$access"
        const val ScopedFactoryAccessMethodName = "cache"
        const val UnscopedFactoryAccessMethodName = "switch\$\$create"
    }
}
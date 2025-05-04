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

import com.squareup.javapoet.ClassName
import com.yandex.yatagan.codegen.poetry.TypeSpecBuilder
import com.yandex.yatagan.codegen.poetry.buildClass
import com.yandex.yatagan.core.graph.BindingGraph
import javax.inject.Inject
import javax.inject.Singleton
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PRIVATE
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Modifier.STATIC
import javax.lang.model.element.Modifier.VOLATILE

@Singleton
internal class InstanceHolder @Inject constructor(
    private val componentImplName: ClassName,
) : ComponentGenerator.Contributor {
    private var isUsed = false
    val name: ClassName = componentImplName.nestedClass("InstanceHolder")
        get() = field.also { isUsed = true }

    override fun generate(builder: TypeSpecBuilder) {
        if (!isUsed) return
        builder.nestedType {
            buildClass(name) {
                modifiers(PRIVATE, STATIC, FINAL)
                field(ClassName.OBJECT, "mValue") {
                    modifiers(FINAL)
                }
                constructor {
                    parameter(ClassName.OBJECT, "value")
                    +"mValue = value"
                }
            }
        }
    }
}
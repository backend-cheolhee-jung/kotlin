/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package kotlin.reflect.jvm

import org.jetbrains.kotlin.load.java.JvmAnnotationNames
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import org.jetbrains.kotlin.metadata.deserialization.MetadataVersion
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.serialization.deserialization.MemberDeserializer
import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.internal.EmptyContainerForLocal
import kotlin.reflect.jvm.internal.KFunctionImpl
import kotlin.reflect.jvm.internal.deserializeToDescriptor

/**
 * This is an experimental API. Given a class for a compiled Kotlin lambda or a function expression,
 * returns a [KFunction] instance providing introspection capabilities for that lambda or function expression and its parameters.
 * Not all features are currently supported, in particular [KCallable.call] and [KCallable.callBy] will fail at the moment.
 */
@ExperimentalReflectionOnLambdas
fun <R> Function<R>.reflect(): KFunction<R>? {
    val annotation = javaClass.getAnnotation(Metadata::class.java) ?: return null
    val data = annotation.data1.takeUnless(Array<String>::isEmpty) ?: return null
    val (nameResolver, proto) = JvmProtoBufUtil.readFunctionDataFrom(data, annotation.data2)
    val metadataVersion = MetadataVersion(
        annotation.metadataVersion,
        (annotation.extraInt and JvmAnnotationNames.METADATA_STRICT_VERSION_SEMANTICS_FLAG) != 0
    )

    val descriptor = deserializeToDescriptor(
        javaClass, proto, nameResolver, TypeTable(proto.typeTable), metadataVersion, MemberDeserializer::loadFunction
    )

    @Suppress("UNCHECKED_CAST")
    return KFunctionImpl(EmptyContainerForLocal, descriptor) as KFunction<R>
}

/**
 * This annotation marks the experimental kotlin-reflect API that allows to approximate a Kotlin lambda or a function expression instance
 * to a [KFunction] instance. The behavior of this API may be changed or the API may be removed completely in any further release.
 *
 * Any usage of a declaration annotated with `@ExperimentalReflectionOnLambdas` should be accepted either by
 * annotating that usage with the [OptIn] annotation, e.g. `@OptIn(ExperimentalReflectionOnLambdas::class)`,
 * or by using the compiler argument `-opt-in=kotlin.reflect.jvm.ExperimentalReflectionOnLambdas`.
 */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
@Retention(BINARY)
@Target(
    CLASS,
    ANNOTATION_CLASS,
    PROPERTY,
    FIELD,
    LOCAL_VARIABLE,
    VALUE_PARAMETER,
    CONSTRUCTOR,
    FUNCTION,
    PROPERTY_GETTER,
    PROPERTY_SETTER,
    TYPEALIAS
)
@MustBeDocumented
@SinceKotlin("1.5")
annotation class ExperimentalReflectionOnLambdas

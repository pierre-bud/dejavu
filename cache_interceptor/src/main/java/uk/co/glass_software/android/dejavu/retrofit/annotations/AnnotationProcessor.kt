/*
 * Copyright (C) 2017 Glass Software Ltd
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package uk.co.glass_software.android.dejavu.retrofit.annotations

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import uk.co.glass_software.android.dejavu.configuration.CacheConfiguration
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction.Operation
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction.Operation.Type.*
import uk.co.glass_software.android.dejavu.configuration.NetworkErrorProvider
import uk.co.glass_software.android.dejavu.retrofit.annotations.CacheException.Type.ANNOTATION

/**
 * Processes Retrofit annotations and generates a CacheInstruction if needed.
 *
 * @see uk.co.glass_software.android.dejavu.configuration.CacheInstruction
 */
internal class AnnotationProcessor<E>(private val cacheConfiguration: CacheConfiguration<E>)
        where  E : Exception,
               E : NetworkErrorProvider {

    private val logger = cacheConfiguration.logger

    /**
     * Processes the annotations on the Retrofit call and tries to convert them to a CacheInstruction
     * if applicable.
     *
     * @param annotations the calls annotations as provided by the Retrofit call adapter.
     * @param rxType the type of RxJava operation for this call
     * @param responseClass the target response class
     *
     * @return the processed CacheInstruction if applicable
     */
    @Throws(CacheException::class)
    fun process(annotations: Array<Annotation>,
                rxType: RxType,
                responseClass: Class<*>): CacheInstruction? {
        var instruction: CacheInstruction? = null

        annotations.forEach { annotation ->
            when (annotation) {
                is Cache -> CACHE
                is DoNotCache -> DO_NOT_CACHE
                is Invalidate -> INVALIDATE
                is Refresh -> REFRESH
                is Offline -> OFFLINE
                is Clear -> CLEAR
                is ClearAll -> CLEAR_ALL
                else -> null
            }?.let { operation ->
                instruction = getInstruction(
                        instruction,
                        rxType,
                        responseClass,
                        operation,
                        annotation
                )
            }
        }

        return instruction
    }

    @Throws(CacheException::class)
    private fun CacheException.logAndThrow() {
        logger.e(this, this)
        throw this
    }

    /**
     * Returns a cache instructions for the given annotation and checks for duplicate annotations
     * on the same call.
     *
     * @param currentInstruction the previous existing annotation if found, to detect duplicates
     * @param rxType the RxJava type
     * @param responseClass the targeted response class for this call
     * @param foundOperation the operation type associated with the given annotation
     * @param annotation the annotation being processed
     * @return the processed cache instruction for the given annotation
     */
    @Throws(CacheException::class)
    private fun getInstruction(currentInstruction: CacheInstruction?,
                               rxType: RxType,
                               responseClass: Class<*>,
                               foundOperation: Operation.Type,
                               annotation: Annotation): CacheInstruction? {
        if (currentInstruction != null) {
            CacheException(
                    ANNOTATION,
                    "More than one cache annotation defined for method returning"
                            + " ${rxType.getTypedName(responseClass)}, found ${foundOperation.annotationName}"
                            + " after existing annotation ${currentInstruction.operation.type.annotationName}."
                            + " Only one annotation can be used for this method."
            ).logAndThrow()
        }

        return when (annotation) {
            is Cache -> CacheInstruction(
                    responseClass,
                    Operation.Expiring.Cache(
                            annotation.durationInMillis.let { if (it == -1L) cacheConfiguration.cacheDurationInMillis else it },
                            annotation.connectivityTimeoutInMillis.let { if (it == -1L) cacheConfiguration.connectivityTimeoutInMillis else it },
                            annotation.freshOnly,
                            annotation.mergeOnNextOnError.value,
                            annotation.encrypt.value,
                            annotation.compress.value
                    )
            )

            is Invalidate -> CacheInstruction(
                    annotation.typeToInvalidate.java,
                    Operation.Invalidate
            )

            is Refresh -> CacheInstruction(
                    responseClass,
                    Operation.Expiring.Refresh(
                            annotation.durationInMillis.let { if (it == -1L) cacheConfiguration.cacheDurationInMillis else it },
                            annotation.connectivityTimeoutInMillis.let { if (it == -1L) cacheConfiguration.connectivityTimeoutInMillis else it },
                            annotation.freshOnly,
                            annotation.mergeOnNextOnError.value
                    )
            )

            is Offline -> CacheInstruction(
                    responseClass,
                    Operation.Expiring.Offline(
                            annotation.freshOnly,
                            annotation.mergeOnNextOnError.value
                    )
            )

            is Clear -> {
                CacheInstruction(
                        annotation.typeToClear.java,
                        Operation.Clear(
                                annotation.typeToClear.java,
                                annotation.clearOldEntriesOnly
                        )
                )
            }

            is ClearAll -> {
                CacheInstruction(
                        responseClass,
                        Operation.Clear(
                                null,
                                annotation.clearOldEntriesOnly
                        )
                )
            }

            is DoNotCache -> CacheInstruction(
                    responseClass,
                    Operation.DoNotCache
            )

            else -> null
        }
    }

    enum class RxType(val rxClass: Class<*>) {
        OBSERVABLE(Observable::class.java),
        SINGLE(Single::class.java),
        COMPLETABLE(Completable::class.java);

        fun getTypedName(responseClass: Class<*>) =
                if (this == COMPLETABLE) rxClass.simpleName
                else "${rxClass.simpleName}<${responseClass.simpleName}>"
    }

}

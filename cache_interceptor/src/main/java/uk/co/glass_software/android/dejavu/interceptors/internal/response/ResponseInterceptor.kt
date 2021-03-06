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

package uk.co.glass_software.android.dejavu.interceptors.internal.response

import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.functions.Predicate
import io.reactivex.subjects.PublishSubject
import uk.co.glass_software.android.boilerplate.core.utils.log.Logger
import uk.co.glass_software.android.dejavu.configuration.CacheConfiguration
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction.Operation.Expiring
import uk.co.glass_software.android.dejavu.configuration.NetworkErrorProvider
import uk.co.glass_software.android.dejavu.interceptors.internal.cache.token.CacheToken
import uk.co.glass_software.android.dejavu.response.CacheMetadata
import uk.co.glass_software.android.dejavu.response.ResponseWrapper
import uk.co.glass_software.android.dejavu.retrofit.annotations.CacheException
import java.util.*

/**
 * Intercepts the response wrapper returned from the error and cache interceptors and returns the actual
 * response while adding the metadata if possible. Only response classes implementing CacheMetadata.Holder
 * will receive metadata and can be used with the mergeOnNextOnError directive, otherwise an error is emitted.
 *
 * This class also deals with filtering the returned responses according to the
 * filterFinal/freshOnly/allowNonFinalForSingle directives set for this call globally or specifically.
 *
 * @param logger the logger
 * @param dateFactory provides a date for a given timestamp or the current date with no argument
 * @param configuration the cache configuration
 * @param metadataSubject the subject used to emit the current response's metadata (exposed as an Observable on DejaVu)
 * @param instructionToken the instruction cache token
 * @param isSingle whether or not the response will be delivered via a Single
 * @param isCompletable whether or not the method returns a Completable
 * @param start the time the call started
 * @param mergeOnNextOnError whether or not any exception should be added to the metadata on an empty response and delivered via onNext. This is only applied if the response implements CacheMetadata.Holder. An error is emitted otherwise.
 */
internal class ResponseInterceptor<E>(private val logger: Logger,
                                      private val dateFactory: (Long?) -> Date,
                                      private val emptyResponseFactory: EmptyResponseFactory<E>,
                                      private val configuration: CacheConfiguration<E>,
                                      private val metadataSubject: PublishSubject<CacheMetadata<E>>,
                                      private val instructionToken: CacheToken,
                                      private val isSingle: Boolean,
                                      private val isCompletable: Boolean,
                                      private val start: Long,
                                      private val mergeOnNextOnError: Boolean)
    : ObservableTransformer<ResponseWrapper<E>, Any>
        where E : Exception,
              E : NetworkErrorProvider {

    private val responseFilter = Predicate<ResponseWrapper<E>> {
        val status = it.metadata.cacheToken.status
        val operation = instructionToken.instruction.operation

        if (operation is Expiring) {
            val filterFresh = status.isFresh || !operation.freshOnly
            val filterFinal = status.isFinal || !operation.filterFinal

            if (filterFresh && filterFinal) {
                if (isSingle) {
                    status.isFinal || (configuration.allowNonFinalForSingle && !operation.filterFinal)
                } else true
            } else false
        } else true
    }

    /**
     * Composes an Observable call.
     *
     * @param upstream the Observable to compose
     * @return the composed Observable
     */
    override fun apply(upstream: Observable<ResponseWrapper<E>>) =
            upstream.filter(responseFilter)
                    .switchIfEmpty(emptyResponseFactory.emptyResponseWrapperSingle(instructionToken).toObservable())
                    .flatMap(this::intercept)!!

    /**
     * Converts the ResponseWrapper into the expected response with added cache metadata if possible.
     *
     * @param wrapper the response wrapper returned by the error and cache interceptors
     *
     * @return an Observable emitting the expected response with associated metadata or an error if the empty response could not be created.
     */
    private fun intercept(wrapper: ResponseWrapper<E>): Observable<Any> {
        val responseClass = wrapper.responseClass
        val operation = wrapper.metadata.cacheToken.instruction.operation
        val mergeOnNextOnError = (operation as? Expiring)?.mergeOnNextOnError
                ?: this.mergeOnNextOnError

        val metadata = wrapper.metadata.copy(
                callDuration = wrapper.metadata.callDuration.copy(
                        total = (dateFactory(null).time - start).toInt()
                )
        )

        val response = wrapper.response ?: emptyResponseFactory.create(
                mergeOnNextOnError,
                responseClass
        )

        metadataSubject.onNext(metadata)

        return if (response == null) {
            checkForError(
                    responseClass,
                    mergeOnNextOnError
            )
                    ?: metadata.exception
                    ?: IllegalStateException("Something went wrong")
        } else {
            addMetadataIfPossible(
                    response,
                    responseClass,
                    metadata,
                    mergeOnNextOnError
            ) ?: response
        }.let {
            when {
                it is Throwable -> {
                    logger.d(this, "Returning error: $it")
                    Observable.error<Any>(it)
                }
                isCompletable && metadata.exception != null -> {
                    if (metadata.exception.cause is EmptyResponseFactory.EmptyResponseException) {
                        logger.d(this, "Returning empty response for Completable: $metadata")
                        Observable.empty<Any>()
                    } else {
                        logger.d(this, "Returning exception for Completable: $metadata")
                        Observable.error<Any>(metadata.exception)
                    }
                }
                else -> {
                    logger.d(this, "Returning response: $metadata")
                    Observable.just<Any>(response)
                }
            }
        }
    }

    /**
     * Adds metadata to any response implementing CacheMetadata.Holder, or throws an exception if the
     * mergeOnNextOnError directive is used but the response does not implement CacheMetadata.Holder.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addMetadataIfPossible(response: Any,
                                      responseClass: Class<*>,
                                      metadata: CacheMetadata<E>,
                                      mergeOnNextOnError: Boolean): CacheException? {
        return if (!isCompletable) {
            val holder = response as? CacheMetadata.Holder<E>
            if (holder == null) {
                checkForError(
                        responseClass,
                        mergeOnNextOnError
                )
            } else {
                holder.metadata = metadata
                null
            }
        } else null
    }

    /**
     * Returns an exception if the mergeOnNextOnError directive is set to true but the
     * response class does not implement CacheMetadata.Holder.
     *
     * @param responseClass the target response class
     * @param mergeOnNextOnError whether or not any exception should be added to the metadata on an empty response and delivered via onNext. This is only applied if the response implements CacheMetadata.Holder. An exception is thrown otherwise.
     */
    private fun checkForError(responseClass: Class<*>,
                              mergeOnNextOnError: Boolean): CacheException? {
        val message = "Could not add cache metadata to response '${responseClass.simpleName}'." +
                " If you want to enable metadata for this class, it needs extend the" +
                " 'CacheMetadata.Holder' interface." +
                " The 'mergeOnNextOnError' directive will be cause an exception to be thrown for classes" +
                " that do not support cache metadata."

        return if (!isCompletable && mergeOnNextOnError)
            CacheException(CacheException.Type.METADATA, message)
        else null
    }
}

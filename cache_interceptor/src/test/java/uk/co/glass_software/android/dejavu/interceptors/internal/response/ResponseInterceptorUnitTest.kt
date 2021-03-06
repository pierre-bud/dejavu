package uk.co.glass_software.android.dejavu.interceptors.internal.response

import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.observers.TestObserver
import io.reactivex.subjects.PublishSubject
import org.junit.Before
import org.junit.Test
import uk.co.glass_software.android.dejavu.configuration.CacheConfiguration
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction.Operation
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction.Operation.Expiring
import uk.co.glass_software.android.dejavu.interceptors.internal.cache.token.CacheStatus
import uk.co.glass_software.android.dejavu.interceptors.internal.cache.token.CacheStatus.EMPTY
import uk.co.glass_software.android.dejavu.interceptors.internal.error.Glitch
import uk.co.glass_software.android.dejavu.response.CacheMetadata
import uk.co.glass_software.android.dejavu.response.ResponseWrapper
import uk.co.glass_software.android.dejavu.retrofit.annotations.CacheException
import uk.co.glass_software.android.dejavu.test.*
import uk.co.glass_software.android.dejavu.test.network.model.TestResponse
import java.util.*

class ResponseInterceptorUnitTest {

    private lateinit var mockEmptyResponseFactory: EmptyResponseFactory<Glitch>
    private lateinit var mockConfiguration: CacheConfiguration<Glitch>
    private lateinit var mockMetadataSubject: PublishSubject<CacheMetadata<Glitch>>
    private lateinit var mockEmptyException: Glitch

    private val start = 1234L
    private val mockDateFactory: (Long?) -> Date = { Date(4321L) }
    private var num = 0

    @Before
    fun setUp() {
        mockEmptyResponseFactory = mock()
        mockMetadataSubject = mock()
    }

    @Test
    fun testApplyObservable() {
        testApply(false, false)
    }

    @Test
    fun testApplySingle() {
        testApply(true, false)
    }

    @Test
    fun testApplyCompletable() {
        testApply(false, true)
    }

    private fun testApply(isSingle: Boolean,
                          isCompletable: Boolean) {
        operationAndStatusSequence { (operation, cacheStatus) ->
            trueFalseSequence { hasResponse ->
                trueFalseSequence { isEmptyObservable ->
                    trueFalseSequence { allowNonFinalForSingle ->
                        trueFalseSequence { mergeOnNextOnError ->
                            sequenceOf(TestResponse::class.java, String::class.java).forEach { responseClass ->
                                testApplyWithVariants(
                                        responseClass,
                                        isSingle,
                                        isCompletable,
                                        hasResponse,
                                        isEmptyObservable,
                                        allowNonFinalForSingle,
                                        mergeOnNextOnError,
                                        cacheStatus,
                                        operation
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getExpiringDescription(operation: Expiring) =
            ",\noperation.filterFinal = ${operation.filterFinal}," +
                    "\noperation.freshOnly = ${operation.freshOnly}," +
                    "\noperation.mergeOnNextOnError = ${operation.mergeOnNextOnError},"

    private fun testApplyWithVariants(responseClass: Class<*>,
                                      isSingle: Boolean,
                                      isCompletable: Boolean,
                                      hasResponse: Boolean,
                                      isEmptyUpstreamObservable: Boolean,
                                      allowNonFinalForSingle: Boolean,
                                      mergeOnNextOnError: Boolean,
                                      cacheStatus: CacheStatus,
                                      operation: Operation) {
        val context = "Iteration ${num++}" +
                "\nResponse class = ${responseClass.simpleName}," +
                "\nOperation = ${operation.type}," +
                "\nCacheStatus = $cacheStatus," +
                "\nisSingle = $isSingle," +
                "\nisCompletable = $isCompletable," +
                "\nhasResponse = $hasResponse," +
                "\nisEmptyUpstreamObservable = $isEmptyUpstreamObservable," +
                "\nallowNonFinalForSingle = $allowNonFinalForSingle," +
                "\nconf.mergeOnNextOnError = $mergeOnNextOnError" +
                (if (operation is Expiring) getExpiringDescription(operation) else "")

        setUp() //reset mocks

        val mockInstructionToken = instructionToken(operation)
        mockEmptyException = Glitch(EmptyResponseFactory.EmptyResponseException)

        val isValid = if (operation is Expiring) {
            val filterFresh = cacheStatus.isFresh || !operation.freshOnly
            val filterFinal = cacheStatus.isFinal || !operation.filterFinal

            if (filterFresh && filterFinal) {
                if (isSingle) {
                    cacheStatus.isFinal || (allowNonFinalForSingle && !operation.filterFinal)
                } else true
            } else false
        } else true

        val isEmptyUpstream = !hasResponse || isEmptyUpstreamObservable
        val expectEmpty = isEmptyUpstream || !isValid

        val mockUpstreamMetadata = CacheMetadata(
                mockInstructionToken.copy(status = if (isEmptyUpstream) EMPTY else cacheStatus),
                if (isEmptyUpstream) mockEmptyException else null
        )

        val mockResponse = if (responseClass == String::class.java) "" else TestResponse()

        val mockUpstreamWrapper = ResponseWrapper(
                responseClass,
                if (isEmptyUpstream) null else mockResponse,
                mockUpstreamMetadata
        )

        val mockUpstreamObservable = if (isEmptyUpstreamObservable)
            Observable.empty<ResponseWrapper<Glitch>>()
        else
            Observable.just(mockUpstreamWrapper)

        mockConfiguration = CacheConfiguration(
                mock(),
                mock(),
                mock(),
                mock(),
                mock(),
                true,
                true,
                true,
                mergeOnNextOnError,
                allowNonFinalForSingle,
                5,
                5,
                5,
                {_,_-> false }
        )

        val target = ResponseInterceptor(
                mock(),
                mockDateFactory,
                mockEmptyResponseFactory,
                mockConfiguration,
                mockMetadataSubject,
                mockInstructionToken,
                isSingle,
                isCompletable,
                start,
                mergeOnNextOnError
        )


        val expectedMetadata = mockUpstreamMetadata.copy(
                cacheToken = mockInstructionToken.copy(status = if (expectEmpty) EMPTY else cacheStatus),
                exception = if (expectEmpty) mockEmptyException else null,
                callDuration = CacheMetadata.Duration(0, 0, 4321 - 1234)
        )

        val mockEmptyResponseWrapper = mockUpstreamWrapper.copy(
                response = null,
                metadata = expectedMetadata
        )

        val expectedMergeOnNextOnError = (operation as? Expiring)?.mergeOnNextOnError
                ?: mergeOnNextOnError

        whenever(mockEmptyResponseFactory.emptyResponseWrapperSingle(
                eq(mockInstructionToken)
        )).thenReturn(Single.just(mockEmptyResponseWrapper))

        val mockEmptyResponse = if (responseClass == String::class.java) "" else TestResponse()

        whenever(mockEmptyResponseFactory.create(
                eq(true),
                eq(responseClass)
        )).thenReturn(mockEmptyResponse)

        whenever(mockEmptyResponseFactory.create(
                eq(false),
                eq(responseClass)
        )).thenReturn(null)

        val testObserver = TestObserver<Any>()

        target.apply(mockUpstreamObservable).subscribe(testObserver)

        verifyWithContext(
                mockMetadataSubject,
                context
        ).onNext(eq(expectedMetadata))

        if (isValid) {
            if (expectEmpty) {
                verifyCheckForError(
                        isCompletable,
                        responseClass,
                        expectedMergeOnNextOnError,
                        expectEmpty,
                        testObserver,
                        context
                )
            } else {
                verifyAddMetadataIfPossible(
                        responseClass,
                        isCompletable,
                        expectedMergeOnNextOnError,
                        expectEmpty,
                        expectedMetadata,
                        testObserver,
                        context
                )
            }
        } else {
            verifyCheckForError(
                    isCompletable,
                    responseClass,
                    expectedMergeOnNextOnError,
                    expectEmpty,
                    testObserver,
                    context
            )
        }
    }

    private fun verifyCheckForError(isCompletable: Boolean,
                                    responseClass: Class<*>,
                                    expectedMergeOnNextOnError: Boolean,
                                    expectedEmpty: Boolean,
                                    testObserver: TestObserver<Any>,
                                    context: String) {
        val actualMetadata = (testObserver.values().firstOrNull() as? TestResponse)?.metadata

        val expectedCacheException = CacheException(
                CacheException.Type.METADATA,
                "Could not add cache metadata to response '${responseClass.simpleName}'." +
                        " If you want to enable metadata for this class, it needs extend the" +
                        " 'CacheMetadata.Holder' interface." +
                        " The 'mergeOnNextOnError' directive will be cause an exception to be thrown for classes" +
                        " that do not support cache metadata."
        )

        if (expectedEmpty && isCompletable) {
            //nothing to check
        } else {
            val expectedException = if (expectedEmpty && responseClass == TestResponse::class.java)
                mockEmptyException
            else expectedCacheException

            if (expectedMergeOnNextOnError) {
                verifyExpectedException(
                        isCompletable,
                        actualMetadata,
                        expectedException,
                        testObserver,
                        context
                )
            } else {
                verifyExpectedException(
                        isCompletable,
                        actualMetadata,
                        mockEmptyException,
                        testObserver,
                        context
                )
            }
        }
    }

    private fun verifyExpectedException(isCompletable: Boolean,
                                        metadata: CacheMetadata<Glitch>?,
                                        expectedException: Exception,
                                        testObserver: TestObserver<Any>,
                                        context: String) {
        val actualException = if (metadata == null || isCompletable)
            testObserver.errors().firstOrNull()
        else metadata.exception

        assertNotNullWithContext(
                actualException,
                "Expected an exception that wasn't thrown",
                context
        )

        assertEqualsWithContext(
                expectedException.javaClass,
                actualException!!.javaClass,
                "Could not find the expected exception on the returned Observable: different type",
                context
        )

        assertEqualsWithContext(
                expectedException.message,
                actualException.message,
                "Could not find the expected exception on the returned Observable: different message",
                context
        )
    }

    private fun verifyAddMetadataIfPossible(responseClass: Class<*>,
                                            isCompletable: Boolean,
                                            expectedMergeOnNextOnError: Boolean,
                                            expectedEmpty: Boolean,
                                            expectedMetadata: CacheMetadata<Glitch>,
                                            testObserver: TestObserver<Any>,
                                            context: String) {
        if (!isCompletable) {
            val actualMetadata = (testObserver.values().firstOrNull() as? TestResponse)?.metadata

            if (responseClass == String::class.java) {
                if (expectedMergeOnNextOnError) {
                    verifyCheckForError(
                            isCompletable,
                            responseClass,
                            expectedMergeOnNextOnError,
                            expectedEmpty,
                            testObserver,
                            context
                    )
                }
            } else {
                val result = testObserver.values().first()

                assertNotNullWithContext(
                        result,
                        "Returned response was null, should have returned a valid wrapper",
                        context
                )

                assertEqualsWithContext(
                        expectedMetadata,
                        actualMetadata,
                        "Returned response metadata didn't match",
                        context
                )
            }
        }
    }

}

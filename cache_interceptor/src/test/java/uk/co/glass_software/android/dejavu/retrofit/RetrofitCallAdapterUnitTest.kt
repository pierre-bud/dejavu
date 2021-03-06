package uk.co.glass_software.android.dejavu.retrofit

import com.nhaarman.mockitokotlin2.*
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.Before
import org.junit.Test
import retrofit2.Call
import retrofit2.CallAdapter
import uk.co.glass_software.android.boilerplate.core.utils.log.Logger
import uk.co.glass_software.android.dejavu.DejaVu.Companion.DejaVuHeader
import uk.co.glass_software.android.dejavu.configuration.CacheConfiguration
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction.Operation.Expiring
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction.Operation.Expiring.Cache
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction.Operation.Expiring.Refresh
import uk.co.glass_software.android.dejavu.configuration.CacheInstructionSerialiser
import uk.co.glass_software.android.dejavu.interceptors.DejaVuInterceptor
import uk.co.glass_software.android.dejavu.interceptors.DejaVuTransformer
import uk.co.glass_software.android.dejavu.interceptors.internal.cache.serialisation.RequestMetadata
import uk.co.glass_software.android.dejavu.interceptors.internal.error.Glitch
import uk.co.glass_software.android.dejavu.retrofit.RetrofitCallAdapterFactory.Companion.DEFAULT_URL
import uk.co.glass_software.android.dejavu.retrofit.annotations.AnnotationProcessor.RxType.*
import uk.co.glass_software.android.dejavu.test.assertEqualsWithContext
import uk.co.glass_software.android.dejavu.test.assertTrueWithContext
import uk.co.glass_software.android.dejavu.test.instructionToken
import uk.co.glass_software.android.dejavu.test.network.model.TestResponse
import uk.co.glass_software.android.dejavu.test.verifyWithContext
import java.lang.reflect.Type

class RetrofitCallAdapterUnitTest {

    private lateinit var mockDejaVuFactory: DejaVuInterceptor.Factory<Glitch>
    private lateinit var mockLogger: Logger
    private lateinit var mockRxCallAdapter: CallAdapter<Any, Any>
    private lateinit var mockCall: Call<Any>
    private lateinit var mockCacheInstructionSerialiser: CacheInstructionSerialiser
    private lateinit var mockRequest: Request
    private lateinit var mockDejaVuTransformer: DejaVuTransformer
    private lateinit var mockTestResponse: TestResponse
    private lateinit var requestMetadata: RequestMetadata.UnHashed
    private lateinit var mockRequestBodyConverter: (Request) -> String?
    private lateinit var configuration: CacheConfiguration<Glitch>

    private val responseClass = TestResponse::class.java
    private val mockMethodDescription = "mockMethodDescription"
    private val mockHeader = "mockHeader"
    private val mockInstruction = instructionToken(Cache()).instruction
    private val mockHeaderInstruction = instructionToken(Refresh()).instruction

    @Before
    fun setUp() {
        mockDejaVuFactory = mock()
        mockLogger = mock()
        mockRxCallAdapter = mock()
        mockCall = mock()
        mockCacheInstructionSerialiser = mock()
        mockRequest = mock()
        mockDejaVuTransformer = mock()
        mockTestResponse = mock()
        mockRequestBodyConverter = mock()
    }


    private fun getTarget(hasInstruction: Boolean,
                          hasHeader: Boolean,
                          cachePredicate: (responseClass: Class<*>, metadata: RequestMetadata) -> Boolean,
                          isHeaderDeserialisationSuccess: Boolean,
                          isHeaderDeserialisationException: Boolean): RetrofitCallAdapter<Glitch> {
        whenever(mockCall.request()).thenReturn(mockRequest)

        if (hasHeader) {
            whenever(mockRequest.header(eq(DejaVuHeader))).thenReturn(mockHeader)
            whenever(mockCacheInstructionSerialiser.deserialise(eq(mockHeader))).apply {
                if (isHeaderDeserialisationException)
                    thenThrow(RuntimeException("error"))
                else
                    thenReturn(if (isHeaderDeserialisationSuccess) mockHeaderInstruction else null)
            }
        }

        configuration = CacheConfiguration(
                mock(),
                mock(),
                mock(),
                mock(),
                mock(),
                true,
                true,
                true,
                true,
                true,
                1234,
                2345,
                3456,
                cachePredicate
        )

        return RetrofitCallAdapter(
                configuration,
                responseClass,
                mockDejaVuFactory,
                mockCacheInstructionSerialiser,
                mockRequestBodyConverter,
                mockLogger,
                mockMethodDescription,
                if (hasInstruction) mockInstruction else null,
                mockRxCallAdapter
        )
    }

    private fun testAdapt(hasInstruction: Boolean,
                          hasHeader: Boolean,
                          cachePredicate: (responseClass: Class<*>, metadata: RequestMetadata) -> Boolean,
                          isHeaderDeserialisationSuccess: Boolean,
                          isHeaderDeserialisationException: Boolean) {
        sequenceOf(
                null,
                OBSERVABLE,
                SINGLE,
                COMPLETABLE
        ).forEach { rxType ->
            setUp() //reset mocks

            val target = getTarget(
                    hasInstruction,
                    hasHeader,
                    cachePredicate,
                    isHeaderDeserialisationSuccess,
                    isHeaderDeserialisationException
            )

            val rxCall = when (rxType) {
                OBSERVABLE -> Observable.just(mockTestResponse)
                SINGLE -> Single.just(mockTestResponse)
                COMPLETABLE -> Completable.complete()
                else -> mockTestResponse
            }

            whenever(mockRxCallAdapter.adapt(eq(mockCall))).thenReturn(rxCall)

            val mockUrl = mock<HttpUrl>()
            whenever(mockRequest.url()).thenReturn(mockUrl)
            whenever(mockUrl.toString()).thenReturn(DEFAULT_URL)

            val mockBodyString = "body"
            val mockBody = mock<RequestBody>()
            whenever(mockRequest.body()).thenReturn(mockBody)
            whenever(mockBody.toString()).thenReturn(mockBodyString)

            whenever(mockRequestBodyConverter.invoke(eq(mockRequest))).thenReturn(mockBodyString)

            requestMetadata = RequestMetadata.UnHashed(DEFAULT_URL, mockBodyString)
            val hasDefaultAdaptation = cachePredicate(responseClass, requestMetadata)
            val usesDefaultAdaptation = hasDefaultAdaptation && !hasHeader && !hasInstruction

            if (rxType != null
                    && (hasInstruction
                            || (hasHeader && isHeaderDeserialisationSuccess)
                            || hasDefaultAdaptation)) {

                whenever(mockDejaVuFactory.create(
                        any(),
                        eq(requestMetadata)
                )).thenReturn(mockDejaVuTransformer)

                when (rxType) {
                    OBSERVABLE -> whenever(mockDejaVuTransformer.apply(eq(rxCall as Observable<Any>))).thenReturn(rxCall.map { it })
                    SINGLE -> whenever(mockDejaVuTransformer.apply(eq(rxCall as Single<Any>))).thenReturn(rxCall.map { it })
                    COMPLETABLE -> whenever(mockDejaVuTransformer.apply(eq(rxCall as Completable))).thenReturn(rxCall.andThen(Completable.complete()))
                }
            }

            val actualAdapted = target.adapt(mockCall)

            val context = "For rxType == $rxType"

            if (rxType == null) {
                verify(mockDejaVuFactory, never()).create(
                        any(),
                        any()
                )

                assertEqualsWithContext(
                        mockTestResponse,
                        actualAdapted,
                        "Adapted value should be the mocked TestResponse",
                        context
                )
            } else if ((hasHeader && isHeaderDeserialisationSuccess) || hasInstruction || usesDefaultAdaptation) {
                val argumentCaptor = argumentCaptor<CacheInstruction>()
                verifyWithContext(
                        mockDejaVuFactory,
                        "$context: DejaVuFactory should have been called with the default CacheInstruction, using the cache predicate"
                ).create(
                        argumentCaptor.capture(),
                        eq(requestMetadata)
                )

                val capturedInstruction = argumentCaptor.firstValue//TODO
                val subContext = "$context: Returned cache predicate CacheInstruction was incorrect"

                assertEqualsWithContext(
                        responseClass,
                        capturedInstruction.responseClass,
                        "Response class didn't match",
                        subContext
                )

                if (usesDefaultAdaptation) {
                    val capturedOperation = capturedInstruction.operation as Expiring

                    assertEqualsWithContext(
                            configuration.cacheDurationInMillis,
                            capturedOperation.durationInMillis,
                            "durationInMillis didn't match",
                            subContext
                    )

                    assertEqualsWithContext(
                            configuration.connectivityTimeoutInMillis,
                            capturedOperation.connectivityTimeoutInMillis,
                            "connectivityTimeoutInMillis didn't match",
                            subContext
                    )

                    assertEqualsWithContext(
                            false,
                            capturedOperation.freshOnly,
                            "freshOnly didn't match",
                            subContext
                    )

                    assertEqualsWithContext(
                            configuration.mergeOnNextOnError,
                            capturedOperation.mergeOnNextOnError,
                            "mergeOnNextOnError didn't match",
                            subContext
                    )

                    assertEqualsWithContext(
                            configuration.encrypt,
                            capturedOperation.encrypt,
                            "encrypt didn't match",
                            subContext
                    )

                    assertEqualsWithContext(
                            configuration.compress,
                            capturedOperation.compress,
                            "compress didn't match",
                            subContext
                    )

                    assertEqualsWithContext(
                            false,
                            capturedOperation.filterFinal,
                            "filterFinal didn't match",
                            subContext
                    )
                } else {
                    val expectedInstruction = when {
                        hasHeader && isHeaderDeserialisationSuccess -> mockHeaderInstruction
                        hasInstruction -> mockInstruction
                        else -> null
                    }

                    assertEqualsWithContext(
                            expectedInstruction,
                            capturedInstruction,
                            subContext
                    )
                }

                when (rxType) {
                    OBSERVABLE -> assertTrueWithContext(
                            Observable::class.java.isAssignableFrom(actualAdapted.javaClass),
                            "Adapted result should be of type Observable",
                            context
                    )

                    SINGLE -> assertTrueWithContext(
                            Single::class.java.isAssignableFrom(actualAdapted.javaClass),
                            "Adapted result should be of type Single",
                            context
                    )

                    COMPLETABLE -> assertTrueWithContext(
                            Completable::class.java.isAssignableFrom(actualAdapted.javaClass),
                            "Adapted result should be of type Completable",
                            context
                    )
                }
            } else {
                assertEqualsWithContext(
                        rxCall,
                        actualAdapted,
                        "The given call should not have been adapted",
                        context
                )
            }
        }
    }

    @Test
    fun testResponseType() {
        val mockResponseType = mock<Type>()
        whenever(mockRxCallAdapter.responseType()).thenReturn(mockResponseType)

        assertEqualsWithContext(
                mockResponseType,
                getTarget(
                        false,
                        false,
                        { _, _ -> false },
                        false,
                        false
                ).responseType(),
                "Response type didn't match"
        )
    }

    @Test
    fun testAdaptWithNoInstructionAndHeader() {
        testAdapt(
                false,
                true,
                { _, _ -> false },
                true,
                false
        )
    }

    @Test
    fun testAdaptWithNoInstructionAndHeaderDeserialisationReturnsNull() {
        testAdapt(
                false,
                true,
                { _, _ -> false },
                false,
                false
        )
    }

    @Test
    fun testAdaptWithNoInstructionAndHeaderDeserialisationThrowsException() {
        testAdapt(
                false,
                true,
                { _, _ -> false },
                false,
                true
        )
    }

    @Test
    fun testAdaptWithInstructionAndNoHeader() {
        testAdapt(
                true,
                false,
                { _, _ -> false },
                true,
                false
        )
    }

    @Test
    fun testAdaptWithInstructionAndHeaderDeserialisationReturnsNull() {
        testAdapt(
                true,
                true,
                { _, _ -> false },
                false,
                false
        )
    }

    @Test
    fun testAdaptWithInstructionAndHeaderDeserialisationThrowsException() {
        testAdapt(
                true,
                true,
                { _, _ -> false },
                false,
                true
        )
    }

    @Test
    fun testAdaptWithNoInstructionAndNoHeaderAndCacheByDefaultTrue() {
        testAdapt(
                false,
                false,
                { _, _ -> true },
                false,
                false
        )
    }

    @Test
    fun testAdaptWithNoInstructionAndNoHeaderAndCacheByDefaultFalse() {
        testAdapt(
                false,
                false,
                { _, _ -> false },
                false,
                false
        )
    }

}
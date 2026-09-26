package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedField.DURATION;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_PARAMETERS;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_CLASS;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_STATUS;
import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static com.chavaillaz.jakarta.rs.LoggedFilterConfiguration.isCredential;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static com.chavaillaz.jakarta.rs.LoggedSupport.levelOf;
import static com.chavaillaz.jakarta.rs.internal.BodyCapturer.MEMORY_FAILURE;
import static com.chavaillaz.jakarta.rs.internal.Sanitizer.REQUEST_ID_MAX_LENGTH;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static jakarta.ws.rs.core.MediaType.WILDCARD_TYPE;
import static java.lang.Integer.parseInt;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.InterceptorContext;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.core.LogEvent;
import org.jboss.resteasy.core.Headers;
import org.jboss.resteasy.core.interception.jaxrs.ContainerResponseContextImpl;
import org.jboss.resteasy.core.interception.jaxrs.PreMatchContainerRequestContext;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import com.chavaillaz.jakarta.rs.LoggedBody.Direction;
import com.chavaillaz.jakarta.rs.LoggedBody.LogType;
import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyConfiguration;

@DisplayName("Original filter")
@ExtendWith(MockitoExtension.class)
class LoggedFilterTest extends AbstractFilterTest {

    private static final LogType[] NO_LOGGING = new LogType[]{};
    private static final LogType[] LOG_LOGGING = new LogType[]{LogType.LOG};
    private static final LogType[] MDC_LOGGING = new LogType[]{LogType.MDC};
    private static final LogType[] ALL_LOGGING = new LogType[]{LogType.MDC, LogType.LOG};
    private static final Class<?>[] NO_FILTERING = new Class[]{};
    private static final Class<?>[] SENSITIVE_FILTERING = new Class[]{SensitiveBodyFilter.class};
    private static final String PARAMETERS = "param1=value1&param2=value2";
    private static final String INPUT = """
                        {
                            "content": "My Article",
                            "secret-code": "1234-ABCD"
                        }
            """;
    private static final String INPUT_FILTERED = """
                        {
                            "content": "My Article",
                            "secret-code": "masked"
                        }
            """;
    private static final String OUTPUT = """
                        {
                            "id": 5
                            "content": "My Article",
                            "secret-code": "1234-ABCD"
                        }
            """;
    private static final String OUTPUT_FILTERED = """
                        {
                            "id": 5
                            "content": "My Article",
                            "secret-code": "masked"
                        }
            """;

    @Mock
    ResourceInfo resourceInfo;

    LoggedFilter loggingFilter;

    /**
     * Second half of the provider pair under test: the container registers it alongside the filter, at a
     * priority placing it after any entity coder, and it hands every body it captures back to the filter.
     */
    final LoggedBodyInterceptor bodyInterceptor = new LoggedBodyInterceptor();

    static Stream<Arguments> arguments() {
        return Stream.of(
                Arguments.of(AnnotatedResource.class, "inherit", ALL_LOGGING, ALL_LOGGING, SENSITIVE_FILTERING),
                Arguments.of(AnnotatedResource.class, "inheritParent", NO_LOGGING, NO_LOGGING, NO_FILTERING),
                Arguments.of(AnnotatedResource.class, "bodyAsMdcAndLogWithFilter", ALL_LOGGING, ALL_LOGGING, SENSITIVE_FILTERING),
                Arguments.of(AnnotatedResource.class, "bodyAsMdcAndLog", ALL_LOGGING, ALL_LOGGING, NO_FILTERING),
                Arguments.of(AnnotatedResource.class, "bodyAsMdcWithFilter", MDC_LOGGING, MDC_LOGGING, SENSITIVE_FILTERING),
                Arguments.of(AnnotatedResource.class, "bodyAsLogWithFilter", LOG_LOGGING, LOG_LOGGING, SENSITIVE_FILTERING),
                Arguments.of(AnnotatedResource.class, "bodyAsMdc", MDC_LOGGING, MDC_LOGGING, NO_FILTERING),
                Arguments.of(AnnotatedResource.class, "bodyAsLog", LOG_LOGGING, LOG_LOGGING, NO_FILTERING),
                Arguments.of(AnnotatedResource.class, "bodyAsMix", MDC_LOGGING, LOG_LOGGING, NO_FILTERING),
                Arguments.of(AnnotatedResource.class, "noBodyLogging", NO_LOGGING, NO_LOGGING, NO_FILTERING)
        );
    }

    @BeforeEach
    void setupFilter() {
        loggingFilter = filterWith(LoggedFilterConfiguration.defaults());
    }

    /**
     * Builds a filter with the given configuration, sharing the mocks the container would inject into it.
     *
     * @param configuration The configuration of the filter
     * @return The filter created
     */
    LoggedFilter filterWith(LoggedFilterConfiguration configuration) {
        LoggedFilter filter = new LoggedFilter(configuration);
        filter.resourceInfo = resourceInfo;
        return filter;
    }

    void setupTest(Class<?> type, String method) throws Exception {
        doReturn(type).when(resourceInfo).getResourceClass();
        Method resourceMethod = type.getDeclaredMethod(method);
        doReturn(resourceMethod).when(resourceInfo).getResourceMethod();
    }

    /**
     * Stubs the property accessors of an interceptor context onto those of the given request, as a real
     * container shares a single property map for the whole request across its filters and interceptors.
     *
     * @param context The interceptor context to stub
     * @param request The request whose property map the interceptor context shares
     */
    void stubProperties(InterceptorContext context, ContainerRequestContext request) {
        lenient().doAnswer(invocation ->
                request.getProperty(invocation.getArgument(0, String.class))
        ).when(context).getProperty(any());
        lenient().doAnswer(invocation -> {
            request.setProperty(invocation.getArgument(0, String.class), invocation.getArgument(1, Object.class));
            return null;
        }).when(context).setProperty(any(), any());
    }

    /**
     * Builds a reader interceptor context driving the same two-provider chain a container would: the
     * first {@code proceed()}, made by {@link LoggedFilter} at its own priority, hands over to
     * {@link LoggedBodyInterceptor} (which the container places after the entity coder), and the second,
     * made by the capture the latter delegates back, runs the given read as the message body reader would.
     *
     * @param request The request whose entity is read
     * @param entity  The entity stream the chain starts from
     * @param read    The read performed once the whole chain has wrapped the stream
     * @return The stubbed context
     * @throws IOException never, the stubbed proceed() only declares it
     */
    ReaderInterceptorContext readerContext(ContainerRequestContext request, InputStream entity, ThrowingConsumer<InputStream> read) throws IOException {
        return readerContext(request, entity, read, bodyInterceptor);
    }

    /**
     * Builds a reader interceptor context driving the given interceptor chain, in the order a container
     * would invoke it, before performing the given read as the message body reader would.
     *
     * @param request The request whose entity is read
     * @param entity  The entity stream the chain starts from
     * @param read    The read performed once the whole chain has wrapped the stream
     * @param chain   The interceptors to invoke, from the lowest to the highest priority
     * @return The stubbed context
     * @throws IOException never, the stubbed proceed() only declares it
     */
    ReaderInterceptorContext readerContext(ContainerRequestContext request, InputStream entity, ThrowingConsumer<InputStream> read, ReaderInterceptor... chain) throws IOException {
        ReaderInterceptorContext context = mock(ReaderInterceptorContext.class);
        AtomicReference<InputStream> stream = new AtomicReference<>(entity);
        AtomicInteger depth = new AtomicInteger();
        lenient().doAnswer(invocation -> stream.get()).when(context).getInputStream();
        lenient().doAnswer(invocation -> {
            stream.set(invocation.getArgument(0, InputStream.class));
            return null;
        }).when(context).setInputStream(any());
        lenient().doAnswer(invocation -> {
            int position = depth.getAndIncrement();
            if (position < chain.length) {
                return chain[position].aroundReadFrom(context);
            }
            read.accept(stream.get());
            return null;
        }).when(context).proceed();
        stubProperties(context, request);
        return context;
    }

    /**
     * Builds a writer interceptor context driving the same chain as {@link #readerContext}, in the
     * other direction.
     *
     * @param request The request whose response entity is written
     * @param write   The write performed once the whole chain has wrapped the stream
     * @return The stubbed context
     * @throws IOException never, the stubbed proceed() only declares it
     */
    WriterInterceptorContext writerContext(ContainerRequestContext request, ThrowingConsumer<OutputStream> write) throws IOException {
        return writerContext(request, write, bodyInterceptor);
    }

    /**
     * Builds a writer interceptor context driving the given chain, as {@link #readerContext} does in the
     * other direction.
     *
     * @param request The request whose response entity is written
     * @param write   The write performed once the whole chain has wrapped the stream
     * @param chain   The interceptors to invoke, from the lowest to the highest priority
     * @return The stubbed context
     * @throws IOException never, the stubbed proceed() only declares it
     */
    WriterInterceptorContext writerContext(ContainerRequestContext request, ThrowingConsumer<OutputStream> write, WriterInterceptor... chain) throws IOException {
        WriterInterceptorContext context = mock(WriterInterceptorContext.class);
        AtomicReference<OutputStream> stream = new AtomicReference<>(new ByteArrayOutputStream());
        AtomicInteger depth = new AtomicInteger();
        lenient().doAnswer(invocation -> stream.get()).when(context).getOutputStream();
        lenient().doAnswer(invocation -> {
            stream.set(invocation.getArgument(0, OutputStream.class));
            return null;
        }).when(context).setOutputStream(any());
        lenient().doAnswer(invocation -> {
            int position = depth.getAndIncrement();
            if (position < chain.length) {
                chain[position].aroundWriteTo(context);
            } else {
                write.accept(stream.get());
            }
            return null;
        }).when(context).proceed();
        stubProperties(context, request);
        return context;
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("arguments")
    @DisplayName("Check filter actions based on annotation")
    void checkFilterAction(Class<?> type, String method, LogType[] expectedRequestLogging, LogType[] expectedResponseLogging, Class<? extends LoggedBodyFilter>[] expectedBodyFilters) throws Exception {
        setupTest(type, method);

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ReaderInterceptorContext requestInterceptorContext = readerContext(requestContext, requestContext.getEntityStream(), InputStream::readAllBytes);

        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        WriterInterceptorContext responseInterceptorContext = writerContext(requestContext, output -> output.write(OUTPUT.getBytes(UTF_8)));

        // When
        loggingFilter.filter(requestContext);
        loggingFilter.aroundReadFrom(requestInterceptorContext);

        // Then
        assertNotNull(getMdc(REQUEST_ID));
        assertEquals(requestContext.getUriInfo().getPath(), getMdc(REQUEST_URI));
        assertEquals(PARAMETERS, getMdc(REQUEST_PARAMETERS));
        assertEquals(requestContext.getMethod(), getMdc(REQUEST_METHOD));
        assertEquals(type.getSimpleName(), getMdc(RESOURCE_CLASS));
        assertEquals(method, getMdc(RESOURCE_METHOD));

        // When
        loggingFilter.filter(requestContext, responseContext);
        loggingFilter.aroundWriteTo(responseInterceptorContext);

        // Then
        assertNotNull(getMdcLogged(REQUEST_ID));
        assertEquals(requestContext.getUriInfo().getPath(), getMdcLogged(REQUEST_URI));
        assertEquals(requestContext.getMethod(), getMdcLogged(REQUEST_METHOD));
        assertEquals(type.getSimpleName(), getMdcLogged(RESOURCE_CLASS));
        assertEquals(method, getMdcLogged(RESOURCE_METHOD));
        assertEquals(responseContext.getHttpResponse().getStatus(), parseInt(getMdcLogged(RESPONSE_STATUS)));
        assertNotNull(getMdcLogged(DURATION));

        checkRequestLogging(expectedRequestLogging, expectedBodyFilters);
        checkResponseLogging(expectedResponseLogging, expectedBodyFilters);
    }

    @Test
    @DisplayName("Check the bodies of a request are released once it is logged, the request possibly outliving it")
    void checkBodiesReleasedOnceLogged() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsMdcAndLog");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ReaderInterceptorContext requestInterceptorContext = readerContext(requestContext, requestContext.getEntityStream(), InputStream::readAllBytes);
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        WriterInterceptorContext responseInterceptorContext = writerContext(requestContext, output -> output.write(OUTPUT.getBytes(UTF_8)));

        // When
        loggingFilter.filter(requestContext);
        loggingFilter.aroundReadFrom(requestInterceptorContext);
        loggingFilter.filter(requestContext, responseContext);
        loggingFilter.aroundWriteTo(responseInterceptorContext);

        // Then: logged with both of them, which the state of the request, kept along with it, no longer holds
        assertEquals(INPUT, getMdcLogged(REQUEST_BODY));
        assertEquals(OUTPUT, getMdcLogged(RESPONSE_BODY));
        LoggedRequestState state = LoggedRequestState.find(requestContext);
        assertNull(state.getRequestBody());
        assertNull(state.getResponseBody());
    }

    @Test
    @DisplayName("Check body logging configuration is resolved once per resource method and cached")
    void checkBodyConfigurationCaching() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsMdc");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        loggingFilter.filter(requestContext);
        LoggedRequestState state = LoggedRequestState.find(requestContext);

        // When
        LoggedBodyConfiguration request = loggingFilter.getBodyConfiguration(state, REQUEST);
        LoggedBodyConfiguration response = loggingFilter.getBodyConfiguration(state, RESPONSE);

        // Then
        assertTrue(request.isActive());
        assertEquals(request, response);
        assertEquals(1, loggingFilter.resolver.bodyConfigurationCache.size());
    }

    @Test
    @DisplayName("Check the body configuration resolved for a request survives losing the matched resource")
    void checkBodyConfigurationSurvivesUnresolvableResource() throws Exception {
        // Resolution reads the resource class and method from ResourceInfo, a request-scoped object the
        // container usually hands out as a proxy resolving through a thread-local. A callback running
        // where no resource is bound would resolve to nothing and silently turn body logging off for a
        // request that had asked for it, so the configuration is kept on the request instead.
        setupTest(AnnotatedResource.class, "bodyAsMdc");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        loggingFilter.filter(requestContext);
        LoggedRequestState state = LoggedRequestState.find(requestContext);

        // When: a later callback sees no matched resource at all (stubbed leniently, as the whole point
        // is that the calls below no longer reach ResourceInfo)
        lenient().doReturn(null).when(resourceInfo).getResourceClass();
        lenient().doReturn(null).when(resourceInfo).getResourceMethod();

        // Then
        assertTrue(loggingFilter.getBodyConfiguration(state, REQUEST).isActive());
        assertTrue(loggingFilter.getBodyConfiguration(state, RESPONSE).isActive());
    }

    @Test
    @DisplayName("Check merged mappings are resolved once per resource method and cached")
    void checkMergedMappingsCaching() throws Exception {
        setupTest(AnnotatedResource.class, "autoMappedQueryParameters");

        // When: two requests to the same resource method
        loggingFilter.filter(getRequestContext());
        loggingFilter.filter(getRequestContext());

        // Then
        assertEquals(1, loggingFilter.resolver.mappingsCache.size());
    }

    @Test
    @DisplayName("Check the request body is captured after an entity coder decoded it")
    void checkRequestBodyCapturedAfterEntityCoder() throws Exception {
        // Interceptors run in ascending priority and each wraps the stream for the next, so capturing
        // from LoggedFilter's own priority, which runs it before the coder, captured the bytes as they
        // arrive on the wire - gzip noise for a Content-Encoding: gzip request. LoggedBodyInterceptor runs
        // after the coder instead, and must therefore see the decoded entity.
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given: the request as it arrives on the wire, with a coder decoding it between the two
        ReaderInterceptor decoder = context -> {
            context.setInputStream(new GZIPInputStream(context.getInputStream()));
            return context.proceed();
        };
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ReaderInterceptorContext requestInterceptorContext = readerContext(
                requestContext, new ByteArrayInputStream(gzip(INPUT)), InputStream::readAllBytes, decoder, bodyInterceptor);

        // When
        loggingFilter.filter(requestContext);
        loggingFilter.aroundReadFrom(requestInterceptorContext);

        // Then
        LogEvent event = listAppender.findFirstMessage("Received");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains(INPUT));
    }

    @Test
    @DisplayName("Check the response body is captured before an entity coder encoded it")
    void checkResponseBodyCapturedBeforeEntityCoder() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given: a coder compressing the entity between the two, so only an interceptor running after
        // it still sees what the message body writer actually produced
        WriterInterceptor encoder = context -> {
            GZIPOutputStream compressed = new GZIPOutputStream(context.getOutputStream());
            context.setOutputStream(compressed);
            context.proceed();
            compressed.finish();
        };
        PreMatchContainerRequestContext requestContext = getRequestContext();
        WriterInterceptorContext responseInterceptorContext = writerContext(
                requestContext, output -> output.write(OUTPUT.getBytes(UTF_8)), encoder, bodyInterceptor);

        // When
        loggingFilter.filter(requestContext);
        loggingFilter.filter(requestContext, getResponseContext(requestContext));
        loggingFilter.aroundWriteTo(responseInterceptorContext);

        // Then
        LogEvent event = listAppender.findFirstMessage("Processed");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains(OUTPUT));
    }

    static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream stream = new GZIPOutputStream(compressed)) {
            stream.write(content.getBytes(UTF_8));
        }
        return compressed.toByteArray();
    }

    @Test
    @DisplayName("Check request body is still logged when reading the entity fails")
    void checkRequestBodyLoggedOnReadFailure() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ReaderInterceptorContext requestInterceptorContext = readerContext(requestContext, requestContext.getEntityStream(), stream -> {
            stream.readAllBytes();
            throw new IOException("Malformed payload");
        });

        loggingFilter.filter(requestContext);

        // When
        assertThrows(IOException.class, () -> loggingFilter.aroundReadFrom(requestInterceptorContext));

        // Then
        LogEvent logReceived = listAppender.findFirstMessage("Received");
        assertNotNull(logReceived);
        assertTrue(logReceived.getMessage().getFormattedMessage().contains(INPUT));
    }

    @Test
    @DisplayName("Check the request is still logged when the resource method never reads the entity")
    void checkRequestLoggedWhenEntityNeverRead() throws Exception {
        // Most JAX-RS implementations only invoke aroundReadFrom when the resource method actually
        // consumes the request entity (see its Javadoc): a resource method that does not must still get
        // its "Received ..." line from the fallback in filter(request, response), not silently drop it
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);

        // When: aroundReadFrom is deliberately never called
        loggingFilter.filter(requestContext);
        loggingFilter.filter(requestContext, responseContext);

        // Then
        LogEvent logReceived = listAppender.findFirstMessage("Received");
        assertNotNull(logReceived);
        assertEquals("Received POST /service", logReceived.getMessage().getFormattedMessage());
        assertEquals(1, listAppender.getMessages().stream()
                .filter(event -> event.getMessage().getFormattedMessage().startsWith("Received"))
                .count());
    }

    @Test
    @DisplayName("Check the fallback request logging does not duplicate a line already logged by aroundReadFrom")
    void checkRequestLoggedOnceWhenEntityIsRead() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ReaderInterceptorContext requestInterceptorContext = readerContext(requestContext, requestContext.getEntityStream(), InputStream::readAllBytes);
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);

        // When
        loggingFilter.filter(requestContext);
        loggingFilter.aroundReadFrom(requestInterceptorContext);
        loggingFilter.filter(requestContext, responseContext);

        // Then
        assertEquals(1, listAppender.getMessages().stream()
                .filter(event -> event.getMessage().getFormattedMessage().startsWith("Received"))
                .count());
    }

    @Test
    @DisplayName("Check a request whose entity is read twice is only logged once")
    void checkRequestLoggedOnceWhenEntityReadTwice() throws Exception {
        // A buffered entity can be read more than once - by a filter validating its signature, then by the
        // resource method - and every read goes through the interceptors again
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        loggingFilter.filter(requestContext);

        // When
        loggingFilter.aroundReadFrom(readerContext(requestContext, IOUtils.toInputStream(INPUT, UTF_8), InputStream::readAllBytes));
        loggingFilter.aroundReadFrom(readerContext(requestContext, IOUtils.toInputStream(INPUT, UTF_8), InputStream::readAllBytes));

        // Then
        assertEquals(List.of("Received POST /service" + LF + INPUT), getReceivedMessages());
    }

    @Test
    @DisplayName("Check a request body the message body reader peeks at is logged as it was received")
    void checkPeekedRequestBodyLoggedAsReceived() throws Exception {
        // A reader checking for an empty entity marks the stream, reads its first byte and resets the stream
        // before reading it for good: a plain tee copies that first byte to the capture twice
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        loggingFilter.filter(requestContext);

        // When
        loggingFilter.aroundReadFrom(readerContext(requestContext, IOUtils.toInputStream(INPUT, UTF_8), stream -> {
            stream.mark(1);
            stream.read();
            stream.reset();
            stream.readAllBytes();
        }));

        // Then
        assertEquals(List.of("Received POST /service" + LF + INPUT), getReceivedMessages());
    }

    @Test
    @DisplayName("Check a body read after the request was logged without one is still logged")
    void checkBodyLoggedAfterRequestLoggedWithoutOne() throws Exception {
        // Whether a request has a body is decided before it is read, by some containers from its
        // Content-Type header alone: a body sent without one is announced as absent at once, then read by
        // the resource method after all, and that body is precisely what the first line lacked
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        MockHttpRequest request = MockHttpRequest.create("POST", "example.company.com/service");
        request.setInputStream(IOUtils.toInputStream(INPUT, UTF_8));
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(request);
        loggingFilter.filter(requestContext);

        // When
        loggingFilter.aroundReadFrom(readerContext(requestContext, requestContext.getEntityStream(), InputStream::readAllBytes));

        // Then
        assertEquals(List.of("Received POST /service", "Received POST /service" + LF + INPUT), getReceivedMessages());
    }

    /**
     * Gets the {@code "Received ..."} lines logged so far, in the order they were logged.
     *
     * @return The formatted messages of those lines
     */
    List<String> getReceivedMessages() {
        return listAppender.getMessages().stream()
                .map(event -> event.getMessage().getFormattedMessage())
                .filter(message -> message.startsWith("Received"))
                .toList();
    }

    @Test
    @DisplayName("Check automatic MDC mapping cannot override reserved fields")
    void checkAutoMappingReservedFieldProtection() throws Exception {
        setupTest(AnnotatedResource.class, "autoMappedQueryParameters");

        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service?request-id=malicious&topic=news"));

        // When
        loggingFilter.filter(requestContext);

        // Then
        String requestId = getMdc(REQUEST_ID);
        assertNotNull(requestId);
        assertNotEquals("malicious", requestId);
        assertEquals("news", MDC.get("topic"));
    }

    @Test
    @DisplayName("Check automatic header mapping skips credential-carrying headers")
    void checkAutoMappingSkipsSensitiveHeaders() throws Exception {
        setupTest(AnnotatedResource.class, "autoMappedHeaders");

        // Given
        MockHttpRequest request = MockHttpRequest.create("GET", "example.company.com/service");
        request.header("Authorization", "Bearer secret-token");
        request.header("Cookie", "JSESSIONID=secret-session");
        request.header("X-Api-Key", "secret-key");
        request.header("User-Agent", "JUnit");
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(request);

        // When
        loggingFilter.filter(requestContext);

        // Then: an "map everything the client sent" instruction must not silently ship credentials
        // to the log aggregator, while ordinary headers are still mapped
        assertNull(MDC.get("header-Authorization"));
        assertNull(MDC.get("header-Cookie"));
        assertNull(MDC.get("header-X-Api-Key"));
        assertEquals("JUnit", MDC.get("header-User-Agent"));
    }

    @Test
    @DisplayName("Check a credential-carrying query parameter is masked in the logged parameters")
    void checkSensitiveQueryParameterIsMasked() throws Exception {
        // Unlike a header, a query parameter is logged by default, with nothing to configure. Passing a
        // credential in a query string is bad practice, but OAuth flows, presigned URLs and plenty of
        // internal APIs do it, and the application has no say in what its callers send.
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service?topic=news&access_token=secret-token"));

        // When
        loggingFilter.filter(requestContext);

        // Then: the name stays visible, as knowing a token was supplied is the useful part
        String parameters = getMdc(REQUEST_PARAMETERS);
        assertNotNull(parameters);
        assertFalse(parameters.contains("secret-token"));
        assertTrue(parameters.contains("access_token=***"));
        assertTrue(parameters.contains("topic=news"));
    }

    @Test
    @DisplayName("Check automatic query mapping skips credential-carrying parameters")
    void checkAutoMappingSkipsSensitiveQueryParameters() throws Exception {
        setupTest(AnnotatedResource.class, "autoMappedQueryParameters");

        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service?topic=news&password=hunter2"));

        // When
        loggingFilter.filter(requestContext);

        // Then
        assertNull(MDC.get("password"));
        assertEquals("news", MDC.get("topic"));
    }

    @Test
    @DisplayName("Check a request without query parameters does not create an empty MDC entry")
    void checkNoEmptyParametersMdcEntry() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service"));

        // When
        loggingFilter.filter(requestContext);

        // Then: absent, rather than present and empty in every structured log line of the application
        assertNull(getMdc(REQUEST_PARAMETERS));
    }

    @Test
    @DisplayName("Check MDC fields left over on a pooled thread are swept before the request is logged")
    void checkStaleMdcSweptAtRequestStart() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: a field left behind by a previous request that completed on another thread
        MDC.put(getMdcField(RESPONSE_STATUS), "500");
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service"));

        // When
        loggingFilter.filter(requestContext);

        // Then: the unrelated request now running on this thread is not mislabelled with it
        assertNull(getMdc(RESPONSE_STATUS));
    }

    @Test
    @DisplayName("Check a mapped MDC key left over on a pooled thread is swept at the start of the next request")
    void checkStaleMappedMdcKeySweptAtRequestStart() throws Exception {
        // Completing a request can only remove its entries from the thread completing it, so a request
        // completing elsewhere (a resumed @Suspended response, a container that never reaches the
        // completion callbacks) leaves its entries behind. Sweeping the fixed fields covered request-id
        // and its siblings; a key an automatic mapping derived from what the client sent stayed on the
        // (pooled) thread and mislabelled every later request served by it, as no request overwrites a
        // key the next client does not happen to send.
        setupTest(AnnotatedResource.class, "autoMappedHeaders");

        // Given: a previous request that mapped a header, completed without this thread cleaning up
        MockHttpRequest previous = MockHttpRequest.create("GET", "example.company.com/service");
        previous.header("User-Agent", "JUnit");
        loggingFilter.filter(new PreMatchContainerRequestContext(previous));
        assertEquals("JUnit", MDC.get("header-User-Agent"));

        // When: an unrelated request, mapping nothing, now runs on this very thread
        setupTest(AnnotatedResource.class, "noBodyLogging");
        loggingFilter.filter(new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/other")));

        // Then
        assertNull(MDC.get("header-User-Agent"));
    }

    @Test
    @DisplayName("Check MDC entries created by mappings are removed once the response has been logged")
    void checkMappingKeysCleanedUpAfterResponse() throws Exception {
        setupTest(AnnotatedResource.class, "autoMappedQueryParameters");

        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service?topic=news"));
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);

        // When
        loggingFilter.filter(requestContext);
        assertEquals("news", MDC.get("topic"));
        loggingFilter.filter(requestContext, responseContext);

        // Then
        assertNull(MDC.get("topic"));
    }

    @Test
    @DisplayName("Check control characters are stripped from the request identifier to prevent log injection")
    void checkLogInjectionSanitizationOnRequestId() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service")
                        .header("X-Request-ID", "abc\r\nFAKE LOG LINE injected"));

        // When
        loggingFilter.filter(requestContext);

        // Then
        String requestId = getMdc(REQUEST_ID);
        assertNotNull(requestId);
        assertFalse(requestId.contains("\r"));
        assertFalse(requestId.contains("\n"));
        assertTrue(requestId.contains("FAKE LOG LINE injected"));
    }

    @Test
    @DisplayName("Check an oversized request identifier is truncated to prevent inflating every log line")
    void checkOversizedRequestIdIsTruncated() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        String oversized = "a".repeat(REQUEST_ID_MAX_LENGTH + 50);
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service")
                        .header("X-Request-ID", oversized));

        // When
        loggingFilter.filter(requestContext);

        // Then
        String requestId = getMdc(REQUEST_ID);
        assertNotNull(requestId);
        assertEquals(REQUEST_ID_MAX_LENGTH, requestId.length());
        assertEquals(oversized.substring(0, REQUEST_ID_MAX_LENGTH), requestId);
    }

    @Test
    @DisplayName("Check a request identifier made of nothing but control characters is replaced by a generated one")
    void checkControlCharacterRequestIdReplaced() throws Exception {
        // Sanitizing turns such an identifier blank, and a blank value is never put in MDC: checked before
        // being sanitized, it left the request without any identifier at all
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service")
                        .header("X-Request-ID", "\u0007\u007f"));

        // When
        loggingFilter.filter(requestContext);

        // Then
        String requestId = getMdc(REQUEST_ID);
        assertNotNull(requestId);
        assertFalse(requestId.isBlank());
    }

    @Test
    @DisplayName("Check truncating an oversized request identifier does not cut a character in half")
    void checkOversizedRequestIdTruncatedOnCharacterBoundary() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: a character outside the Basic Multilingual Plane, two chars long, straddling the limit
        String prefix = "a".repeat(REQUEST_ID_MAX_LENGTH - 1);
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service")
                        .header("X-Request-ID", prefix + "😀"));

        // When
        loggingFilter.filter(requestContext);

        // Then: the character is left out whole, rather than half of it staying as an invalid string
        assertEquals(prefix, getMdc(REQUEST_ID));
    }

    @Test
    @DisplayName("Check control characters are stripped from auto-mapped parameter values to prevent log injection")
    void checkLogInjectionSanitizationOnMappedParameter() throws Exception {
        setupTest(AnnotatedResource.class, "autoMappedQueryParameters");

        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service?topic=news%0D%0AFAKE"));

        // When
        loggingFilter.filter(requestContext);

        // Then
        String topic = MDC.get("topic");
        assertNotNull(topic);
        assertFalse(topic.contains("\r"));
        assertFalse(topic.contains("\n"));
    }

    @Test
    @DisplayName("Check MDC is cleaned up even if writing the response body fails")
    void checkMdcCleanupOnWriteFailure() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        WriterInterceptorContext responseInterceptorContext = writerContext(requestContext, output -> {
            throw new IOException("Client disconnected");
        });

        loggingFilter.filter(requestContext);
        loggingFilter.filter(requestContext, responseContext);

        // When
        assertThrows(IOException.class, () -> loggingFilter.aroundWriteTo(responseInterceptorContext));

        // Then
        assertNull(MDC.get(getMdcField(REQUEST_ID)));
        assertNull(MDC.get(getMdcField(REQUEST_URI)));
        assertNull(MDC.get(getMdcField(DURATION)));
        assertNull(MDC.get(getMdcField(RESPONSE_STATUS)));
        assertNotNull(listAppender.findFirstMessage("Processed"));
    }

    @Test
    @DisplayName("Check an entity written in several parts is captured and logged once, leaving nothing in MDC")
    void checkEntityWrittenInPartsLoggedOnce() throws Exception {
        // A chunked or event stream response goes through the writer interceptors once per part, long after
        // the first part completed the request: every later part was captured for nothing, and its body put
        // in MDC by a completion that no longer removed anything
        setupTest(AnnotatedResource.class, "bodyAsMdcAndLog");

        // Given
        AtomicInteger captures = new AtomicInteger();
        LoggedFilter countingFilter = filterWith(LoggedFilterConfiguration.builder()
                .bodyCapture(limit -> {
                    captures.incrementAndGet();
                    return new BoundedLoggedBodyCapture(limit);
                })
                .build());
        PreMatchContainerRequestContext requestContext = getRequestContext();
        countingFilter.filter(requestContext);
        countingFilter.filter(requestContext, getResponseContext(requestContext));

        // When
        countingFilter.aroundWriteTo(writerContext(requestContext, output -> output.write("first".getBytes(UTF_8))));
        countingFilter.aroundWriteTo(writerContext(requestContext, output -> output.write("second".getBytes(UTF_8))));

        // Then
        assertEquals(1, captures.get());
        assertEquals(1, listAppender.getMessages().stream()
                .filter(event -> event.getMessage().getFormattedMessage().startsWith("Processed"))
                .count());
        assertTrue(MDC.getCopyOfContextMap().isEmpty(), () -> "Left in MDC: " + MDC.getCopyOfContextMap());
    }

    @Test
    @DisplayName("Check whatever was written to the response body is still logged when writing it then fails")
    void checkPartialResponseBodyLoggedOnWriteFailure() throws Exception {
        // A serialization error partway through, or a client disconnecting mid-write, must not discard
        // the bytes already produced: aroundReadFrom already guarantees this for the request body (see
        // its Javadoc), and aroundWriteTo must mirror it for the response body
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        WriterInterceptorContext responseInterceptorContext = writerContext(requestContext, output -> {
            output.write("partial content".getBytes(UTF_8));
            throw new IOException("Client disconnected");
        });

        loggingFilter.filter(requestContext);
        loggingFilter.filter(requestContext, responseContext);

        // When
        assertThrows(IOException.class, () -> loggingFilter.aroundWriteTo(responseInterceptorContext));

        // Then
        LogEvent event = listAppender.findFirstMessage("Processed");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains("partial content"));
    }

    @Test
    @DisplayName("Check response is logged and MDC cleaned up on an empty response even without body logging configured")
    void checkResponseLoggedAndMdcCleanedUpWithoutBodyLoggingOnEmptyResponse() throws Exception {
        // As with no response entity (e.g. 204 No Content, HEAD) the container never calls
        // aroundWriteTo, filter(request, response) must be the one logging and cleaning up MDC
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);

        // When
        loggingFilter.filter(requestContext);
        loggingFilter.filter(requestContext, responseContext);

        // Then
        assertNotNull(listAppender.findFirstMessage("Processed"));
        assertNull(MDC.get(getMdcField(REQUEST_ID)));
        assertNull(MDC.get(getMdcField(REQUEST_URI)));
        assertNull(MDC.get(getMdcField(DURATION)));
        assertNull(MDC.get(getMdcField(RESPONSE_STATUS)));
    }

    @ParameterizedTest(name = "status {0} logged at {1}")
    @CsvSource({"200, INFO", "304, INFO", "400, WARN", "404, WARN", "500, ERROR", "503, ERROR"})
    @DisplayName("Check the level of the completion line follows the response status")
    void checkResponseLevelFollowsStatus(int status, Level expectedLevel) throws Exception {
        // A failed request logged at the same level as a successful one is a line nobody is alerted on:
        // the library is the one place that knows the request failed, so leaving every completion at INFO
        // pushes that knowledge into a message-parsing rule in whatever consumes the logs
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext, status);

        // When
        loggingFilter.filter(requestContext);
        loggingFilter.filter(requestContext, responseContext);

        // Then
        LogEvent event = listAppender.findFirstMessage("Processed");
        assertNotNull(event);
        assertEquals(expectedLevel.name(), event.getLevel().name());
    }

    @Test
    @DisplayName("Check the request identifier is returned to the caller")
    void checkRequestIdReturnedToCaller() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);
        loggingFilter.filter(requestContext);
        String requestId = getMdc(REQUEST_ID);

        // When
        loggingFilter.filter(requestContext, responseContext);

        // Then: a caller quoting it in a bug report points straight at the request, instead of leaving
        // whoever picks the report up searching the logs by timestamp
        assertEquals(requestId, responseContext.getHeaders().getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check a request identifier already set on the response is not overwritten")
    void checkExistingRequestIdHeaderKept() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: an application, or a gateway in front of it, setting its own (in a different casing,
        // as HTTP header names are case-insensitive but the response header map need not be)
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);
        responseContext.getHeaders().putSingle("x-request-id", "chosen-by-the-application");
        loggingFilter.filter(requestContext);

        // When
        loggingFilter.filter(requestContext, responseContext);

        // Then
        assertEquals(1, responseContext.getHeaders().keySet().stream()
                .filter(REQUEST_ID_HEADER::equalsIgnoreCase)
                .count());
        assertEquals("chosen-by-the-application", responseContext.getHeaders().getFirst("x-request-id"));
    }

    @Test
    @DisplayName("Check the request identifier is read from and returned in the header configured")
    void checkConfiguredRequestIdHeader() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        LoggedFilter configuredFilter = filterWith(LoggedFilterConfiguration.builder()
                .fieldName(REQUEST_ID, "trace-id")
                .requestIdHeader("X-Trace-ID")
                .build());
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service").header("X-Trace-ID", "trace-42"));
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);

        // When
        configuredFilter.filter(requestContext);
        String requestId = MDC.get("trace-id");
        configuredFilter.filter(requestContext, responseContext);

        // Then
        assertEquals("trace-42", requestId);
        assertEquals("trace-42", responseContext.getHeaders().getFirst("X-Trace-ID"));
        assertNull(responseContext.getHeaders().getFirst(REQUEST_ID_HEADER));
    }

    /**
     * Builds a filter the way the container instantiates one, the application resolving its configuration
     * through the given resolver.
     *
     * @param resolver The resolver the application declares, {@code null} for none
     * @return The filter created
     */
    LoggedFilter filterDeclaring(@Nullable ContextResolver<LoggedFilterConfiguration> resolver) {
        LoggedFilter filter = new LoggedFilter();
        filter.resourceInfo = resourceInfo;
        filter.providers = mock(Providers.class);
        doReturn(resolver).when(filter.providers).getContextResolver(LoggedFilterConfiguration.class, WILDCARD_TYPE);
        return filter;
    }

    @Test
    @DisplayName("Check a provider the container instantiates is configured the way the application declares")
    void checkConfigurationDeclaredByApplication() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: a configuration the application declares for the class of the provider asking alone
        LoggedFilter declaredFilter = filterDeclaring(type -> type == LoggedFilter.class
                ? LoggedFilterConfiguration.builder().fieldName(REQUEST_ID, "trace-id").build()
                : null);
        PreMatchContainerRequestContext requestContext = getRequestContext();

        // When
        declaredFilter.filter(requestContext);
        declaredFilter.filter(requestContext, getEmptyResponseContext(requestContext));

        // Then: looked up once, when the first request is logged
        assertNotNull(listAppender.findFirstMessage("Processed").getContextData().getValue("trace-id"));
        assertEquals("trace-id", declaredFilter.configuration().fieldName(REQUEST_ID));
        verify(declaredFilter.providers, times(1)).getContextResolver(LoggedFilterConfiguration.class, WILDCARD_TYPE);
    }

    @Test
    @DisplayName("Check a provider uses the default configuration when the application declares none, or fails to")
    void checkDefaultConfigurationWhenNoneDeclared() {
        ContextResolver<LoggedFilterConfiguration> failing = type -> {
            throw new IllegalStateException("Configuration not loaded yet");
        };

        assertSame(LoggedFilterConfiguration.defaults(), new LoggedFilter().configuration());
        assertSame(LoggedFilterConfiguration.defaults(), filterDeclaring(null).configuration());
        assertSame(LoggedFilterConfiguration.defaults(), filterDeclaring(type -> null).configuration());
        assertSame(LoggedFilterConfiguration.defaults(), filterDeclaring(failing).configuration());
        assertNotNull(listAppender.findFirstMessage("Unable to look up the configuration of the application"));
    }

    @Test
    @DisplayName("Check a provider constructed with its configuration looks none up")
    void checkGivenConfigurationNotLookedUp() {
        // Given
        LoggedFilterConfiguration given = LoggedFilterConfiguration.builder().build();
        LoggedFilter givenFilter = new LoggedFilter(given);
        givenFilter.providers = mock(Providers.class);

        // Then
        assertSame(given, givenFilter.configuration());
        verifyNoInteractions(givenFilter.providers);
    }

    @ParameterizedTest(name = "strategy obtaining \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", " ", "\r\n"})
    @DisplayName("Check a request the identifier strategy obtains none for gets a random one")
    void checkRequestIdGeneratedWhenStrategyObtainsNone(String obtained) throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        LoggedFilter configuredFilter = filterWith(LoggedFilterConfiguration.builder()
                .requestId(request -> obtained)
                .build());
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);

        // When
        configuredFilter.filter(requestContext);
        configuredFilter.filter(requestContext, responseContext);

        // Then
        String requestId = getMdcLogged(REQUEST_ID);
        assertDoesNotThrow(() -> UUID.fromString(requestId));
        assertEquals(requestId, responseContext.getHeaders().getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check an identifier strategy that fails costs the request its identifier alone")
    void checkRequestIdGeneratedWhenStrategyFails() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        LoggedFilter configuredFilter = filterWith(LoggedFilterConfiguration.builder()
                .requestId(request -> {
                    throw new IllegalStateException("No trace context on this request");
                })
                .build());
        PreMatchContainerRequestContext requestContext = getRequestContext();

        // When
        configuredFilter.filter(requestContext);
        configuredFilter.filter(requestContext, getEmptyResponseContext(requestContext));

        // Then
        assertDoesNotThrow(() -> UUID.fromString(getMdcLogged(REQUEST_ID)));
        assertEquals("POST", getMdcLogged(REQUEST_METHOD));
        assertEquals("/service", getMdcLogged(REQUEST_URI));
        assertEquals("Processed POST /service with status 204", listAppender.findFirstMessage("Processed")
                .getMessage().getFormattedMessage().replaceAll(" in \\d+ms$", ""));
        assertNotNull(listAppender.findFirstMessage("Unable to get the identifier of the request"));
    }

    @Test
    @DisplayName("Check the request identifier is not returned to the caller once configured not to be")
    void checkRequestIdNotReturned() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        LoggedFilter configuredFilter = filterWith(LoggedFilterConfiguration.builder()
                .withoutReturnedRequestId()
                .build());
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);

        // When
        configuredFilter.filter(requestContext);
        configuredFilter.filter(requestContext, responseContext);

        // Then
        assertTrue(responseContext.getHeaders().isEmpty());
        assertNotNull(listAppender.findFirstMessage("Processed"));
    }

    @Test
    @DisplayName("Check the level of the completion line follows the level configured for the status")
    void checkConfiguredResponseLevel() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: an application expecting its 404, which is no reason to warn anybody
        LoggedFilter configuredFilter = filterWith(LoggedFilterConfiguration.builder()
                .responseLevel(status -> status == 404 ? Level.INFO : levelOf(status))
                .build());
        PreMatchContainerRequestContext requestContext = getRequestContext();

        // When
        configuredFilter.filter(requestContext);
        configuredFilter.filter(requestContext, getEmptyResponseContext(requestContext, 404));

        // Then
        assertEquals(Level.INFO.name(), listAppender.findFirstMessage("Processed").getLevel().name());
    }

    @Test
    @DisplayName("Check a status the level function gives no level for is still logged, at its default level")
    void checkConfiguredResponseLevelWithoutLevel() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: a function covering the statuses it cares about, and returning null for the others
        LoggedFilter configuredFilter = filterWith(LoggedFilterConfiguration.builder()
                .responseLevel(status -> status == 404 ? Level.INFO : null)
                .build());
        PreMatchContainerRequestContext requestContext = getRequestContext();

        // When
        configuredFilter.filter(requestContext);
        configuredFilter.filter(requestContext, getEmptyResponseContext(requestContext, 503));

        // Then
        LogEvent event = listAppender.findFirstMessage("Processed");
        assertNotNull(event, "No Processed line was logged");
        assertEquals(Level.ERROR.name(), event.getLevel().name());
    }

    @Test
    @DisplayName("Check the value of a query parameter configured as sensitive is masked")
    void checkConfiguredSensitiveParameter() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        LoggedFilter configuredFilter = filterWith(LoggedFilterConfiguration.builder()
                .sensitiveParameters((type, name) -> isCredential(type, name)
                        || (type == QUERY && "url-signature".equals(name)))
                .build());
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service?url-signature=secret&access_token=token"));

        // When
        configuredFilter.filter(requestContext);

        // Then: both the one configured and the default ones
        assertEquals("access_token=***&url-signature=***", getMdc(REQUEST_PARAMETERS));
    }

    @Test
    @DisplayName("Check completing the request twice only logs and cleans up MDC once")
    void checkLogResponseIsIdempotent() throws Exception {
        // Guards against a request being completed twice, in case a future JAX-RS edge case (exception
        // mapping, @Suspended AsyncResponse, ...) ever causes both filter(request, response) and
        // aroundWriteTo to consider themselves responsible for completing the same request
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);
        loggingFilter.filter(requestContext);
        loggingFilter.filter(requestContext, responseContext);

        // When
        loggingFilter.logResponse(LoggedRequestState.find(requestContext), "");

        // Then
        long processedCount = listAppender.getMessages().stream()
                .filter(event -> event.getMessage().getFormattedMessage().contains("Processed"))
                .count();
        assertEquals(1, processedCount);
    }

    @Test
    @DisplayName("Check a body filter that throws neither breaks the response nor leaks the body")
    void checkFailingBodyFilterDoesNotBreakResponse() throws Exception {
        setupTest(AnnotatedResource.class, "bodyWithFailingFilter");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        WriterInterceptorContext responseInterceptorContext = writerContext(requestContext, output -> output.write(OUTPUT.getBytes(UTF_8)));

        loggingFilter.filter(requestContext);
        loggingFilter.filter(requestContext, responseContext);

        // When: a bug in a filter must not turn a perfectly good response into a 500
        assertDoesNotThrow(() -> loggingFilter.aroundWriteTo(responseInterceptorContext));

        // Then: the response is still logged, without the payload the filter failed to redact,
        // and the request is still completed (MDC cleaned up)
        LogEvent event = listAppender.findFirstMessage("Processed");
        assertNotNull(event);
        assertFalse(event.getMessage().getFormattedMessage().contains("1234-ABCD"));
        assertNull(MDC.get(getMdcField(REQUEST_ID)));
    }

    @Test
    @DisplayName("Check a body filter that throws does not replace the exception the exchange failed with")
    void checkFailingBodyFilterKeepsOriginalException() throws Exception {
        // The capture and logging happen in a finally block, so an exception raised there does not just
        // lose a log line: it replaces the real failure on its way out, leaving nothing pointing at what
        // actually went wrong
        setupTest(AnnotatedResource.class, "bodyWithFailingFilter");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        WriterInterceptorContext responseInterceptorContext = writerContext(requestContext, output -> {
            throw new IOException("Client disconnected");
        });

        loggingFilter.filter(requestContext);
        loggingFilter.filter(requestContext, responseContext);

        // When
        IOException thrown = assertThrows(IOException.class, () -> loggingFilter.aroundWriteTo(responseInterceptorContext));

        // Then
        assertEquals("Client disconnected", thrown.getMessage());
    }

    @Test
    @DisplayName("Check a request aborted before this provider's request filter still describes the request")
    void checkAbortedRequestStillDescribesTheRequest() throws Exception {
        // An authentication filter sits at Priorities.AUTHENTICATION, well below this provider's own
        // priority, so aborting there skips the rest of the request filter chain - including this
        // provider's - while the container still runs every response filter. The completion line of a 401
        // an application rejects that way used to carry none of the fields describing the request
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext, 401);

        // When: only the response filter runs, filter(ContainerRequestContext) never did
        loggingFilter.filter(requestContext, responseContext);

        // Then
        assertNotNull(listAppender.findFirstMessage("Processed"));
        assertNotNull(getMdcLogged(REQUEST_ID));
        assertEquals(requestContext.getMethod(), getMdcLogged(REQUEST_METHOD));
        assertEquals(requestContext.getUriInfo().getPath(), getMdcLogged(REQUEST_URI));
        assertEquals("401", getMdcLogged(RESPONSE_STATUS));
        assertNull(MDC.get(getMdcField(REQUEST_ID)));
    }

    @Test
    @DisplayName("Check a body capture that cannot be created neither breaks the request nor skips the entity")
    void checkFailingBodyCaptureDoesNotBreakRequest() throws Exception {
        // Putting the capture in place is the one piece of logging work happening before proceed() rather
        // than in a finally block after it, so a failure there does not merely lose a log line: the entity
        // is never read at all and the request fails with an error having nothing to do with it
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given: what a createBodyCapture spilling to a temporary file does when it cannot create one
        LoggedFilter failingCaptureFilter = failingCaptureFilter();
        PreMatchContainerRequestContext requestContext = getRequestContext();
        AtomicReference<String> entityRead = new AtomicReference<>();
        ReaderInterceptorContext requestInterceptorContext = readerContext(
                requestContext, requestContext.getEntityStream(),
                stream -> entityRead.set(new String(stream.readAllBytes(), UTF_8)));

        failingCaptureFilter.filter(requestContext);

        // When
        assertDoesNotThrow(() -> failingCaptureFilter.aroundReadFrom(requestInterceptorContext));

        // Then: the entity was read as if nothing had been asked of it, only without a body in the logs
        assertEquals(INPUT, entityRead.get());
    }

    @Test
    @DisplayName("Check a body configuration that cannot be resolved breaks neither reading nor writing the entity")
    void checkUnresolvableBodyConfigurationDoesNotBreakExchange() throws Exception {
        // Deciding whether to capture a body happens before proceed() too, where a failure prevents the
        // entity from being read or written at all
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given: a configuration failing to resolve, standing in for a container's ResourceInfo failing
        // outside of the request scope it expects, or a subclass overriding the resolution
        LoggedFilter failingFilter = new LoggedFilter() {

            @Override
            protected LoggedBodyConfiguration getBodyConfiguration(LoggedRequestState state, Direction target) {
                throw new IllegalStateException("Not inside a request scope");
            }

        };
        failingFilter.resourceInfo = resourceInfo;
        PreMatchContainerRequestContext requestContext = getRequestContext();
        AtomicReference<String> entityRead = new AtomicReference<>();
        ReaderInterceptorContext requestInterceptorContext = readerContext(
                requestContext, requestContext.getEntityStream(),
                stream -> entityRead.set(new String(stream.readAllBytes(), UTF_8)));
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        WriterInterceptorContext responseInterceptorContext = writerContext(
                requestContext, output -> written.write(OUTPUT.getBytes(UTF_8)));

        // When
        failingFilter.filter(requestContext);
        assertDoesNotThrow(() -> failingFilter.aroundReadFrom(requestInterceptorContext));
        failingFilter.filter(requestContext, getResponseContext(requestContext));
        assertDoesNotThrow(() -> failingFilter.aroundWriteTo(responseInterceptorContext));

        // Then
        assertEquals(INPUT, entityRead.get());
        assertEquals(OUTPUT, written.toString(UTF_8));
    }

    @Test
    @DisplayName("Check a body capture that cannot be created neither breaks the response nor skips the entity")
    void checkFailingBodyCaptureDoesNotBreakResponse() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        LoggedFilter failingCaptureFilter = failingCaptureFilter();
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        WriterInterceptorContext responseInterceptorContext = writerContext(requestContext, output -> {
            output.write(OUTPUT.getBytes(UTF_8));
            written.write(OUTPUT.getBytes(UTF_8));
        });

        failingCaptureFilter.filter(requestContext);
        failingCaptureFilter.filter(requestContext, responseContext);

        // When
        assertDoesNotThrow(() -> failingCaptureFilter.aroundWriteTo(responseInterceptorContext));

        // Then: the entity was written, and the request still completed (logged and MDC cleaned up)
        assertEquals(OUTPUT, written.toString(UTF_8));
        assertNotNull(listAppender.findFirstMessage("Processed"));
        assertNull(MDC.get(getMdcField(REQUEST_ID)));
    }

    @Test
    @DisplayName("Check a capture is released once what it collected has been read")
    void checkCaptureReleasedOnceRead() throws Exception {
        // A capture holding more than memory - one spilling a large body to a temporary file - has
        // nowhere to give it back other than close()
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        AtomicBoolean closed = new AtomicBoolean();
        LoggedFilter capturingFilter = capturingFilter(closed);
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ReaderInterceptorContext requestInterceptorContext = readerContext(
                requestContext, requestContext.getEntityStream(),
                InputStream::readAllBytes);

        capturingFilter.filter(requestContext);

        // When
        capturingFilter.aroundReadFrom(requestInterceptorContext);

        // Then: the body was read from the capture, and the capture released afterwards
        assertNotNull(listAppender.findFirstMessage("Received"));
        assertTrue(closed.get());
    }

    @Test
    @DisplayName("Check a body too large to render with the memory available is left out without failing the request")
    void checkBodyTooLargeToRenderLeftOut() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given: a capture whose rendering needs more memory than the heap has left
        AtomicBoolean closed = new AtomicBoolean();
        LoggedFilter capturingFilter = filterWith(LoggedFilterConfiguration.builder()
                .bodyCapture(limit -> new BoundedLoggedBodyCapture(limit) {

                    @Override
                    public String content(Set<LoggedBodyFilter> filters, MediaType mediaType) {
                        throw new OutOfMemoryError("Java heap space");
                    }

                    @Override
                    public void close() {
                        closed.set(true);
                    }

                })
                .build());
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ReaderInterceptorContext requestInterceptorContext = readerContext(
                requestContext, requestContext.getEntityStream(),
                InputStream::readAllBytes);
        capturingFilter.filter(requestContext);

        // When: an error escaping would be rethrown by JUnit as unrecoverable, crashing the whole test run
        try {
            capturingFilter.aroundReadFrom(requestInterceptorContext);
        } catch (OutOfMemoryError e) {
            fail("Rendering the body failed the request", e);
        }

        // Then: the body is left out, and the capture released all the same
        assertEquals(List.of(), getReceivedMessages());
        assertNotNull(listAppender.findFirstMessage(MEMORY_FAILURE));
        assertTrue(closed.get());
    }

    @Test
    @DisplayName("Check a capture that could not be wired into the entity stream is released")
    void checkCaptureReleasedWhenItCannotBeWiredIn() throws Exception {
        // The capture exists by then, holding whatever it reserved, and nothing downstream ever sees it
        // again: releasing it is only possible where it was created
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        AtomicBoolean closed = new AtomicBoolean();
        LoggedFilter capturingFilter = capturingFilter(closed);
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ReaderInterceptorContext requestInterceptorContext = readerContext(
                requestContext, requestContext.getEntityStream(),
                InputStream::readAllBytes);
        doThrow(new IllegalStateException("Cannot wrap the entity stream"))
                .when(requestInterceptorContext).setInputStream(any());

        capturingFilter.filter(requestContext);

        // When
        assertDoesNotThrow(() -> capturingFilter.aroundReadFrom(requestInterceptorContext));

        // Then
        assertTrue(closed.get());
    }

    @Test
    @DisplayName("Check a capture whose sink fails neither breaks reading the entity nor logs part of it")
    void checkFailingCaptureSinkDoesNotBreakRequest() throws Exception {
        // The sink is written to as a branch of the entity stream itself, so what it throws surfaces in the
        // middle of the read: a capture spilling to a temporary file failing the way a full disk does
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        LoggedFilter failingSinkFilter = failingSinkFilter();
        PreMatchContainerRequestContext requestContext = getRequestContext();
        AtomicReference<String> entityRead = new AtomicReference<>();
        ReaderInterceptorContext requestInterceptorContext = readerContext(
                requestContext, requestContext.getEntityStream(),
                stream -> entityRead.set(new String(stream.readAllBytes(), UTF_8)));

        failingSinkFilter.filter(requestContext);

        // When
        assertDoesNotThrow(() -> failingSinkFilter.aroundReadFrom(requestInterceptorContext));
        failingSinkFilter.filter(requestContext, getEmptyResponseContext(requestContext));

        // Then: the entity was read whole, and the part captured before the failure is not logged as if it
        // were the body the application received
        assertEquals(INPUT, entityRead.get());
        assertEquals(List.of("Received POST /service"), getReceivedMessages());
    }

    @Test
    @DisplayName("Check a capture whose sink fails neither breaks writing the entity nor logs part of it")
    void checkFailingCaptureSinkDoesNotBreakResponse() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        LoggedFilter failingSinkFilter = failingSinkFilter();
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        WriterInterceptorContext responseInterceptorContext = writerContext(requestContext, output -> {
            output.write(OUTPUT.getBytes(UTF_8));
            written.write(OUTPUT.getBytes(UTF_8));
        });

        failingSinkFilter.filter(requestContext);
        failingSinkFilter.filter(requestContext, responseContext);

        // When
        assertDoesNotThrow(() -> failingSinkFilter.aroundWriteTo(responseInterceptorContext));

        // Then
        assertEquals(OUTPUT, written.toString(UTF_8));
        LogEvent event = listAppender.findFirstMessage("Processed");
        assertNotNull(event);
        assertFalse(event.getMessage().getFormattedMessage().contains(LF));
    }

    /**
     * Builds a filter whose body capture fails as soon as anything is written to its sink, standing in for
     * a capture spilling to a temporary file on a full disk.
     *
     * @return The filter created
     */
    LoggedFilter failingSinkFilter() {
        return filterWith(LoggedFilterConfiguration.builder()
                .bodyCapture(limit -> new BoundedLoggedBodyCapture(limit) {

                    @Override
                    public OutputStream sink() {
                        return new OutputStream() {

                            @Override
                            public void write(int b) throws IOException {
                                throw new IOException("No space left on device");
                            }

                        };
                    }

                })
                .build());
    }

    /**
     * Builds a filter capturing bodies with a capture standing in for one holding more than memory (a
     * temporary file, a pooled buffer, ...), recording whether it was given the chance to hand it back.
     *
     * @param closed The flag the capture raises once released
     * @return The filter created
     */
    LoggedFilter capturingFilter(AtomicBoolean closed) {
        return filterWith(LoggedFilterConfiguration.builder()
                .bodyCapture(limit -> new BoundedLoggedBodyCapture(limit) {

                    @Override
                    public void close() {
                        closed.set(true);
                    }

                })
                .build());
    }

    /**
     * Builds a filter whose body capture cannot be created.
     *
     * @return The filter created
     */
    LoggedFilter failingCaptureFilter() {
        return filterWith(LoggedFilterConfiguration.builder()
                .bodyCapture(limit -> {
                    throw new IllegalStateException("No room left to capture anything");
                })
                .build());
    }

    @Test
    @DisplayName("Check a field left out is not logged while every other one still is")
    void checkFieldLeftOut() throws Exception {
        // Unmapping a field was the obvious way to keep it out of the logs, and MDC rejected the null key
        // it then resolved to: every field described after it was lost, or every field of every request
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        LoggedFilter leavingOutFilter = filterWith(LoggedFilterConfiguration.builder()
                .withoutField(REQUEST_PARAMETERS)
                .build());
        PreMatchContainerRequestContext requestContext = getRequestContext();

        // When
        leavingOutFilter.filter(requestContext);
        leavingOutFilter.filter(requestContext, getEmptyResponseContext(requestContext));

        // Then
        assertNull(listAppender.findFirstMessage("Unable to log"));
        Map<String, String> mdc = listAppender.findFirstMessage("Processed").getContextData().toMap();
        assertFalse(mdc.containsValue(PARAMETERS));
        assertEquals("noBodyLogging", mdc.get(getMdcField(RESOURCE_METHOD)));
        assertTrue(MDC.getCopyOfContextMap().isEmpty());
    }

    @Test
    @DisplayName("Check putMdc(String, String) tracks the key so cleanupMdc removes it")
    void checkPutMdcTracksKeyForCleanup() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        loggingFilter.filter(requestContext);

        // When
        loggingFilter.putMdc("custom-key", "custom-value");

        // Then
        assertEquals("custom-value", MDC.get("custom-key"));
        loggingFilter.cleanupMdc(LoggedRequestState.find(requestContext));
        assertNull(MDC.get("custom-key"));
    }

    @Test
    @DisplayName("Check a request completed within another one leaves the other one's MDC entries tracked")
    void checkNestedCompletionKeepsTheEnclosingRequestTracked() throws Exception {
        // A request handler resuming the response of a request suspended earlier - a message posted to a
        // long-polling topic - completes that request in the middle of its own, on its own thread. The
        // entries of the enclosing request must still be removed once it completes in turn.
        setupTest(AnnotatedResource.class, "autoMappedHeaders");

        // Given: a request suspended earlier, left without being answered
        PreMatchContainerRequestContext suspended = getRequestContext();
        loggingFilter.filter(suspended);

        // And: the request resuming it, mapping a header of its own
        MockHttpRequest request = MockHttpRequest.create("GET", "example.company.com/service");
        request.header("User-Agent", "JUnit");
        PreMatchContainerRequestContext enclosing = new PreMatchContainerRequestContext(request);
        loggingFilter.filter(enclosing);

        // When: the suspended request completes within the enclosing one, then the enclosing one completes
        loggingFilter.filter(suspended, getEmptyResponseContext(suspended));
        assertEquals("JUnit", MDC.get("header-User-Agent"));
        loggingFilter.filter(enclosing, getEmptyResponseContext(enclosing));

        // Then
        assertNull(MDC.get("header-User-Agent"));
    }

    @Test
    @DisplayName("Check a request completed within another one is logged and answered as itself, leaving the other one intact")
    void checkNestedCompletionLoggedAsItself() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: a request suspended earlier, left without being answered
        PreMatchContainerRequestContext suspended = getRequestContext();
        loggingFilter.filter(suspended);
        String suspendedId = getMdc(REQUEST_ID);

        // And: the request resuming it, on this same thread
        PreMatchContainerRequestContext enclosing = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/topics"));
        loggingFilter.filter(enclosing);
        String enclosingId = getMdc(REQUEST_ID);

        // When: the suspended request completes within the enclosing one
        ContainerResponseContextImpl suspendedResponse = getEmptyResponseContext(suspended);
        loggingFilter.filter(suspended, suspendedResponse);

        // Then: it is logged and answered under its own identifier, and the enclosing request keeps its own
        assertEquals(List.of("Processed POST /service with status 204"), getProcessedMessages());
        assertEquals(suspendedId, getMdcLogged(REQUEST_ID));
        assertEquals(suspendedId, suspendedResponse.getHeaders().getFirst(REQUEST_ID_HEADER));
        assertEquals(enclosingId, getMdc(REQUEST_ID));
        assertEquals("/topics", getMdc(REQUEST_URI));

        // When: the enclosing request completes in turn
        loggingFilter.filter(enclosing, getEmptyResponseContext(enclosing));

        // Then
        assertEquals(List.of("Processed POST /service with status 204", "Processed GET /topics with status 204"),
                getProcessedMessages());
        assertEquals(enclosingId, listAppender.getMessages().getLast().getContextData().getValue(getMdcField(REQUEST_ID)));
        Map<String, String> left = MDC.getCopyOfContextMap();
        assertTrue(left == null || left.isEmpty(), () -> "Left in MDC: " + left);
    }

    @Test
    @DisplayName("Check a request completed on another thread is logged as itself, leaving nothing on that thread")
    void checkCompletionOnAnotherThreadLoggedAsItself() throws Exception {
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: a request whose response is resumed from a worker, without MdcPropagation
        PreMatchContainerRequestContext requestContext = getRequestContext();
        loggingFilter.filter(requestContext);
        String requestId = getMdc(REQUEST_ID);
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);
        ExecutorService worker = newSingleThreadExecutor();

        // When
        Map<String, String> left;
        try {
            left = worker.submit(() -> {
                loggingFilter.filter(requestContext, responseContext);
                return MDC.getCopyOfContextMap();
            }).get();
        } finally {
            worker.shutdown();
        }

        // Then
        assertEquals(List.of("Processed POST /service with status 204"), getProcessedMessages());
        assertEquals(requestId, getMdcLogged(REQUEST_ID));
        assertEquals(requestId, responseContext.getHeaders().getFirst(REQUEST_ID_HEADER));
        assertTrue(left == null || left.isEmpty(), () -> "Left on the worker: " + left);
    }

    /**
     * Gets the {@code "Processed ..."} lines logged so far, in the order they were logged, without the
     * duration they end with.
     *
     * @return The formatted messages of those lines
     */
    List<String> getProcessedMessages() {
        return listAppender.getMessages().stream()
                .map(event -> event.getMessage().getFormattedMessage())
                .filter(message -> message.startsWith("Processed"))
                .map(message -> message.replaceAll(" in \\d+ms$", ""))
                .toList();
    }

    void checkRequestLogging(LogType[] expectedRequestLogging, Class<? extends LoggedBodyFilter>[] expectedBodyFilters) {
        LogEvent logReceived = listAppender.findFirstMessage("Received");

        if (Set.of(expectedRequestLogging).contains(LogType.LOG)) {
            assertNotNull(logReceived);
            if (Set.of(expectedBodyFilters).contains(SensitiveBodyFilter.class)) {
                assertTrue(logReceived.getMessage().getFormattedMessage().contains(INPUT_FILTERED));
            } else {
                assertTrue(logReceived.getMessage().getFormattedMessage().contains(INPUT));
            }
        } else {
            assertNull(logReceived);
        }

        if (Set.of(expectedRequestLogging).contains(LogType.MDC)) {
            if (Set.of(expectedBodyFilters).contains(SensitiveBodyFilter.class)) {
                assertEquals(INPUT_FILTERED, getMdcLogged(REQUEST_BODY));
            } else {
                assertEquals(INPUT, getMdcLogged(REQUEST_BODY));
            }
        } else {
            assertNull(getMdcLogged(REQUEST_BODY));
        }
    }

    void checkResponseLogging(LogType[] expectedResponseLogging, Class<? extends LoggedBodyFilter>[] expectedBodyFilters) {
        LogEvent logProcessed = listAppender.findFirstMessage("Processed");
        assertNotNull(logProcessed);
        String message = logProcessed.getMessage().getFormattedMessage();

        if (Set.of(expectedResponseLogging).contains(LogType.LOG)) {
            if (Set.of(expectedBodyFilters).contains(SensitiveBodyFilter.class)) {
                assertTrue(message.contains(OUTPUT_FILTERED));
            } else {
                assertTrue(message.contains(OUTPUT));
            }
        } else {
            assertFalse(message.contains(OUTPUT_FILTERED));
            assertFalse(message.contains(OUTPUT));
        }

        if (Set.of(expectedResponseLogging).contains(LogType.MDC)) {
            if (Set.of(expectedBodyFilters).contains(SensitiveBodyFilter.class)) {
                assertEquals(OUTPUT_FILTERED, getMdcLogged(RESPONSE_BODY));
            } else {
                assertEquals(OUTPUT, getMdcLogged(RESPONSE_BODY));
            }
        } else {
            assertNull(getMdcLogged(RESPONSE_BODY));
        }
    }

    PreMatchContainerRequestContext getRequestContext() throws URISyntaxException {
        MockHttpRequest request = MockHttpRequest.create("POST", "example.company.com/service?" + PARAMETERS);
        request.setInputStream(IOUtils.toInputStream(INPUT, UTF_8));
        request.contentType(TEXT_PLAIN_TYPE);
        return new PreMatchContainerRequestContext(request);
    }

    ContainerResponseContextImpl getResponseContext(PreMatchContainerRequestContext request) {
        int responseStatus = 200;
        Headers<Object> headers = new Headers<>();
        headers.add(CONTENT_TYPE, TEXT_PLAIN_TYPE.toString());
        MockHttpResponse httpResponse = new MockHttpResponse();
        httpResponse.setStatus(responseStatus);
        BuiltResponse builtResponse = new BuiltResponse(responseStatus, headers, OUTPUT, null);
        return new ContainerResponseContextImpl(request.getHttpRequest(), httpResponse, builtResponse);
    }

    ContainerResponseContextImpl getEmptyResponseContext(PreMatchContainerRequestContext request) {
        return getEmptyResponseContext(request, 204);
    }

    ContainerResponseContextImpl getEmptyResponseContext(PreMatchContainerRequestContext request, int responseStatus) {
        Headers<Object> headers = new Headers<>();
        MockHttpResponse httpResponse = new MockHttpResponse();
        httpResponse.setStatus(responseStatus);
        BuiltResponse builtResponse = new BuiltResponse(responseStatus, headers, null, null);
        return new ContainerResponseContextImpl(request.getHttpRequest(), httpResponse, builtResponse);
    }

    String getMdc(LoggedField field) {
        return MDC.get(getMdcField(field));
    }

    String getMdcField(LoggedField field) {
        return loggingFilter.configuration().fieldName(field);
    }

    String getMdcLogged(LoggedField key) {
        return Optional.ofNullable(listAppender.findFirstMessage("Processed"))
                .map(LogEvent::getContextData)
                .map(mdc -> mdc.getValue(getMdcField(key)))
                .map(Object::toString)
                .orElse(null);
    }

    @Logged(@LoggedBody(value = {LogType.MDC, LogType.LOG}, filters = SensitiveBodyFilter.class))
    interface AnnotatedResource extends AnnotatedResourceParent {

        void inherit();

        @Override
        void inheritParent();

        @LoggedBody(value = {LogType.MDC, LogType.LOG}, filters = SensitiveBodyFilter.class)
        void bodyAsMdcAndLogWithFilter();

        @LoggedBody({LogType.MDC, LogType.LOG})
        void bodyAsMdcAndLog();

        @LoggedBody(value = LogType.MDC, filters = SensitiveBodyFilter.class)
        void bodyAsMdcWithFilter();

        @LoggedBody(value = LogType.LOG, filters = SensitiveBodyFilter.class)
        void bodyAsLogWithFilter();

        @LoggedBody(LogType.MDC)
        void bodyAsMdc();

        @LoggedBody(LogType.LOG)
        void bodyAsLog();

        @LoggedBody(value = LogType.MDC, targets = REQUEST)
        @LoggedBody(value = LogType.LOG, targets = RESPONSE)
        void bodyAsMix();

        @LoggedBody(value = {LogType.MDC, LogType.LOG}, filters = FailingBodyFilter.class)
        void bodyWithFailingFilter();

        @Logged
        void noBodyLogging();

        @Logged
        @LoggedMapping(type = QUERY, auto = true)
        void autoMappedQueryParameters();

        @Logged
        @LoggedMapping(type = HEADER, auto = true, mdcPrefix = "header-")
        void autoMappedHeaders();

        @Logged
        @LoggedMapping(type = HEADER, paramNames = "X-Internal-Secret")
        @LoggedMapping(type = HEADER, mdcKey = "agent", paramNames = "User-Agent")
        @LoggedMapping(type = HEADER, auto = true, mdcPrefix = "header-")
        void namedAndAutoMappedHeaders();

        @Logged
        @LoggedMapping(type = QUERY, mdcKey = "trace", paramNames = {"trace-id", "trace-id", "correlation-id"})
        void duplicatedParameterNames();

    }

    @Test
    @DisplayName("Check a request whose description cannot be read is still served")
    void checkFailureDescribingTheRequestDoesNotFailIt() throws Exception {
        // The request filter runs before the resource method does, so anything it lets out does not
        // merely lose the "Received ..." line, it answers a perfectly serviceable request with an error
        // having nothing to do with it
        // Given: a context failing to describe the request it carries, standing in for whatever can go
        // wrong while this provider reads one (a container returning the unexpected, a subclass
        // overriding one of these methods, an appender that ran out of disk, ...)
        Map<String, Object> properties = new HashMap<>();
        ContainerRequestContext failing = mock(ContainerRequestContext.class);
        doAnswer(invocation -> properties.get(invocation.getArgument(0, String.class)))
                .when(failing).getProperty(any());
        doAnswer(invocation -> properties.put(invocation.getArgument(0, String.class), invocation.getArgument(1, Object.class)))
                .when(failing).setProperty(any(), any());
        doThrow(new IllegalStateException("Cannot describe the request")).when(failing).getUriInfo();

        // When
        assertDoesNotThrow(() -> loggingFilter.filter(failing));

        // Then: the request is served, and its completion still finds the state this filter attached
        assertNotNull(LoggedRequestState.find(failing));
    }

    @Test
    @DisplayName("Check a response whose description fails is still served and completed")
    void checkFailureDescribingTheResponseDoesNotFailIt() throws Exception {
        // The resource method has already run by the time the response filter does, so anything it lets
        // out answers with a 500 a request the application served successfully
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: a response whose headers cannot be read to return the identifier in, standing in for
        // whatever can go wrong while this provider describes a response (a container returning the
        // unexpected, an appender that ran out of disk, ...)
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = spy(getEmptyResponseContext(requestContext));
        doThrow(new IllegalStateException("Cannot describe the response")).when(responseContext).getHeaders();
        loggingFilter.filter(requestContext);

        // When
        assertDoesNotThrow(() -> loggingFilter.filter(requestContext, responseContext));

        // Then: the request is still completed, the response logged and its MDC cleaned up
        assertNotNull(listAppender.findFirstMessage("Processed"));
        assertNull(MDC.get(getMdcField(REQUEST_ID)));
    }

    @Test
    @DisplayName("Check a header claimed by a named mapping is not mapped again by an automatic one sent in another casing")
    void checkAutoMappingHonoursNamedExclusionWhateverTheHeaderCasing() throws Exception {
        // HTTP header names are case-insensitive and HTTP/2 sends them lower cased, so the casing a
        // mapping declares is not the casing the client uses
        setupTest(AnnotatedResource.class, "namedAndAutoMappedHeaders");

        // Given
        MockHttpRequest request = MockHttpRequest.create("GET", "example.company.com/service");
        request.header("user-agent", "JUnit");
        request.header("x-internal-secret", "secret-value");
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(request);

        // When
        loggingFilter.filter(requestContext);

        // Then: the named mapping keeps its key, and the exclusion keeps the secret out of MDC entirely
        assertEquals("JUnit", MDC.get("agent"));
        assertNull(MDC.get("header-user-agent"));
        assertNull(MDC.get("header-User-Agent"));
        assertNull(MDC.get("header-x-internal-secret"));
        assertNull(MDC.get("header-X-Internal-Secret"));
    }

    @Test
    @DisplayName("Check a mapping repeating a parameter name does not fail the request")
    void checkDuplicatedParameterNamesAreTolerated() throws Exception {
        // A repeated name in an annotation is a typo, not a reason to answer every request to that
        // resource with a 500
        setupTest(AnnotatedResource.class, "duplicatedParameterNames");

        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service?trace-id=abc"));

        // When
        assertDoesNotThrow(() -> loggingFilter.filter(requestContext));

        // Then
        assertEquals("abc", MDC.get("trace"));
    }

    @Test
    @DisplayName("Check a named mapping reads its parameters in the order they are declared")
    void checkNamedMappingFollowsDeclarationOrder() throws Exception {
        // Set.of randomizes its iteration order per JVM run, which used to make the parameter picked
        // among several present differ from one restart of the application to the next
        setupTest(AnnotatedResource.class, "duplicatedParameterNames");

        // Given: both declared parameters are present, the first declared one must win
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service?correlation-id=second&trace-id=first"));

        // When
        loggingFilter.filter(requestContext);

        // Then
        assertEquals("first", MDC.get("trace"));
    }

    /**
     * Body filter standing in for any buggy one an application could declare (a regular expression
     * blowing up on an unexpected payload, a null dereference, ...).
     */
    public static class FailingBodyFilter implements LoggedBodyFilter {

        @Override
        public void filter(StringBuilder body) {
            throw new IllegalStateException("Filter bug");
        }

    }

    interface AnnotatedResourceParent {

        @Logged
        void inheritParent();

    }

}


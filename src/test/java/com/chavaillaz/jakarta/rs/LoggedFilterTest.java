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
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static java.lang.Integer.parseInt;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.chavaillaz.jakarta.rs.LoggedBody.LogType;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.ext.InterceptorContext;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.core.LogEvent;
import org.jboss.resteasy.core.Headers;
import org.jboss.resteasy.core.interception.jaxrs.ContainerResponseContextImpl;
import org.jboss.resteasy.core.interception.jaxrs.PreMatchContainerRequestContext;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.slf4j.event.Level;

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

    @Mock
    ContainerRequestContext containerRequestContext;

    @InjectMocks
    LoggedFilter loggingFilter;

    /**
     * Second half of the provider pair under test: the container registers it alongside the filter, at a
     * priority placing it after any entity coder, and it hands every body it captures back to the filter.
     */
    final LoggedBodyInterceptor bodyInterceptor = new LoggedBodyInterceptor();

    /**
     * Property map shared by every context mock of a test, see {@link #stubProperties(InterceptorContext)}.
     */
    final Map<String, Object> contextProperties = new HashMap<>();

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

    void setupTest(Class<?> type, String method) throws Exception {
        doReturn(type).when(resourceInfo).getResourceClass();
        Method resourceMethod = type.getDeclaredMethod(method);
        doReturn(resourceMethod).when(resourceInfo).getResourceMethod();

        // The filter's injected requestContext field is this mock, distinct from the real
        // ContainerRequestContext each test passes as a method argument (which is what a real
        // JAX-RS container would give it as the very same object): back it with a real map so
        // properties set through the field (e.g. by cleanupMdc's callers) can be read back
        lenient().doAnswer(invocation ->
                contextProperties.get(invocation.getArgument(0, String.class))
        ).when(containerRequestContext).getProperty(any());
        lenient().doAnswer(invocation -> {
            contextProperties.put(invocation.getArgument(0, String.class), invocation.getArgument(1, Object.class));
            return null;
        }).when(containerRequestContext).setProperty(any(), any());
    }

    /**
     * Stubs the property accessors of an interceptor context onto {@link #contextProperties}, the one
     * map every context mock of a test shares, as a real container shares a single property map for the
     * whole request across its filters and interceptors.
     *
     * @param context The interceptor context to stub
     */
    void stubProperties(InterceptorContext context) {
        lenient().doAnswer(invocation ->
                contextProperties.get(invocation.getArgument(0, String.class))
        ).when(context).getProperty(any());
        lenient().doAnswer(invocation -> {
            contextProperties.put(invocation.getArgument(0, String.class), invocation.getArgument(1, Object.class));
            return null;
        }).when(context).setProperty(any(), any());
    }

    /**
     * Builds a reader interceptor context driving the same two-provider chain a container would: the
     * first {@code proceed()}, made by {@link LoggedFilter} at its own priority, hands over to
     * {@link LoggedBodyInterceptor} (which the container places after the entity coder), and the second,
     * made by the capture the latter delegates back, runs the given read as the message body reader would.
     *
     * @param entity The entity stream the chain starts from
     * @param read   The read performed once the whole chain has wrapped the stream
     * @return The stubbed context
     * @throws IOException never, the stubbed proceed() only declares it
     */
    ReaderInterceptorContext readerContext(InputStream entity, ThrowingConsumer<InputStream> read) throws IOException {
        return readerContext(entity, read, bodyInterceptor);
    }

    /**
     * Builds a reader interceptor context driving the given interceptor chain, in the order a container
     * would invoke it, before performing the given read as the message body reader would.
     *
     * @param entity The entity stream the chain starts from
     * @param read   The read performed once the whole chain has wrapped the stream
     * @param chain  The interceptors to invoke, from the lowest to the highest priority
     * @return The stubbed context
     * @throws IOException never, the stubbed proceed() only declares it
     */
    ReaderInterceptorContext readerContext(InputStream entity, ThrowingConsumer<InputStream> read, ReaderInterceptor... chain) throws IOException {
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
        stubProperties(context);
        return context;
    }

    /**
     * Builds a writer interceptor context driving the same chain as {@link #readerContext}, in the
     * other direction.
     *
     * @param write The write performed once the whole chain has wrapped the stream
     * @return The stubbed context
     * @throws IOException never, the stubbed proceed() only declares it
     */
    WriterInterceptorContext writerContext(ThrowingConsumer<OutputStream> write) throws IOException {
        return writerContext(write, bodyInterceptor);
    }

    /**
     * Builds a writer interceptor context driving the given chain, as {@link #readerContext} does in the
     * other direction.
     *
     * @param write The write performed once the whole chain has wrapped the stream
     * @param chain The interceptors to invoke, from the lowest to the highest priority
     * @return The stubbed context
     * @throws IOException never, the stubbed proceed() only declares it
     */
    WriterInterceptorContext writerContext(ThrowingConsumer<OutputStream> write, WriterInterceptor... chain) throws IOException {
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
        stubProperties(context);
        return context;
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("arguments")
    @DisplayName("Check filter actions based on annotation")
    void checkFilterAction(Class<?> type, String method, LogType[] expectedRequestLogging, LogType[] expectedResponseLogging, Class<? extends LoggedBodyFilter>[] expectedBodyFilters) throws Exception {
        setupTest(type, method);

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ReaderInterceptorContext requestInterceptorContext = readerContext(requestContext.getEntityStream(), InputStream::readAllBytes);

        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        WriterInterceptorContext responseInterceptorContext = writerContext(output -> output.write(OUTPUT.getBytes(UTF_8)));

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
    @DisplayName("Check body logging configuration is resolved once per resource method and cached")
    void checkBodyConfigurationCaching() throws Exception {
        setupTest(AnnotatedResource.class, "bodyAsMdc");

        // When
        LoggedBodyConfiguration request = loggingFilter.getBodyConfiguration(REQUEST);
        LoggedBodyConfiguration response = loggingFilter.getBodyConfiguration(RESPONSE);

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
        loggingFilter.filter(getRequestContext());

        // When: a later callback sees no matched resource at all (stubbed leniently, as the whole point
        // is that the calls below no longer reach ResourceInfo)
        lenient().doReturn(null).when(resourceInfo).getResourceClass();
        lenient().doReturn(null).when(resourceInfo).getResourceMethod();

        // Then
        assertTrue(loggingFilter.getBodyConfiguration(REQUEST).isActive());
        assertTrue(loggingFilter.getBodyConfiguration(RESPONSE).isActive());
    }

    @Test
    @DisplayName("Check merged mappings are resolved once per resource method and cached")
    void checkMergedMappingsCaching() throws Exception {
        setupTest(AnnotatedResource.class, "autoMappedQueryParameters");

        // When
        Set<LoggedMapping> first = loggingFilter.getCachedMergedMappings();
        Set<LoggedMapping> second = loggingFilter.getCachedMergedMappings();

        // Then
        assertEquals(first, second);
        assertEquals(1, loggingFilter.resolver.mappingsCache.size());
    }

    @Test
    @DisplayName("Check the request body is captured after an entity coder decoded it")
    void checkRequestBodyCapturedAfterEntityCoder() throws Exception {
        // Interceptors run in ascending priority and each wraps the stream for the next, so capturing
        // from LoggedFilter's own (deliberately low) priority captured the bytes as they arrive on the
        // wire - gzip noise for a Content-Encoding: gzip request. LoggedBodyInterceptor runs after the
        // coder instead, and must therefore see the decoded entity.
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given: the request as it arrives on the wire, with a coder decoding it between the two
        ReaderInterceptor decoder = context -> {
            context.setInputStream(new GZIPInputStream(context.getInputStream()));
            return context.proceed();
        };
        ReaderInterceptorContext requestInterceptorContext = readerContext(
                new ByteArrayInputStream(gzip(INPUT)), InputStream::readAllBytes, decoder, bodyInterceptor);

        // When
        loggingFilter.filter(getRequestContext());
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
                output -> output.write(OUTPUT.getBytes(UTF_8)), encoder, bodyInterceptor);

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
        ReaderInterceptorContext requestInterceptorContext = readerContext(requestContext.getEntityStream(), stream -> {
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
        ReaderInterceptorContext requestInterceptorContext = readerContext(requestContext.getEntityStream(), InputStream::readAllBytes);
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
        loggingFilter.aroundReadFrom(readerContext(IOUtils.toInputStream(INPUT, UTF_8), InputStream::readAllBytes));
        loggingFilter.aroundReadFrom(readerContext(IOUtils.toInputStream(INPUT, UTF_8), InputStream::readAllBytes));

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
        loggingFilter.aroundReadFrom(readerContext(requestContext.getEntityStream(), InputStream::readAllBytes));

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
        // The per-request closeables can only remove entries from the thread that closes them, so a
        // request completing elsewhere (a resumed @Suspended response, a container that never reaches the
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
        contextProperties.clear();

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
        String oversized = "a".repeat(LoggedFilter.REQUEST_ID_MAX_LENGTH + 50);
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("GET", "example.company.com/service")
                        .header("X-Request-ID", oversized));

        // When
        loggingFilter.filter(requestContext);

        // Then
        String requestId = getMdc(REQUEST_ID);
        assertNotNull(requestId);
        assertEquals(LoggedFilter.REQUEST_ID_MAX_LENGTH, requestId.length());
        assertEquals(oversized.substring(0, LoggedFilter.REQUEST_ID_MAX_LENGTH), requestId);
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
        WriterInterceptorContext responseInterceptorContext = writerContext(output -> {
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
    @DisplayName("Check whatever was written to the response body is still logged when writing it then fails")
    void checkPartialResponseBodyLoggedOnWriteFailure() throws Exception {
        // A serialization error partway through, or a client disconnecting mid-write, must not discard
        // the bytes already produced: aroundReadFrom already guarantees this for the request body (see
        // its Javadoc), and aroundWriteTo must mirror it for the response body
        setupTest(AnnotatedResource.class, "bodyAsLog");

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        WriterInterceptorContext responseInterceptorContext = writerContext(output -> {
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
        assertEquals(requestId, responseContext.getHeaders().getFirst(LoggedFilter.REQUEST_ID_HEADER));
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
                .filter(LoggedFilter.REQUEST_ID_HEADER::equalsIgnoreCase)
                .count());
        assertEquals("chosen-by-the-application", responseContext.getHeaders().getFirst("x-request-id"));
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
        loggingFilter.logResponse("");

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
        WriterInterceptorContext responseInterceptorContext = writerContext(output -> output.write(OUTPUT.getBytes(UTF_8)));

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
        WriterInterceptorContext responseInterceptorContext = writerContext(output -> {
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
                requestContext.getEntityStream(),
                stream -> entityRead.set(new String(stream.readAllBytes(), UTF_8)));

        failingCaptureFilter.filter(requestContext);

        // When
        assertDoesNotThrow(() -> failingCaptureFilter.aroundReadFrom(requestInterceptorContext));

        // Then: the entity was read as if nothing had been asked of it, only without a body in the logs
        assertEquals(INPUT, entityRead.get());
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
        WriterInterceptorContext responseInterceptorContext = writerContext(output -> {
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
                requestContext.getEntityStream(),
                InputStream::readAllBytes);

        capturingFilter.filter(requestContext);

        // When
        capturingFilter.aroundReadFrom(requestInterceptorContext);

        // Then: the body was read from the capture, and the capture released afterwards
        assertNotNull(listAppender.findFirstMessage("Received"));
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
                requestContext.getEntityStream(),
                InputStream::readAllBytes);
        doThrow(new IllegalStateException("Cannot wrap the entity stream"))
                .when(requestInterceptorContext).setInputStream(any());

        capturingFilter.filter(requestContext);

        // When
        assertDoesNotThrow(() -> capturingFilter.aroundReadFrom(requestInterceptorContext));

        // Then
        assertTrue(closed.get());
    }

    /**
     * Builds a filter capturing bodies with a capture standing in for one holding more than memory (a
     * temporary file, a pooled buffer, ...), recording whether it was given the chance to hand it back.
     *
     * @param closed The flag the capture raises once released
     * @return The filter created
     */
    LoggedFilter capturingFilter(AtomicBoolean closed) {
        LoggedFilter capturingFilter = new LoggedFilter() {

            @Override
            protected LoggedBodyCapture createBodyCapture(int limit) {
                return new BoundedLoggedBodyCapture(limit) {

                    @Override
                    public void close() {
                        closed.set(true);
                    }

                };
            }

        };
        capturingFilter.resourceInfo = resourceInfo;
        capturingFilter.requestContext = containerRequestContext;
        return capturingFilter;
    }

    /**
     * Builds a filter whose body capture cannot be created, sharing the mocks the container would inject
     * into {@link #loggingFilter} as {@code @InjectMocks} does for it.
     *
     * @return The filter created
     */
    LoggedFilter failingCaptureFilter() {
        LoggedFilter failingCaptureFilter = new LoggedFilter() {

            @Override
            protected LoggedBodyCapture createBodyCapture(int limit) {
                throw new IllegalStateException("No room left to capture anything");
            }

        };
        failingCaptureFilter.resourceInfo = resourceInfo;
        failingCaptureFilter.requestContext = containerRequestContext;
        return failingCaptureFilter;
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
        loggingFilter.cleanupMdc();
        assertNull(MDC.get("custom-key"));
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
        return loggingFilter.mdcFields.get(field.name());
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
        doAnswer(invocation ->
                contextProperties.get(invocation.getArgument(0, String.class))
        ).when(containerRequestContext).getProperty(any());
        doAnswer(invocation -> {
            contextProperties.put(invocation.getArgument(0, String.class), invocation.getArgument(1, Object.class));
            return null;
        }).when(containerRequestContext).setProperty(any(), any());

        ContainerRequestContext failing = mock(ContainerRequestContext.class);
        doThrow(new IllegalStateException("Cannot describe the request")).when(failing).getUriInfo();

        // When
        assertDoesNotThrow(() -> loggingFilter.filter(failing));

        // Then: the request is served, and its completion still finds the state this filter attached
        assertNotNull(loggingFilter.findState());
    }

    @Test
    @DisplayName("Check a response whose description fails is still served and completed")
    void checkFailureDescribingTheResponseDoesNotFailIt() throws Exception {
        // The resource method has already run by the time the response filter does, so anything it lets
        // out answers with a 500 a request the application served successfully
        setupTest(AnnotatedResource.class, "noBodyLogging");

        // Given: a subclass failing to return the identifier, standing in for whatever can go wrong while
        // this provider describes a response (a container returning the unexpected, an appender that ran
        // out of disk, ...)
        LoggedFilter failingFilter = new LoggedFilter() {

            @Override
            protected void addRequestId(ContainerResponseContext responseContext) {
                throw new IllegalStateException("Cannot describe the response");
            }

        };
        failingFilter.resourceInfo = resourceInfo;
        failingFilter.requestContext = containerRequestContext;
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getEmptyResponseContext(requestContext);
        failingFilter.filter(requestContext);

        // When
        assertDoesNotThrow(() -> failingFilter.filter(requestContext, responseContext));

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


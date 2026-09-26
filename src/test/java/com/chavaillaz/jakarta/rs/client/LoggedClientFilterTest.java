package com.chavaillaz.jakarta.rs.client;

import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static com.chavaillaz.jakarta.rs.LoggedFilterConfiguration.isCredential;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.NO_LIMIT;
import static com.chavaillaz.jakarta.rs.internal.BodyCapturer.MEMORY_FAILURE;
import static com.chavaillaz.jakarta.rs.internal.Sanitizer.REQUEST_ID_MAX_LENGTH;
import static jakarta.ws.rs.HttpMethod.POST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import com.chavaillaz.jakarta.rs.AbstractFilterTest;
import com.chavaillaz.jakarta.rs.SensitiveBodyFilter;
import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.JsonMaskingBodyFilter;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

@DisplayName("Logged client filter")
@ExtendWith(MockitoExtension.class)
class LoggedClientFilterTest extends AbstractFilterTest {

    private final LoggedClientFilter filter = new LoggedClientFilter();

    private ClientRequestContext requestContext;
    private Map<String, Object> properties;
    private MultivaluedMap<String, Object> headers;

    @BeforeEach
    void setupRequestContext() {
        properties = new HashMap<>();
        headers = new MultivaluedHashMap<>();

        requestContext = mock(ClientRequestContext.class);
        lenient().doReturn(POST).when(requestContext).getMethod();
        lenient().doReturn(URI.create("https://service.company.com/article")).when(requestContext).getUri();
        lenient().doReturn(headers).when(requestContext).getHeaders();
        lenient().doAnswer(invocation -> properties.get(invocation.getArgument(0, String.class)))
                .when(requestContext).getProperty(any());
        lenient().doAnswer(invocation -> {
            properties.put(invocation.getArgument(0, String.class), invocation.getArgument(1, Object.class));
            return null;
        }).when(requestContext).setProperty(any(), any());
    }

    @Test
    @DisplayName("Check the correlation identifier is propagated from MDC to the outgoing header")
    void checkCorrelationIdPropagatedFromMdc() {
        // Given
        MDC.put("request-id", "abc-123");

        // When
        filter.filter(requestContext);

        // Then
        assertEquals("abc-123", headers.getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check a random correlation identifier is generated when absent from MDC")
    void checkCorrelationIdGeneratedWhenAbsent() {
        // When
        filter.filter(requestContext);

        // Then
        assertNotNull(headers.getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check a random correlation identifier is generated when blank in MDC")
    void checkCorrelationIdGeneratedWhenBlank() {
        // Given: an identifier the application put in MDC, blank, which would correlate nothing
        MDC.put("request-id", " ");

        // When
        filter.filter(requestContext);

        // Then
        assertDoesNotThrow(() -> UUID.fromString((String) headers.getFirst(REQUEST_ID_HEADER)));
    }

    @Test
    @DisplayName("Check the correlation identifier is propagated the way a LoggedFilter logs one it receives")
    void checkCorrelationIdSanitized() {
        // Given: an identifier the application put in MDC as it got it, which no HTTP client of the JDK sends
        MDC.put("request-id", "abc\r\nX-Forged: yes");

        // When
        filter.filter(requestContext);

        // Then
        assertEquals("abc  X-Forged: yes", headers.getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check an oversized correlation identifier is truncated")
    void checkCorrelationIdTruncated() {
        // Given
        MDC.put("request-id", "a".repeat(REQUEST_ID_MAX_LENGTH + 50));

        // When
        filter.filter(requestContext);

        // Then
        assertEquals("a".repeat(REQUEST_ID_MAX_LENGTH), headers.getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check the correlation identifier is read from the MDC key configured")
    void checkCorrelationIdReadFromConfiguredKey() {
        // Given: the MDC key a LoggedFilter renaming its request identifier puts it under
        MDC.put("request-id", "not-this-one");
        MDC.put("trace-id", "abc-123");
        LoggedClientFilter renamed = LoggedClientFilter.builder().correlationIdKey("trace-id").build();

        // When
        renamed.filter(requestContext);

        // Then
        assertEquals("abc-123", headers.getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check the correlation identifier is propagated in the header configured")
    void checkCorrelationIdPropagatedInConfiguredHeader() {
        // Given: the header the services called read their identifier from
        MDC.put("request-id", "abc-123");
        LoggedClientFilter tracing = LoggedClientFilter.builder().correlationIdHeader("X-Trace-ID").build();

        // When
        tracing.filter(requestContext);

        // Then
        assertEquals("abc-123", headers.getFirst("X-Trace-ID"));
        assertNull(headers.getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check a header configured the call already carries, in another casing, is not overwritten")
    void checkConfiguredCorrelationHeaderNotOverwritten() {
        // Given
        headers.putSingle("x-trace-id", "caller-supplied");
        MDC.put("request-id", "from-mdc");
        LoggedClientFilter tracing = LoggedClientFilter.builder().correlationIdHeader("X-Trace-ID").build();

        // When
        tracing.filter(requestContext);

        // Then
        assertEquals(1, headers.size());
        assertEquals("caller-supplied", headers.getFirst("x-trace-id"));
    }

    @Test
    @DisplayName("Check an already present correlation header is not overwritten")
    void checkCorrelationIdNotOverwritten() {
        // Given
        headers.putSingle(REQUEST_ID_HEADER, "caller-supplied");
        MDC.put("request-id", "from-mdc");

        // When
        filter.filter(requestContext);

        // Then
        assertEquals("caller-supplied", headers.getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check calling the request logs a Calling line")
    void checkFilterLogsCallingLine() {
        // When
        filter.filter(requestContext);

        // Then
        LogEvent event = listAppender.findFirstMessage("Calling");
        assertNotNull(event);
        assertEquals("Calling POST https://service.company.com/article", event.getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("Check the credentials a URI carries are masked in every line describing the call")
    void checkUriCredentialsMasked() {
        // Given: the password of the user information and an OAuth access token, which a URI logged whole
        // used to write to the logs of every call made
        doReturn(URI.create("https://jane:hunter2@service.company.com/article?topic=news&access_token=secret"))
                .when(requestContext).getUri();
        ClientResponseContext responseContext = mock(ClientResponseContext.class);
        doReturn(200).when(responseContext).getStatus();

        // When
        filter.filter(requestContext);
        filter.filter(requestContext, responseContext);

        // Then: what identifies the call stays readable
        String masked = "https://***@service.company.com/article?topic=news&access_token=***";
        assertEquals("Calling POST " + masked, listAppender.findFirstMessage("Calling").getMessage().getFormattedMessage());
        assertTrue(listAppender.findFirstMessage("Called").getMessage().getFormattedMessage().startsWith("Called POST " + masked + " "));
        assertEquals(masked, properties.get(LoggedClientFilter.REQUEST_URI_PROPERTY));
    }

    @Test
    @DisplayName("Check the query parameters the configuration reports as sensitive are masked, and only those")
    void checkConfiguredSensitiveParametersMasked() {
        // Given: the predicate a service is given for the requests it receives, extended with the key of a partner
        LoggedClientFilter extended = LoggedClientFilter.builder()
                .sensitiveParameters((type, name) -> isCredential(type, name) || "partner-key".equalsIgnoreCase(name))
                .build();
        LoggedClientFilter restricted = LoggedClientFilter.builder()
                .sensitiveParameters((type, name) -> false)
                .build();
        URI uri = URI.create("https://partner.company.com/a?Partner-Key=k&access_token=t&topic=news");

        // Then: asked about the query parameters by type, the predicate can be shared with the server side
        assertEquals("https://partner.company.com/a?Partner-Key=***&access_token=***&topic=news", extended.getLoggedUri(uri));
        assertEquals("https://partner.company.com/a?Partner-Key=k&access_token=t&topic=news", restricted.getLoggedUri(uri));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "https://service.company.com/article                   | https://service.company.com/article",
            "https://service.company.com/article?topic=news        | https://service.company.com/article?topic=news",
            "https://service.company.com/a?Access_Token=x&t=1      | https://service.company.com/a?Access_Token=***&t=1",
            "https://service.company.com/a?access%5Ftoken=x        | https://service.company.com/a?access%5Ftoken=***",
            "https://service.company.com/a?token&password=&x=token | https://service.company.com/a?token&password=***&x=token",
            "https://user@[::1]:8443/a?b=c#access_token=x          | https://***@[::1]:8443/a?b=c#access_token=x",
            // A host name with an underscore leaves java.net.URI without any user information to report
            "https://jane:hunter2@my_service:8080/a                 | https://***@my_service:8080/a",
            "https://jane:hunter2@my_service/a?access_token=x      | https://***@my_service/a?access_token=***",
            "https://my_service:8080/a?topic=news                  | https://my_service:8080/a?topic=news",
            "mailto:jane@company.com?password=x                    | mailto:jane@company.com?password=x"
    })
    @DisplayName("Check a URI is logged as given, but for the credentials it carries")
    void checkLoggedUri(String uri, String expected) {
        assertEquals(expected, filter.getLoggedUri(URI.create(uri)));
    }

    @Test
    @DisplayName("Check a configuration that cannot work is rejected by the builder rather than on every call")
    void checkInvalidConfigurationRejected() {
        // Accepted, each of them failed later on every single call: a body limit below -1 by leaving every
        // body out of the logs, a missing MDC key by losing the line announcing the call, a missing filter
        // by dropping every body it was meant to filter
        LoggedClientFilter.Builder builder = LoggedClientFilter.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.bodyLimit(-2));
        assertThrows(IllegalArgumentException.class, () -> builder.requestBodyLimit(-2));
        assertThrows(IllegalArgumentException.class, () -> builder.responseBodyLimit(Integer.MIN_VALUE));
        assertThrows(NullPointerException.class, () -> builder.correlationIdKey(null));
        assertThrows(IllegalArgumentException.class, () -> builder.correlationIdHeader(" "));
        assertThrows(NullPointerException.class, () -> builder.sensitiveParameters(null));
        assertThrows(NullPointerException.class, () -> builder.bodyFilters((LoggedBodyFilter) null));
        assertThrows(NullPointerException.class, () -> builder.bodyFilters(AppendA.class, null));
        assertDoesNotThrow(() -> builder.bodyLimit(0).bodyLimit(-1).build());
    }

    @Test
    @DisplayName("Check the response is logged exactly once with its status and duration")
    void checkResponseLogged() {
        // Given
        filter.filter(requestContext);
        ClientResponseContext responseContext = mock(ClientResponseContext.class);
        doReturn(200).when(responseContext).getStatus();

        // When
        filter.filter(requestContext, responseContext);

        // Then
        LogEvent event = listAppender.findFirstMessage("Called");
        assertNotNull(event);
        String message = event.getMessage().getFormattedMessage();
        assertEquals(1, listAppender.getMessages().stream()
                .filter(e -> e.getMessage().getFormattedMessage().startsWith("Called"))
                .count());
        assertNotEquals(-1, message.indexOf("status 200"));
    }

    @Test
    @DisplayName("Check the response is still logged, with a fallback duration, when a prior filter aborted the request")
    void checkResponseLoggedWhenRequestWasAborted() {
        // Given: filter(ClientRequestContext) below (where REQUEST_TIME_PROPERTY is normally recorded)
        // was skipped, as happens when a higher-priority filter calls requestContext.abortWith(...)
        // before this one runs; response filters still run for an aborted request
        ClientResponseContext responseContext = mock(ClientResponseContext.class);
        doReturn(503).when(responseContext).getStatus();

        // When
        filter.filter(requestContext, responseContext);

        // Then
        LogEvent event = listAppender.findFirstMessage("Called");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains("status 503"));
    }

    @ParameterizedTest(name = "status {0} logged at {1}")
    @CsvSource({"200, INFO", "302, INFO", "404, WARN", "429, WARN", "500, ERROR", "503, ERROR"})
    @DisplayName("Check the level of the call line follows the status of the response received")
    void checkResponseLevelFollowsStatus(int status, Level expectedLevel) {
        // Given
        filter.filter(requestContext);
        ClientResponseContext responseContext = mock(ClientResponseContext.class);
        doReturn(status).when(responseContext).getStatus();

        // When
        filter.filter(requestContext, responseContext);

        // Then
        LogEvent event = listAppender.findFirstMessage("Called");
        assertNotNull(event);
        assertEquals(expectedLevel.name(), event.getLevel().name());
    }

    @Test
    @DisplayName("Check a call the level override gives no level for is still logged, at its default level")
    void checkResponseLevelWithoutLevel() {
        // Given: an override covering the statuses it cares about, and returning null for the others
        LoggedClientFilter levelling = new LoggedClientFilter() {

            @Override
            protected Level getResponseLevel(int status) {
                return status == 404 ? Level.INFO : null;
            }

        };
        ClientResponseContext responseContext = mock(ClientResponseContext.class);
        doReturn(503).when(responseContext).getStatus();

        // When
        levelling.filter(requestContext);
        levelling.filter(requestContext, responseContext);

        // Then
        LogEvent event = listAppender.findFirstMessage("Called");
        assertNotNull(event, "No Called line was logged");
        assertEquals(Level.ERROR.name(), event.getLevel().name());
    }

    @Test
    @DisplayName("Check a call the level override fails for is still logged, at its default level")
    void checkResponseLevelFailing() {
        // Given
        LoggedClientFilter levelling = new LoggedClientFilter() {

            @Override
            protected Level getResponseLevel(int status) {
                throw new IllegalStateException("No level for " + status);
            }

        };
        ClientResponseContext responseContext = mock(ClientResponseContext.class);
        doReturn(503).when(responseContext).getStatus();

        // When
        levelling.filter(requestContext);
        levelling.filter(requestContext, responseContext);

        // Then: the failure is reported, and the line logged all the same
        LogEvent event = listAppender.findFirstMessage("Called");
        assertNotNull(event, "No Called line was logged");
        assertEquals(Level.ERROR.name(), event.getLevel().name());
        assertNotNull(listAppender.findFirstMessage("Unable to get the level of the client call"));
    }

    @Test
    @DisplayName("Check the request body is not captured when request body logging is not activated")
    void checkRequestBodyNotCapturedByDefault() throws Exception {
        // Given
        WriterInterceptorContext context = mock(WriterInterceptorContext.class);

        // When
        filter.captureRequestBody(context);

        // Then
        assertNull(listAppender.findFirstMessage("Request body"));
    }

    @Test
    @DisplayName("Check the request body is logged as a separate line when activated")
    void checkRequestBodyLogged() throws Exception {
        // Given
        filter.filter(requestContext);
        LoggedClientFilter bodyLoggingFilter = LoggedClientFilter.builder().logRequestBody().build();
        WriterInterceptorContext context = writerContext(properties, "Hello, world!");

        // When
        bodyLoggingFilter.captureRequestBody(context);

        // Then
        LogEvent event = listAppender.findFirstMessage("Request body");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains("Hello, world!"));
    }

    @Test
    @DisplayName("Check the request body is filtered before being logged")
    void checkRequestBodyFiltered() throws Exception {
        // Given
        filter.filter(requestContext);
        LoggedClientFilter bodyLoggingFilter = LoggedClientFilter.builder()
                .logRequestBody()
                .bodyFilters(SensitiveBodyFilter.class)
                .build();
        WriterInterceptorContext context = writerContext(properties, "{\"secret-code\": \"1234-ABCD\"}");

        // When
        bodyLoggingFilter.captureRequestBody(context);

        // Then
        LogEvent event = listAppender.findFirstMessage("Request body");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains("masked"));
        assertFalse(event.getMessage().getFormattedMessage().contains("1234-ABCD"));
    }

    @Test
    @DisplayName("Check body filters are applied in the order they were declared on the builder")
    void checkBodyFiltersAppliedInDeclarationOrder() throws Exception {
        // Given
        filter.filter(requestContext);
        LoggedClientFilter bodyLoggingFilter = LoggedClientFilter.builder()
                .logRequestBody()
                .bodyFilters(AppendA.class, AppendB.class)
                .build();
        WriterInterceptorContext context = writerContext(properties, "body");

        // When
        bodyLoggingFilter.captureRequestBody(context);

        // Then
        LogEvent event = listAppender.findFirstMessage("Request body");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains("bodyAB"));
    }

    @Test
    @DisplayName("Check filters given as instances apply after those given as classes, in the order given")
    void checkBodyFilterInstancesApplied() throws Exception {
        // Given: a configured built-in filter, whose configuration only an instance can carry
        filter.filter(requestContext);
        LoggedClientFilter bodyLoggingFilter = LoggedClientFilter.builder()
                .logRequestBody()
                .bodyFilters(new JsonMaskingBodyFilter("password"), body -> body.append("B"))
                .bodyFilters(AppendA.class)
                .build();
        WriterInterceptorContext context = writerContext(properties, "{\"password\":\"hunter2\"}");

        // When
        bodyLoggingFilter.captureRequestBody(context);

        // Then
        LogEvent event = listAppender.findFirstMessage("Request body");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().endsWith("{\"password\":\"***\"}AB"),
                event.getMessage().getFormattedMessage());
    }

    public static class AppendA implements LoggedBodyFilter {

        @Override
        public void filter(StringBuilder body) {
            body.append("A");
        }

    }

    public static class AppendB implements LoggedBodyFilter {

        @Override
        public void filter(StringBuilder body) {
            body.append("B");
        }

    }

    @Test
    @DisplayName("Check whatever was written to the request body is still logged when writing it then fails")
    void checkPartialRequestBodyLoggedOnWriteFailure() throws Exception {
        // Given
        filter.filter(requestContext);
        LoggedClientFilter bodyLoggingFilter = LoggedClientFilter.builder().logRequestBody().build();
        WriterInterceptorContext context = mock(WriterInterceptorContext.class);
        AtomicReference<OutputStream> output = new AtomicReference<>(new ByteArrayOutputStream());
        doAnswer(invocation -> output.get()).when(context).getOutputStream();
        doAnswer(invocation -> {
            output.set(invocation.getArgument(0, OutputStream.class));
            return null;
        }).when(context).setOutputStream(any());
        doAnswer(invocation -> {
            output.get().write("partial content".getBytes(UTF_8));
            throw new IOException("Connection reset");
        }).when(context).proceed();
        lenient().doAnswer(invocation -> properties.get(invocation.getArgument(0, String.class)))
                .when(context).getProperty(any());

        // When
        assertThrows(IOException.class, () -> bodyLoggingFilter.captureRequestBody(context));

        // Then
        LogEvent event = listAppender.findFirstMessage("Request body");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains("partial content"));
    }

    @Test
    @DisplayName("Check the response body is logged as a separate line when activated")
    void checkResponseBodyLogged() throws Exception {
        // Given
        LoggedClientFilter bodyLoggingFilter = LoggedClientFilter.builder().logResponseBody().build();
        ReaderInterceptorContext context = readerContext("Received content");

        // When
        Object result = bodyLoggingFilter.captureResponseBody(context);

        // Then
        assertEquals("read", result);
        LogEvent event = listAppender.findFirstMessage("Response body");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains("Received content"));
    }

    @Test
    @DisplayName("Check a response the calling code reads as a stream is logged once read to its end")
    void checkStreamedResponseBodyLogged() throws Exception {
        // Given: the entity stream handed over as it is, as for response.readEntity(InputStream.class)
        LoggedClientFilter bodyLoggingFilter = LoggedClientFilter.builder().logResponseBody().build();
        ReaderInterceptorContext context = readerContext("Received content");
        doAnswer(invocation -> context.getInputStream()).when(context).proceed();

        // When
        InputStream entity = (InputStream) bodyLoggingFilter.captureResponseBody(context);

        // Then: nothing is logged until the calling code read the stream
        assertNull(listAppender.findFirstMessage("Response body"));
        assertEquals("Received content", new String(entity.readAllBytes(), UTF_8));
        LogEvent event = listAppender.findFirstMessage("Response body");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().endsWith(LF + "Received content"));
    }

    @Test
    @DisplayName("Check registering the filter registers the interceptor capturing bodies on its behalf")
    void checkRegistrationRegistersBodyInterceptor() throws Exception {
        // Given
        filter.filter(requestContext);
        FeatureContext featureContext = mock(FeatureContext.class);
        LoggedClientFilter bodyLoggingFilter = LoggedClientFilter.builder().logRequestBody().logResponseBody().build();

        // When
        boolean enabled = bodyLoggingFilter.configure(featureContext);

        // Then: a single client.register(...) keeps covering bodies, which the interceptor hands back
        LoggedClientFilter.BodyInterceptor interceptor = registered(featureContext, LoggedClientFilter.BodyInterceptor.class);
        interceptor.aroundWriteTo(writerContext(properties, "Hello, world!"));
        interceptor.aroundReadFrom(readerContext("Received content"));
        assertTrue(enabled);
        assertNotNull(listAppender.findFirstMessage("Request body"));
        assertNotNull(listAppender.findFirstMessage("Response body"));
    }

    /**
     * Gets the component of the given type a feature registered on the given context.
     *
     * @param featureContext The context the feature was configured with
     * @param type           The type of the component to get
     * @param <T>            The type of the component
     * @return The component registered
     */
    static <T> T registered(FeatureContext featureContext, Class<T> type) {
        ArgumentCaptor<Object> registered = ArgumentCaptor.forClass(Object.class);
        verify(featureContext, atLeastOnce()).register(registered.capture());
        return type.cast(registered.getAllValues().stream()
                .filter(type::isInstance)
                .findFirst()
                .orElseGet(() -> fail("No " + type.getSimpleName() + " registered")));
    }

    long lines(String start) {
        return listAppender.getMessages().stream()
                .filter(event -> event.getMessage().getFormattedMessage().startsWith(start))
                .count();
    }

    @Test
    @DisplayName("Check registering the feature registers the filter logging the calls on its behalf")
    void checkRegistrationRegistersCallFilter() {
        // Given: what every runtime registers of the feature, the filter it registers itself
        FeatureContext featureContext = mock(FeatureContext.class);
        filter.configure(featureContext);
        ClientRequestFilter requestFilter = registered(featureContext, ClientRequestFilter.class);
        ClientResponseFilter responseFilter = registered(featureContext, ClientResponseFilter.class);
        ClientResponseContext responseContext = mock(ClientResponseContext.class);
        doReturn(200).when(responseContext).getStatus();
        MDC.put("request-id", "abc-123");

        // When
        assertDoesNotThrow(() -> requestFilter.filter(requestContext));
        assertDoesNotThrow(() -> responseFilter.filter(requestContext, responseContext));

        // Then
        assertEquals("abc-123", headers.getFirst(REQUEST_ID_HEADER));
        assertEquals(1, lines("Calling"));
        assertEquals(1, lines("Called"));
    }

    @Test
    @DisplayName("Check the filter is a feature alone, which no runtime calls next to the filter it registers")
    void checkFeatureAlone() {
        // Jersey and RESTEasy register a component for every contract it implements: one implementing a filter
        // contract as well would be called next to the filter it registers, logging every call twice
        assertArrayEquals(new Class<?>[]{Feature.class}, LoggedClientFilter.class.getInterfaces());
    }

    @Test
    @DisplayName("Check the filter is left for the application to register rather than discovered")
    void checkNotDiscoverable() {
        // A container discovering it in a deployment hands RESTEasy clients one configured by default, which
        // logs every call twice next to the one the application registers, and sends the identifier first
        assertFalse(LoggedClientFilter.class.isAnnotationPresent(Provider.class));
    }

    @Test
    @DisplayName("Check a response entity read twice is only logged once")
    void checkResponseBodyLoggedOnceWhenReadTwice() throws Exception {
        // A buffered entity can be read any number of times - bufferEntity() then readEntity() once per
        // type the calling code tries - and every read goes through the interceptors again
        LoggedClientFilter bodyLoggingFilter = LoggedClientFilter.builder().logResponseBody().build();

        // When
        bodyLoggingFilter.captureResponseBody(readerContext("Received content"));
        bodyLoggingFilter.captureResponseBody(readerContext("Received content"));

        // Then
        assertEquals(1, listAppender.getMessages().stream()
                .filter(event -> event.getMessage().getFormattedMessage().startsWith("Response body"))
                .count());
    }

    @Test
    @DisplayName("Check a call this provider cannot describe is still made")
    void checkFailureDescribingTheCallDoesNotFailIt() {
        // Given: a context failing to describe the request it carries, standing in for whatever can go
        // wrong while this provider reads one (a Client implementation returning the unexpected, a
        // subclass overriding one of these methods, an appender that ran out of disk, ...)
        doThrow(new IllegalStateException("Cannot describe the request")).when(requestContext).getUri();
        ClientResponseContext responseContext = mock(ClientResponseContext.class);
        lenient().doReturn(200).when(responseContext).getStatus();

        // When
        assertDoesNotThrow(() -> filter.filter(requestContext));
        assertDoesNotThrow(() -> filter.filter(requestContext, responseContext));

        // Then: the call went out, correlated with the service it reaches, only without the lines describing it
        assertNotNull(headers.getFirst(REQUEST_ID_HEADER));
        assertNull(listAppender.findFirstMessage("Calling"));
        assertNull(listAppender.findFirstMessage("Called"));
    }

    @Test
    @DisplayName("Check a capture is released once what it collected has been logged")
    void checkCaptureReleasedOnceLogged() throws Exception {
        // A capture holding more than memory - one spilling a large body to a temporary file - has
        // nowhere to give it back other than close()
        filter.filter(requestContext);
        ReleasingBodyCapture capture = new ReleasingBodyCapture();
        LoggedClientFilter bodyLoggingFilter = capturingFilter(capture);

        // When
        bodyLoggingFilter.captureRequestBody(writerContext(properties, "Hello, world!"));

        // Then: the body was read from the capture, and the capture released afterwards
        assertNotNull(listAppender.findFirstMessage("Request body"));
        assertTrue(capture.closed);
    }

    @Test
    @DisplayName("Check a capture is released even when what it collected cannot be read")
    void checkCaptureReleasedWhenContentFails() throws Exception {
        // Rendering a captured body is exactly the step that fails on a payload nobody expected, which
        // is precisely when a temporary file must not be left behind
        filter.filter(requestContext);
        ReleasingBodyCapture capture = new ReleasingBodyCapture() {

            @Override
            public String content(Set<LoggedBodyFilter> filters, MediaType mediaType) {
                throw new IllegalStateException("Unreadable body");
            }

        };

        // When
        assertDoesNotThrow(() -> capturingFilter(capture).captureRequestBody(writerContext(properties, "Hello, world!")));

        // Then
        assertNull(listAppender.findFirstMessage("Request body"));
        assertTrue(capture.closed);
    }

    @Test
    @DisplayName("Check a body too large to render with the memory available is left out without failing the call")
    void checkBodyTooLargeToRenderLeftOut() throws Exception {
        // Given: a capture whose rendering needs more memory than the heap has left
        filter.filter(requestContext);
        ReleasingBodyCapture capture = new ReleasingBodyCapture() {

            @Override
            public String content(Set<LoggedBodyFilter> filters, MediaType mediaType) {
                throw new OutOfMemoryError("Java heap space");
            }

        };

        // When: an error escaping would be rethrown by JUnit as unrecoverable, crashing the whole test run
        try {
            capturingFilter(capture).captureRequestBody(writerContext(properties, "Hello, world!"));
        } catch (OutOfMemoryError e) {
            fail("Rendering the body failed the call", e);
        }

        // Then
        assertNull(listAppender.findFirstMessage("Request body"));
        assertNotNull(listAppender.findFirstMessage(MEMORY_FAILURE));
        assertTrue(capture.closed);
    }

    @Test
    @DisplayName("Check a capture that could not be wired into the entity stream is released")
    void checkCaptureReleasedWhenItCannotBeWiredIn() throws Exception {
        // The capture exists by then, holding whatever it reserved, and nothing downstream ever sees it
        // again: releasing it is only possible where it was created
        filter.filter(requestContext);
        ReleasingBodyCapture capture = new ReleasingBodyCapture();
        WriterInterceptorContext context = mock(WriterInterceptorContext.class);
        doThrow(new IllegalStateException("Cannot wrap the entity stream")).when(context).getOutputStream();

        // When
        assertDoesNotThrow(() -> capturingFilter(capture).captureRequestBody(context));

        // Then: the entity was written as if nothing had been asked of it, and nothing was left held
        verify(context).proceed();
        assertTrue(capture.closed);
    }

    /**
     * Builds a filter logging request bodies through the given capture, standing in for whatever
     * {@code createBodyCapture} an application plugs in.
     *
     * @param capture The capture the filter must use
     * @return The filter created
     */
    LoggedClientFilter capturingFilter(LoggedBodyCapture capture) {
        return new LoggedClientFilter(LoggedClientFilter.builder().logRequestBody()) {

            @Override
            protected LoggedBodyCapture createBodyCapture(int limit) {
                return capture;
            }

        };
    }

    /**
     * Capture standing in for one holding more than memory (a temporary file, a pooled buffer, ...),
     * recording whether it was given the chance to hand it back.
     */
    static class ReleasingBodyCapture extends BoundedLoggedBodyCapture {

        boolean closed = false;

        ReleasingBodyCapture() {
            super(NO_LIMIT);
        }

        @Override
        public void close() {
            closed = true;
        }

    }

    @Test
    @DisplayName("Check a body capture that cannot be created neither breaks the call nor skips the entity")
    void checkFailingBodyCaptureDoesNotBreakCall() throws Exception {
        // Putting the capture in place is the one piece of logging work happening before proceed() rather
        // than in a finally block after it, so a failure there does not merely lose a log line: the entity
        // is never written at all and the call fails with an error having nothing to do with it
        filter.filter(requestContext);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        LoggedClientFilter bodyLoggingFilter = new LoggedClientFilter(LoggedClientFilter.builder().logRequestBody()) {

            @Override
            protected LoggedBodyCapture createBodyCapture(int limit) {
                throw new IllegalStateException("No room left to capture anything");
            }

        };

        // Given
        WriterInterceptorContext context = mock(WriterInterceptorContext.class);
        doReturn(written).when(context).getOutputStream();
        doAnswer(invocation -> {
            context.getOutputStream().write("Hello, world!".getBytes(UTF_8));
            return null;
        }).when(context).proceed();

        // When
        assertDoesNotThrow(() -> bodyLoggingFilter.captureRequestBody(context));

        // Then: the entity was written as if nothing had been asked of it, only without a body in the logs
        assertEquals("Hello, world!", written.toString(UTF_8));
        assertNull(listAppender.findFirstMessage("Request body"));
    }

    private WriterInterceptorContext writerContext(Map<String, Object> sharedProperties, String body) throws IOException {
        WriterInterceptorContext context = mock(WriterInterceptorContext.class);
        AtomicReference<OutputStream> output = new AtomicReference<>(new ByteArrayOutputStream());
        doAnswer(invocation -> output.get()).when(context).getOutputStream();
        doAnswer(invocation -> {
            output.set(invocation.getArgument(0, OutputStream.class));
            return null;
        }).when(context).setOutputStream(any());
        doAnswer(invocation -> {
            output.get().write(body.getBytes(UTF_8));
            return null;
        }).when(context).proceed();
        lenient().doAnswer(invocation -> sharedProperties.get(invocation.getArgument(0, String.class)))
                .when(context).getProperty(any());
        return context;
    }

    private ReaderInterceptorContext readerContext(String body) throws IOException {
        ReaderInterceptorContext context = mock(ReaderInterceptorContext.class);
        AtomicReference<InputStream> input = new AtomicReference<>(IOUtils.toInputStream(body, UTF_8));
        lenient().doAnswer(invocation -> input.get()).when(context).getInputStream();
        lenient().doAnswer(invocation -> {
            input.set(invocation.getArgument(0, InputStream.class));
            return null;
        }).when(context).setInputStream(any());
        lenient().doAnswer(invocation -> {
            input.get().readAllBytes();
            return "read";
        }).when(context).proceed();
        lenient().doAnswer(invocation -> properties.get(invocation.getArgument(0, String.class)))
                .when(context).getProperty(any());
        lenient().doAnswer(invocation -> properties.put(invocation.getArgument(0, String.class), invocation.getArgument(1, Object.class)))
                .when(context).setProperty(any(), any());
        return context;
    }

}

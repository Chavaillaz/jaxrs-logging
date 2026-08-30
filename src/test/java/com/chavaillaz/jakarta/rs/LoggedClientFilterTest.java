package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static jakarta.ws.rs.HttpMethod.POST;
import static java.nio.charset.StandardCharsets.UTF_8;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

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

    @Test
    @DisplayName("Check the request body is not captured when request body logging is not activated")
    void checkRequestBodyNotCapturedByDefault() throws Exception {
        // Given
        WriterInterceptorContext context = mock(WriterInterceptorContext.class);

        // When
        filter.aroundWriteTo(context);

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
        bodyLoggingFilter.aroundWriteTo(context);

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
        bodyLoggingFilter.aroundWriteTo(context);

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
        bodyLoggingFilter.aroundWriteTo(context);

        // Then
        LogEvent event = listAppender.findFirstMessage("Request body");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains("bodyAB"));
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
        assertThrows(IOException.class, () -> bodyLoggingFilter.aroundWriteTo(context));

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
        Object result = bodyLoggingFilter.aroundReadFrom(context);

        // Then
        assertEquals("read", result);
        LogEvent event = listAppender.findFirstMessage("Response body");
        assertNotNull(event);
        assertTrue(event.getMessage().getFormattedMessage().contains("Received content"));
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
        doAnswer(invocation -> input.get()).when(context).getInputStream();
        doAnswer(invocation -> {
            input.set(invocation.getArgument(0, InputStream.class));
            return null;
        }).when(context).setInputStream(any());
        doAnswer(invocation -> {
            input.get().readAllBytes();
            return "read";
        }).when(context).proceed();
        lenient().doAnswer(invocation -> properties.get(invocation.getArgument(0, String.class)))
                .when(context).getProperty(any());
        return context;
    }

}

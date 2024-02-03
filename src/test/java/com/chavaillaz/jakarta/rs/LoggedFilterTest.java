package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.DURATION;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_CLASS;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_STATUS;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static java.lang.Integer.parseInt;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.chavaillaz.jakarta.rs.Logged.LogType;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.ext.Providers;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.core.LogEvent;
import org.jboss.resteasy.core.Headers;
import org.jboss.resteasy.core.interception.jaxrs.ContainerResponseContextImpl;
import org.jboss.resteasy.core.interception.jaxrs.PreMatchContainerRequestContext;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.jboss.resteasy.plugins.providers.DefaultTextPlain;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
@DisplayName("Original filter")
class LoggedFilterTest extends AbstractFilterTest {

    private static final LogType[] NO_LOGGING = new LogType[]{};
    private static final LogType[] LOG_LOGGING = new LogType[]{LogType.LOG};
    private static final LogType[] MDC_LOGGING = new LogType[]{LogType.MDC};
    private static final LogType[] ALL_LOGGING = new LogType[]{LogType.MDC, LogType.LOG};
    private static final Class[] NO_FILTERING = new Class[]{};
    private static final Class[] SENSITIVE_FILTERING = new Class[]{SensitiveBodyFilter.class};
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
    Providers providers;

    @InjectMocks
    LoggedFilter loggingFilter;

    static Stream<Arguments> arguments() {
        return Stream.of(
                Arguments.of(AnnotatedResource.class, "inherit", ALL_LOGGING, ALL_LOGGING, SENSITIVE_FILTERING),
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
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("arguments")
    @DisplayName("Check filter actions based on annotation")
    void checkFilterAction(Class<?> type, String method, LogType[] expectedRequestLogging, LogType[] expectedResponseLogging, Class<? extends LoggedBodyFilter>[] expectedBodyFilters) throws Exception {
        setupTest(type, method);

        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        if (!Set.of(expectedResponseLogging).isEmpty()) {
            doReturn(new DefaultTextPlain()).when(providers).getMessageBodyWriter(any(), any(), any(), any());
        }

        // When
        loggingFilter.filter(requestContext);

        // Then
        assertNotNull(getMdc(REQUEST_ID));
        assertEquals(requestContext.getUriInfo().getPath(), getMdc(REQUEST_URI));
        assertEquals(requestContext.getMethod(), getMdc(REQUEST_METHOD));
        assertEquals(type.getSimpleName(), getMdc(RESOURCE_CLASS));
        assertEquals(method, getMdc(RESOURCE_METHOD));

        // When
        loggingFilter.filter(requestContext, responseContext);

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
        MockHttpRequest request = MockHttpRequest.create("POST", "example.company.com/service");
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

    @Logged(requestBody = {LogType.MDC, LogType.LOG}, responseBody = {LogType.MDC, LogType.LOG}, filersBody = {SensitiveBodyFilter.class})
    interface AnnotatedResource {

        void inherit();

        @Logged(requestBody = {LogType.MDC, LogType.LOG}, responseBody = {LogType.MDC, LogType.LOG}, filersBody = SensitiveBodyFilter.class)
        void bodyAsMdcAndLogWithFilter();

        @Logged(requestBody = {LogType.MDC, LogType.LOG}, responseBody = {LogType.MDC, LogType.LOG})
        void bodyAsMdcAndLog();

        @Logged(requestBody = LogType.MDC, responseBody = LogType.MDC, filersBody = SensitiveBodyFilter.class)
        void bodyAsMdcWithFilter();

        @Logged(requestBody = LogType.LOG, responseBody = LogType.LOG, filersBody = SensitiveBodyFilter.class)
        void bodyAsLogWithFilter();

        @Logged(requestBody = LogType.MDC, responseBody = LogType.MDC)
        void bodyAsMdc();

        @Logged(requestBody = LogType.LOG, responseBody = LogType.LOG)
        void bodyAsLog();

        @Logged(requestBody = LogType.MDC, responseBody = LogType.LOG)
        void bodyAsMix();

        @Logged
        void noBodyLogging();

    }

}


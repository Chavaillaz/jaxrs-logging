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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.net.URISyntaxException;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
@DisplayName("Original filter")
@Logged(requestBody = true, responseBody = true)
class LoggedFilterTest extends AbstractFilterTest {

    private static final String INPUT = "input";
    private static final String OUTPUT = "output";

    @Mock
    ResourceInfo resourceInfo;

    @Mock
    Providers providers;

    @InjectMocks
    LoggedFilter requestLoggingFilter;

    @Override
    void setupTest() {
        super.setupTest();
        // Returns this method and class as the resource matched by the queries in tests
        doReturn(new Object() {}.getClass().getEnclosingMethod()).when(resourceInfo).getResourceMethod();
        doReturn(this.getClass()).when(resourceInfo).getResourceClass();
    }

    @Test
    @DisplayName("Check filter on request sets up MDC fields")
    void filterRequestCheckMdc() throws URISyntaxException {
        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();

        // When
        requestLoggingFilter.filter(requestContext);

        // Then
        assertNotNull(getMdc(REQUEST_ID));
        assertEquals(requestContext.getUriInfo().getPath(), getMdc(REQUEST_URI));
        assertEquals(requestContext.getMethod(), getMdc(REQUEST_METHOD));
        assertEquals(getClass().getSimpleName(), getMdc(RESOURCE_CLASS));
        assertEquals("setupTest", getMdc(RESOURCE_METHOD));
    }

    @Test
    @DisplayName("Check filter on request and response writes log with MDC fields")
    void filterResponseCheckLog() throws Exception {
        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();
        ContainerResponseContextImpl responseContext = getResponseContext(requestContext);
        doReturn(new DefaultTextPlain()).when(providers).getMessageBodyWriter(any(), any(), any(), any());

        // When
        requestLoggingFilter.filter(requestContext);
        requestLoggingFilter.filter(requestContext, responseContext);

        // Then
        assertNotNull(getMdcLogged(REQUEST_ID));
        assertEquals(requestContext.getUriInfo().getPath(), getMdcLogged(REQUEST_URI));
        assertEquals(requestContext.getMethod(), getMdcLogged(REQUEST_METHOD));
        assertEquals(getClass().getSimpleName(), getMdcLogged(RESOURCE_CLASS));
        assertEquals("setupTest", getMdcLogged(RESOURCE_METHOD));
        assertEquals(responseContext.getHttpResponse().getStatus(), parseInt(getMdcLogged(RESPONSE_STATUS)));
        assertNotNull(getMdcLogged(DURATION));
        assertEquals(INPUT, getMdcLogged(REQUEST_BODY));
        assertEquals(OUTPUT, getMdcLogged(RESPONSE_BODY));
    }

    private PreMatchContainerRequestContext getRequestContext() throws URISyntaxException {
        MockHttpRequest request = MockHttpRequest.create("POST", "example.company.com/service");
        request.setInputStream(IOUtils.toInputStream(INPUT, UTF_8));
        request.contentType(TEXT_PLAIN_TYPE);
        return new PreMatchContainerRequestContext(request);
    }

    private ContainerResponseContextImpl getResponseContext(PreMatchContainerRequestContext request) {
        int responseStatus = 200;
        Headers<Object> headers = new Headers<>();
        headers.add(CONTENT_TYPE, TEXT_PLAIN_TYPE.toString());
        MockHttpResponse httpResponse = new MockHttpResponse();
        httpResponse.setStatus(responseStatus);
        BuiltResponse builtResponse = new BuiltResponse(responseStatus, headers, OUTPUT, null);
        return new ContainerResponseContextImpl(request.getHttpRequest(), httpResponse, builtResponse);
    }

    private String getMdc(LoggedField field) {
        return MDC.get(getMdcField(field));
    }

    private String getMdcField(LoggedField field) {
        return requestLoggingFilter.mdcFields.get(field.name());
    }

    private String getMdcLogged(LoggedField key) {
        return LIST_APPENDER.getMessages().stream()
                .filter(log -> log.getMessage().getFormattedMessage().startsWith("Processed"))
                .map(LogEvent::getContextData)
                .map(mdc -> mdc.getValue(getMdcField(key)))
                .map(Object::toString)
                .findFirst()
                .orElse(null);
    }

}


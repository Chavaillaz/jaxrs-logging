package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.UserLoggingConfiguration.REQUEST_IDENTIFIER;
import static com.chavaillaz.jakarta.rs.UserLoggingConfiguration.USER_AGENT;
import static com.chavaillaz.jakarta.rs.UserLoggingConfiguration.USER_ID;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import jakarta.ws.rs.container.ResourceInfo;
import java.net.URISyntaxException;
import java.util.Map;

import org.jboss.resteasy.core.Headers;
import org.jboss.resteasy.core.interception.jaxrs.ContainerResponseContextImpl;
import org.jboss.resteasy.core.interception.jaxrs.PreMatchContainerRequestContext;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("Configuration of an application")
class UserLoggingConfigurationTest extends AbstractFilterTest {

    final LoggedFeature feature = new LoggedFeature(new UserLoggingConfiguration().getContext(LoggedFeature.class));

    /**
     * Builds the filter the feature registers on the given resource method.
     *
     * @param type   The resource class
     * @param method The name of the resource method
     * @return The filter created
     * @throws Exception if the class declares no such method
     */
    MethodFilter filterOf(Class<?> type, String method) throws Exception {
        ResourceInfo resourceInfo = mock(ResourceInfo.class);
        doReturn(type).when(resourceInfo).getResourceClass();
        doReturn(type.getDeclaredMethod(method)).when(resourceInfo).getResourceMethod();
        return feature.filterFor(resourceInfo);
    }

    @Test
    @DisplayName("Check a request is described with the entries of the application")
    void checkApplicationEntriesPut() throws Exception {
        // Given
        PreMatchContainerRequestContext requestContext = getRequestContext();

        // When
        filterOf(AnnotatedResource.class, "inherit").filter(requestContext);

        // Then
        assertEquals("CaseId", MDC.get(REQUEST_IDENTIFIER));
        assertEquals("Doe", MDC.get(USER_ID));
        assertEquals("Opera", MDC.get(USER_AGENT));
    }

    @Test
    @DisplayName("Check the user agent is put for the resources whose annotation asks for it alone")
    void checkUserAgentPutWhereAsked() throws Exception {
        // When
        filterOf(OtherResource.class, "other").filter(getRequestContext());

        // Then
        assertEquals("Doe", MDC.get(USER_ID));
        assertNull(MDC.get(USER_AGENT));
    }

    @Test
    @DisplayName("Check a request without the custom header falls back to a random identifier")
    void checkFilterFallsBackToRandomRequestId() throws Exception {
        // Given
        PreMatchContainerRequestContext requestContext = new PreMatchContainerRequestContext(
                MockHttpRequest.create("POST", "example.company.com/service")
                        .content("Hello, world!".getBytes())
                        .contentType(TEXT_PLAIN_TYPE));

        // When
        filterOf(AnnotatedResource.class, "inherit").filter(requestContext);

        // Then
        assertNotNull(MDC.get(REQUEST_IDENTIFIER));
    }

    @Test
    @DisplayName("Check completing the request removes the entries of the application")
    void checkApplicationEntriesRemoved() throws Exception {
        // Given
        MethodFilter filter = filterOf(AnnotatedResource.class, "inherit");
        PreMatchContainerRequestContext requestContext = getRequestContext();
        filter.filter(requestContext);

        // When
        filter.filter(requestContext, new ContainerResponseContextImpl(requestContext.getHttpRequest(), new MockHttpResponse(),
                new BuiltResponse(204, new Headers<>(), null, null)));

        // Then
        Map<String, String> left = MDC.getCopyOfContextMap();
        assertTrue(left == null || left.isEmpty(), () -> "Left in MDC: " + left);
    }

    private PreMatchContainerRequestContext getRequestContext() throws URISyntaxException {
        return new PreMatchContainerRequestContext(
                MockHttpRequest.create("POST", "example.company.com/service")
                        .header("X-Case-ID", "CaseId")
                        .header("User-Agent", "Opera")
                        .content("Hello, world!".getBytes())
                        .contentType(TEXT_PLAIN_TYPE));
    }

    @Logged
    @UserLogged(userAgent = true)
    interface AnnotatedResource {

        void inherit();

    }

    @Logged
    interface OtherResource {

        void other();

    }

}

package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.UserLoggedFilter.REQUEST_IDENTIFIER;
import static com.chavaillaz.jakarta.rs.UserLoggedFilter.USER_AGENT;
import static com.chavaillaz.jakarta.rs.UserLoggedFilter.USER_ID;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;

import java.net.URISyntaxException;

import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.ext.Providers;
import org.jboss.resteasy.core.interception.jaxrs.PreMatchContainerRequestContext;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
@DisplayName("Custom filter")
@UserLogged(logging = @Logged(requestBody = true, responseBody = true), userAgent = true)
class UserLoggedFilterTest extends AbstractFilterTest {

    @Mock
    ResourceInfo resourceInfo;

    @Mock
    Providers providers;

    @InjectMocks
    UserLoggedFilter requestLoggingFilter;

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
        assertEquals("CaseId", MDC.get(REQUEST_IDENTIFIER));
        assertEquals("Doe", MDC.get(USER_ID));
        assertEquals("Opera", MDC.get(USER_AGENT));
    }

    private PreMatchContainerRequestContext getRequestContext() throws URISyntaxException {
        return new PreMatchContainerRequestContext(
                MockHttpRequest.create("POST", "example.company.com/service")
                        .header("X-Case-ID", "CaseId")
                        .header("User-Agent", "Opera")
                        .contentType(TEXT_PLAIN_TYPE));
    }

}

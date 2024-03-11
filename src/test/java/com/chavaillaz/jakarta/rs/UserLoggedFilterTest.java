package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.UserLoggedFilter.REQUEST_IDENTIFIER;
import static com.chavaillaz.jakarta.rs.UserLoggedFilter.USER_AGENT;
import static com.chavaillaz.jakarta.rs.UserLoggedFilter.USER_ID;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;

import java.lang.reflect.Method;
import java.net.URISyntaxException;

import com.chavaillaz.jakarta.rs.Logged.LogType;
import jakarta.ws.rs.container.ResourceInfo;
import org.jboss.resteasy.core.interception.jaxrs.PreMatchContainerRequestContext;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@DisplayName("Custom filter")
@ExtendWith(MockitoExtension.class)
class UserLoggedFilterTest extends AbstractFilterTest {

    @Mock
    ResourceInfo resourceInfo;

    @InjectMocks
    UserLoggedFilter requestLoggingFilter;

    @Override
    @BeforeEach
    void setupTest() throws Exception {
        super.setupTest();
        Class<?> type = AnnotatedResource.class;
        doReturn(type).when(resourceInfo).getResourceClass();
        Method resourceMethod = type.getDeclaredMethod("inherit");
        doReturn(resourceMethod).when(resourceInfo).getResourceMethod();
    }

    @Test
    @DisplayName("Check filter sets up MDC fields")
    void checkFilterAction() throws URISyntaxException {
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

    @UserLogged(logging = @Logged(requestBody = {LogType.MDC}, responseBody = LogType.MDC), userAgent = true)
    interface AnnotatedResource {

        void inherit();

    }

}

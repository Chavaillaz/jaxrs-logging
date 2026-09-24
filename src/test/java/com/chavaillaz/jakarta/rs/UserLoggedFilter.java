package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getAnnotation;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.ext.Provider;

@Provider
@UserLogged
public class UserLoggedFilter extends LoggedFilter {

    protected static final String REQUEST_IDENTIFIER = "request-identifier";
    protected static final String USER_ID = "user-id";
    protected static final String USER_AGENT = "user-agent";

    @Inject
    public UserLoggedFilter() {
        super(LoggedFilterConfiguration.builder()
                // Edit MDC field name when needed, for example to be aligned between applications
                // or follow schemas defined for Kibana, OpenSearch, Splunk
                .fieldName(REQUEST_ID, REQUEST_IDENTIFIER)
                // Take the request identifier from a custom header, falling back to a random one
                // when the header is absent
                .requestIdHeader("X-Case-ID")
                .build());
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        super.filter(requestContext);

        // Add the user currently logged in, possibly by querying injected entity.
        // Uses putMdc (rather than MDC.put directly) so it is removed once the request is done.
        putMdc(USER_ID, "Doe");

        // Log specific field if activated in the new annotation
        logUserAgent(requestContext);
    }

    private void logUserAgent(ContainerRequestContext requestContext) {
        getAnnotation(resourceInfo, UserLogged.class)
                .stream()
                .findFirst()
                .map(UserLogged::userAgent)
                .filter(loggingActivated -> loggingActivated)
                .map(logging -> requestContext.getHeaderString("User-Agent"))
                // Sanitized, as the header comes from the client, who could otherwise forge log lines with it
                .ifPresent(origin -> putMdc(USER_AGENT, sanitize(origin)));
    }

}

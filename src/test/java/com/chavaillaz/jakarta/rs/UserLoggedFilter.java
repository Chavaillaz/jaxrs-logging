package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;

import java.util.Optional;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.MDC;

@UserLogged
@Provider
public class UserLoggedFilter extends LoggedFilter {

    protected static final String REQUEST_IDENTIFIER = "request-identifier";
    protected static final String USER_ID = "user-id";
    protected static final String USER_AGENT = "user-agent";

    public UserLoggedFilter() {
        // Add new MDC fields to be finally cleaned up
        this.mdcFields.put(USER_ID, USER_ID);
        this.mdcFields.put(USER_AGENT, USER_AGENT);

        // Edit MDC field name when needed, for example to be aligned between applications
        // or follow schemas defined for Kibana, OpenSearch, Splunk
        this.mdcFields.put(REQUEST_ID.name(), REQUEST_IDENTIFIER);
    }

    @Override
    protected Optional<Logged> getAnnotation() {
        // Need to give the base configuration to the parent
        // But could also overwrite requestBodyLogging, responseBodyLogging and filtersBody
        return LoggedUtils.getAnnotation(resourceInfo, UserLogged.class)
                .map(UserLogged::logging);
    }

    @Override
    protected String getRequestId(ContainerRequestContext requestContext) {
        // Take the request identifier from custom header received
        return requestContext.getHeaderString("X-Case-ID");
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        super.filter(requestContext);

        // Add the user currently logged in, possibly by querying injected entity
        MDC.put(USER_ID, "Doe");

        // Log specific field if activated in the new annotation
        logUserAgent(requestContext);
    }

    private void logUserAgent(ContainerRequestContext requestContext) {
        LoggedUtils.getAnnotation(resourceInfo, UserLogged.class)
                .map(UserLogged::userAgent)
                .filter(loggingActivated -> loggingActivated)
                .map(logging -> requestContext.getHeaderString("User-Agent"))
                .ifPresent(origin -> MDC.put(USER_AGENT, origin));
    }

}

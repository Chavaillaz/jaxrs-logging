package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getAnnotation;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import java.util.HashMap;
import java.util.Map;

/**
 * Example of an application configuring the logging of its requests: it renames the MDC entry of the request
 * identifier, reads the identifier from a header of its own, and describes the requests with entries of its own,
 * one of them for the resources asking for it with an annotation of its own ({@link UserLogged}).
 */
@Provider
public class UserLoggingConfiguration implements ContextResolver<LoggedFeatureConfiguration> {

    protected static final String REQUEST_IDENTIFIER = "request-identifier";
    protected static final String USER_ID = "user-id";
    protected static final String USER_AGENT = "user-agent";

    static final LoggedFeatureConfiguration CONFIGURATION = LoggedFeatureConfiguration.builder()
            // Edit MDC field name when needed, for example to be aligned between applications
            // or follow schemas defined for Kibana, OpenSearch, Splunk
            .fieldName(REQUEST_ID, REQUEST_IDENTIFIER)
            // Take the request identifier from a custom header, falling back to a random one
            // when the header is absent
            .requestIdHeader("X-Case-ID")
            // Add entries of the application, removed once the request is done as those of the library are
            .mdcEntries(UserLoggingConfiguration::describe)
            .build();

    @Override
    public LoggedFeatureConfiguration getContext(Class<?> type) {
        return CONFIGURATION;
    }

    /**
     * Describes the given request with the entries of the application.
     *
     * @param request  The context of the request received
     * @param resource The resource method matched by the request
     * @return The entries describing the request, by MDC key
     */
    static Map<String, String> describe(ContainerRequestContext request, ResourceInfo resource) {
        Map<String, String> entries = new HashMap<>();
        // Add the user currently logged in, possibly by querying injected entity
        entries.put(USER_ID, "Doe");
        // Add the user agent if the annotation of the resource asks for it, sanitized by the library as the
        // header comes from the client, who could otherwise forge log lines with it
        if (getAnnotation(resource, UserLogged.class).stream().anyMatch(UserLogged::userAgent)) {
            entries.put(USER_AGENT, request.getHeaderString("User-Agent"));
        }
        return entries;
    }

}

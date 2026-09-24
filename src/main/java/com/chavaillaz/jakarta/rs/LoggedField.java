package com.chavaillaz.jakarta.rs;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * List of context fields to be written in MDC by {@link LoggedFilter}, under the default names given here
 * unless a subclass renames them (see {@link LoggedFilter#mdcFields}).
 */
public enum LoggedField {

    /**
     * Identifier of the request, from its {@value LoggedFilter#REQUEST_ID_HEADER} header or generated.
     */
    REQUEST_ID("request-id"),

    /**
     * HTTP method of the request.
     */
    REQUEST_METHOD("request-method"),

    /**
     * Path of the request, relative to the base URI of the application.
     */
    REQUEST_URI("request-uri"),

    /**
     * Query parameters of the request, with the value of those carrying a credential masked.
     */
    REQUEST_PARAMETERS("request-parameters"),

    /**
     * Body of the request, when logged as MDC (see {@link LoggedBody.LogType#MDC}).
     */
    REQUEST_BODY("request-body"),

    /**
     * Body of the response, when logged as MDC (see {@link LoggedBody.LogType#MDC}).
     */
    RESPONSE_BODY("response-body"),

    /**
     * HTTP status the request was answered with.
     */
    RESPONSE_STATUS("response-status"),

    /**
     * Simple name of the resource class matched by the request.
     */
    RESOURCE_CLASS("resource-class"),

    /**
     * Name of the resource method matched by the request.
     */
    RESOURCE_METHOD("resource-method"),

    /**
     * Time taken to answer the request, writing the response entity included, in milliseconds.
     */
    DURATION("duration");

    private final String defaultField;

    /**
     * Creates a new context field to be logged in MDC.
     *
     * @param defaultField The default MDC field name
     */
    LoggedField(String defaultField) {
        this.defaultField = defaultField;
    }

    /**
     * Gets a {@link Map} with the enumeration name as key and the default field name as value.
     *
     * @return The corresponding {@link Map}
     */
    public static Map<String, String> getDefaultFields() {
        Map<String, String> map = new HashMap<>();
        Stream.of(LoggedField.values()).forEach(entry -> map.put(entry.name(), entry.getDefaultField()));
        return map;
    }

    /**
     * Gets the default MDC field name to be used.
     *
     * @return The default name
     */
    public String getDefaultField() {
        return this.defaultField;
    }

}

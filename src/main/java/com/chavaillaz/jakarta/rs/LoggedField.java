package com.chavaillaz.jakarta.rs;

/**
 * Fields {@link LoggedFeature} describes a request with in MDC, under the names given here unless the
 * configuration renames them (see {@link LoggedFeatureConfiguration.Builder#fieldName(LoggedField, String)}).
 */
public enum LoggedField {

    /**
     * Identifier of the request, from its {@value LoggedFeature#REQUEST_ID_HEADER} header by default (see
     * {@link LoggedFeatureConfiguration.Builder#requestIdHeader(String)}), or generated.
     */
    REQUEST_ID("request-id"),

    /**
     * HTTP method of the request.
     */
    REQUEST_METHOD("request-method"),

    /**
     * Path of the request, relative to the base URI of the application and starting with a slash.
     */
    REQUEST_URI("request-uri"),

    /**
     * Query parameters of the request, as a query string sorted by name, the values of a parameter separated
     * by commas, those of a credential masked, and {@code %}, {@code &} and, in a name, {@code =} escaped.
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

    private final String defaultName;

    /**
     * Creates a field logged under the given name by default.
     *
     * @param defaultName The name of its MDC entry by default
     */
    LoggedField(String defaultName) {
        this.defaultName = defaultName;
    }

    /**
     * Gets the name of the MDC entry of the field by default.
     *
     * @return The name by default
     */
    public String getDefaultName() {
        return this.defaultName;
    }

}

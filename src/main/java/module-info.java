/**
 * Logging of the requests received by JAX-RS resources and of the calls made through JAX-RS clients, the
 * requests being described in MDC for every line logged while they are processed.
 * <ul>
 *     <li>{@link com.chavaillaz.jakarta.rs}: the annotations activating and configuring the logging of a
 *     resource, and the feature logging its requests</li>
 *     <li>{@link com.chavaillaz.jakarta.rs.client}: the logging of the calls made through a client</li>
 *     <li>{@link com.chavaillaz.jakarta.rs.filter}: the filters keeping values out of the bodies logged</li>
 *     <li>{@link com.chavaillaz.jakarta.rs.capture}: how the bodies logged are captured</li>
 *     <li>{@link com.chavaillaz.jakarta.rs.mdc}: the propagation of MDC to the threads a task is handed to</li>
 * </ul>
 * The package of the feature is open, as a JAX-RS implementation injects the context resolvers of the
 * application into {@code LoggedFeature}, or into the filters it registers, by reflection. The body filter
 * classes a resource names are instantiated by reflection too, so the package declaring them must be exported to
 * this module.
 */
module com.chavaillaz.jakarta.rs {

    requires transitive jakarta.annotation;
    requires transitive jakarta.ws.rs;
    requires transitive org.jspecify;
    requires transitive org.slf4j;
    requires org.apache.commons.lang3;

    exports com.chavaillaz.jakarta.rs;
    exports com.chavaillaz.jakarta.rs.capture;
    exports com.chavaillaz.jakarta.rs.client;
    exports com.chavaillaz.jakarta.rs.filter;
    exports com.chavaillaz.jakarta.rs.mdc;

    opens com.chavaillaz.jakarta.rs;

}

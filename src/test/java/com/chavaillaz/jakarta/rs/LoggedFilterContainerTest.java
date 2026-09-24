package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import org.apache.logging.log4j.core.LogEvent;
import org.jboss.resteasy.mock.MockDispatcherFactory;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.jboss.resteasy.spi.Dispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.event.Level;

/**
 * Runs the providers inside a real container rather than against mocked contexts: RESTEasy's in-memory
 * dispatcher, which matches, filters, intercepts and writes a request the way it does behind a server.
 * <p>
 * What only a container shows is what it actually hands a provider - which contexts it injects, which
 * callbacks it invokes and in which order, which property map they share. A provider depending on
 * anything beyond the JAX-RS contract passes every test built on mocks, and fails here.
 */
@DisplayName("Filter in a container")
class LoggedFilterContainerTest extends AbstractFilterTest {

    Dispatcher dispatcher;

    @BeforeEach
    void setupDispatcher() {
        dispatcher = MockDispatcherFactory.createDispatcher();
        dispatcher.getProviderFactory().registerProvider(LoggedFilter.class);
        dispatcher.getProviderFactory().registerProvider(LoggedBodyInterceptor.class);
        dispatcher.getProviderFactory().registerProvider(RejectingFilter.class);
        dispatcher.getRegistry().addPerRequestResource(ArticleResource.class);
        dispatcher.getRegistry().addPerRequestResource(NoteResource.class);
    }

    MockHttpResponse invoke(MockHttpRequest request) {
        MockHttpResponse response = new MockHttpResponse();
        dispatcher.invoke(request, response);
        return response;
    }

    @Test
    @DisplayName("Check a request is served, described in MDC and answered with its identifier")
    void checkRequestServedAndLogged() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.get("/article?topic=news"));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("article", response.getContentAsString());

        LogEvent event = processed();
        assertEquals(Level.INFO.name(), event.getLevel().name());
        Map<String, String> mdc = event.getContextData().toMap();
        assertEquals("GET", mdc.get("request-method"));
        assertEquals("/article", mdc.get("request-uri"));
        assertEquals("topic=news", mdc.get("request-parameters"));
        assertEquals("ArticleResource", mdc.get("resource-class"));
        assertEquals("get", mdc.get("resource-method"));
        assertEquals("200", mdc.get("response-status"));
        assertNotNull(mdc.get("duration"));
        assertNotNull(mdc.get("request-id"));
        assertEquals(mdc.get("request-id"), response.getOutputHeaders().getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check the request and response bodies are logged")
    void checkBodiesLogged() throws Exception {
        // Given
        MockHttpRequest request = MockHttpRequest.post("/article")
                .contentType(TEXT_PLAIN)
                .content("hello".getBytes(UTF_8));

        // When
        MockHttpResponse response = invoke(request);

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("Received POST /article" + LF + "hello", received().getMessage().getFormattedMessage());
        assertTrue(processed().getMessage().getFormattedMessage().endsWith(LF + "created hello"));
    }

    @Test
    @DisplayName("Check the identifier a client sent is the one logged and returned")
    void checkClientRequestIdKept() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.get("/article").header(REQUEST_ID_HEADER, "abc-123"));

        // Then
        assertEquals("abc-123", processed().getContextData().getValue("request-id"));
        assertEquals("abc-123", response.getOutputHeaders().getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check a response without entity is logged")
    void checkEmptyResponseLogged() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.delete("/article"));

        // Then
        assertEquals(204, response.getStatus());
        assertEquals("204", processed().getContextData().getValue("response-status"));
    }

    @Test
    @DisplayName("Check a request rejected by a filter running before this one is still described")
    void checkRequestAbortedBeforeFilterDescribed() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.get("/article").header("X-Reject", "true"));

        // Then
        assertEquals(401, response.getStatus());
        LogEvent event = processed();
        assertEquals(Level.WARN.name(), event.getLevel().name());
        assertEquals("GET", event.getContextData().getValue("request-method"));
        assertEquals("/article", event.getContextData().getValue("request-uri"));
    }

    @Test
    @DisplayName("Check the body logging a generic interface declares applies to the resource implementing it")
    void checkGenericInterfaceConfigurationApplied() throws Exception {
        // When: the container matches create(String), which implements the interface's create(T)
        MockHttpResponse response = invoke(MockHttpRequest.post("/notes")
                .contentType(TEXT_PLAIN)
                .content("hello".getBytes(UTF_8)));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("Received POST /notes" + LF + "hello", received().getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("Check nothing is left in MDC once a request has been served")
    void checkNothingLeftInMdc() throws Exception {
        // When
        invoke(MockHttpRequest.post("/article").contentType(TEXT_PLAIN).content("hello".getBytes(UTF_8)));
        invoke(MockHttpRequest.get("/article").header("X-Reject", "true"));
        invoke(MockHttpRequest.delete("/article"));

        // Then: the dispatcher runs each request on this very thread, as a pooled server thread would
        Map<String, String> left = MDC.getCopyOfContextMap();
        assertTrue(left == null || left.isEmpty(), () -> "Left in MDC: " + left);
    }

    LogEvent processed() {
        LogEvent event = listAppender.findFirstMessage("Processed");
        assertNotNull(event, "No Processed line was logged");
        return event;
    }

    LogEvent received() {
        LogEvent event = listAppender.findFirstMessage("Received");
        assertNotNull(event, "No Received line was logged");
        return event;
    }

    @Path("/article")
    @Logged(@LoggedBody(LOG))
    public static class ArticleResource {

        @GET
        @Produces(TEXT_PLAIN)
        public String get() {
            return "article";
        }

        @POST
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        public String create(String article) {
            return "created " + article;
        }

        @DELETE
        public void delete() {
            // Answered with 204 No Content, which has no entity to write
        }

    }

    /**
     * An API declared once for several types of entity, as a CRUD interface is, with the body logging of the
     * methods its resources implement.
     *
     * @param <T> The type of entity
     */
    public interface CrudApi<T> {

        @POST
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        @Logged(@LoggedBody(LOG))
        String create(T entity);

    }

    @Path("/notes")
    @Logged
    public static class NoteResource implements CrudApi<String> {

        @Override
        public String create(String note) {
            return "created " + note;
        }

    }

    /**
     * Stands in for an authentication filter, which runs before this library's own request filter and
     * aborts the request of a caller it rejects.
     */
    @Priority(Priorities.AUTHENTICATION)
    public static class RejectingFilter implements ContainerRequestFilter {

        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getHeaderString("X-Reject") != null) {
                requestContext.abortWith(Response.status(401).build());
            }
        }

    }

}

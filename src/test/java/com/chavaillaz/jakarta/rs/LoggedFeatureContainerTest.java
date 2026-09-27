package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedFeature.REQUEST_ID_HEADER;
import static com.chavaillaz.jakarta.rs.LoggedField.DURATION;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_STATUS;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.core.LogEvent;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.core.SynchronousExecutionContext;
import org.jboss.resteasy.mock.MockDispatcherFactory;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.jboss.resteasy.spi.Dispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;
import com.chavaillaz.jakarta.rs.mdc.MdcPropagation;

/**
 * Runs the providers inside a real container rather than against mocked contexts: RESTEasy's in-memory
 * dispatcher, which matches, filters, intercepts and writes a request the way it does behind a server.
 * <p>
 * What only a container shows is what it actually hands a provider - which contexts it injects, which
 * callbacks it invokes and in which order, which property map they share. A provider depending on
 * anything beyond the JAX-RS contract passes every test built on mocks, and fails here.
 */
@DisplayName("Feature in a container")
class LoggedFeatureContainerTest extends AbstractFilterTest {

    Dispatcher dispatcher;

    @BeforeEach
    void setupDispatcher() {
        dispatcher = MockDispatcherFactory.createDispatcher();
        dispatcher.getProviderFactory().registerProvider(LoggedFeature.class);
        dispatcher.getProviderFactory().registerProvider(RejectingFilter.class);
        dispatcher.getRegistry().addPerRequestResource(ArticleResource.class);
        dispatcher.getRegistry().addPerRequestResource(NoteResource.class);
        dispatcher.getRegistry().addPerRequestResource(DraftResource.class);
        dispatcher.getRegistry().addPerRequestResource(ResumedResource.class);
        dispatcher.getRegistry().addPerRequestResource(ClearingResource.class);
        dispatcher.getRegistry().addPerRequestResource(SingleBodyResource.class);
        dispatcher.getRegistry().addPerRequestResource(MappedResource.class);
        dispatcher.getRegistry().addPerRequestResource(PlainResource.class);
        dispatcher.getRegistry().addPerRequestResource(ConcreteResource.class);
        dispatcher.getRegistry().addPerRequestResource(UploadResource.class);
    }

    /**
     * Creates a dispatcher serving the given resources through a feature configured as given, rather than
     * through the default one the other tests share.
     *
     * @param configuration The configuration of the feature
     * @param resources     The resources to serve
     * @return The dispatcher created
     */
    static Dispatcher dispatcherWith(LoggedFilterConfiguration configuration, Class<?>... resources) {
        Dispatcher configured = MockDispatcherFactory.createDispatcher();
        configured.getProviderFactory().registerProviderInstance(new LoggedFeature(configuration));
        for (Class<?> resource : resources) {
            configured.getRegistry().addPerRequestResource(resource);
        }
        return configured;
    }

    static MockHttpResponse invoke(Dispatcher dispatcher, MockHttpRequest request) {
        MockHttpResponse response = new MockHttpResponse();
        dispatcher.invoke(request, response);
        return response;
    }

    MockHttpResponse invoke(MockHttpRequest request) {
        return invoke(dispatcher, request);
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
    @DisplayName("Check a HEAD request is logged once, as itself")
    void checkHeadRequestLogged() throws Exception {
        // When: answered by the GET method of the resource
        MockHttpResponse response = invoke(MockHttpRequest.create("HEAD", "/article"));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals(1, lines("Processed"));
        assertTrue(processed().getMessage().getFormattedMessage().startsWith("Processed HEAD /article with status 200"));
        Map<String, String> left = MDC.getCopyOfContextMap();
        assertTrue(left == null || left.isEmpty(), () -> "Left in MDC: " + left);
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
    @DisplayName("Check a method repeating @LoggedBody without @Logged is logged, the compiler wrapping them in it")
    void checkRepeatedLoggedBodyActivatesLogging() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.post("/drafts")
                .contentType(TEXT_PLAIN)
                .content("hello".getBytes(UTF_8)));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("Received POST /drafts" + LF + "hello", received().getMessage().getFormattedMessage());
        assertTrue(processed().getMessage().getFormattedMessage().endsWith(LF + "drafted hello"));
    }

    @Test
    @DisplayName("Check a method declaring a single @LoggedBody is logged, as one repeating it is")
    void checkSingleLoggedBodyActivatesLogging() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.post("/single")
                .contentType(TEXT_PLAIN)
                .content("hello".getBytes(UTF_8)));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("Received POST /single" + LF + "hello", received().getMessage().getFormattedMessage());
        assertTrue(processed().getMessage().getFormattedMessage().endsWith(LF + "single hello"));
    }

    @Test
    @DisplayName("Check the body logging an abstract base resource declares applies to the resource extending it")
    void checkBaseResourceConfigurationApplied() throws Exception {
        // When: the container matches the method overriding the one the base resource declares
        MockHttpResponse response = invoke(MockHttpRequest.post("/concrete")
                .contentType(TEXT_PLAIN)
                .content("hello".getBytes(UTF_8)));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("Received POST /concrete" + LF + "hello", received().getMessage().getFormattedMessage());
        assertTrue(processed().getMessage().getFormattedMessage().endsWith(LF + "concrete hello"));
    }

    @Test
    @DisplayName("Check a resource declaring a mapping alone is logged, the mapping applied")
    void checkLoggedMappingActivatesLogging() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.get("/mapped").header("X-Tenant", "acme"));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("acme", processed().getContextData().getValue("tenant"));
    }

    @Test
    @DisplayName("Check a resource carrying no annotation of this library is served without being logged")
    void checkUnannotatedResourceNotLogged() throws Exception {
        // When: the feature configures every resource method
        MockHttpResponse response = invoke(MockHttpRequest.post("/plain")
                .contentType(TEXT_PLAIN)
                .content("hello".getBytes(UTF_8)));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("plain hello", response.getContentAsString());
        assertNull(listAppender.findFirstMessage("Received"));
        assertNull(listAppender.findFirstMessage("Processed"));
        assertNull(response.getOutputHeaders().getFirst(REQUEST_ID_HEADER));
        Map<String, String> left = MDC.getCopyOfContextMap();
        assertTrue(left == null || left.isEmpty(), () -> "Left in MDC: " + left);
    }

    @Test
    @DisplayName("Check a request of a resource no feature logs is served without what a request logged left on the thread")
    void checkLeftoversSweptBeforeUnloggedRequest() throws Exception {
        // Given: a request whose response MdcPropagation completes on the thread serving it, restoring after the
        // context map it saved before - the entries of the request included
        MockHttpRequest resumed = MockHttpRequest.get("/resumed").header(REQUEST_ID_HEADER, "abc-123");
        MockHttpResponse resumedResponse = new MockHttpResponse();
        resumed.setAsynchronousContext(new SynchronousExecutionContext((SynchronousDispatcher) dispatcher, resumed, resumedResponse));
        dispatcher.invoke(resumed, resumedResponse);
        assertEquals(200, resumedResponse.getStatus());
        assertEquals("abc-123", MDC.get("request-id"));

        // When: a request of a resource carrying no annotation of this library follows on the thread
        MockHttpResponse response = invoke(MockHttpRequest.get("/plain/mdc"));

        // Then: served without them
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().matches("null|\\{}"), response.getContentAsString());
    }

    @Test
    @DisplayName("Check a request is described with the entries of the application, which the annotations of its resource drive")
    void checkApplicationEntriesLogged() throws Exception {
        // Given: an application declaring its configuration, entries of its own included
        Dispatcher describing = MockDispatcherFactory.createDispatcher();
        describing.getProviderFactory().registerProvider(UserLoggingConfiguration.class);
        describing.getProviderFactory().registerProvider(LoggedFeature.class);
        describing.getRegistry().addPerRequestResource(UserResource.class);

        // When
        MockHttpResponse response = invoke(describing, MockHttpRequest.post("/user")
                .header("User-Agent", "JUnit")
                .contentType(TEXT_PLAIN)
                .content("hello".getBytes(UTF_8)));

        // Then: described the way the application asks, with the body its resource logs
        assertEquals(200, response.getStatus());
        assertEquals("Received POST /user" + LF + "hello", received().getMessage().getFormattedMessage());
        assertEquals("Doe", processed().getContextData().getValue("user-id"));
        assertEquals("JUnit", processed().getContextData().getValue("user-agent"));
        assertNotNull(processed().getContextData().getValue("request-identifier"));
        Map<String, String> left = MDC.getCopyOfContextMap();
        assertTrue(left == null || left.isEmpty(), () -> "Left in MDC: " + left);
    }

    @Test
    @DisplayName("Check a body the resource reads as a stream is logged once read, and its capture released")
    void checkStreamedBodyLogged() throws Exception {
        // Given: captures counting what reaches them once released
        AtomicLong bytesAfterRelease = new AtomicLong();
        AtomicInteger releases = new AtomicInteger();
        Dispatcher streaming = dispatcherWith(LoggedFilterConfiguration.builder()
                .bodyCapture(limit -> new ReleaseTrackingCapture(limit, bytesAfterRelease, releases))
                .build(), UploadResource.class);

        // When: the resource method reads its entity once the interceptors are done with it
        MockHttpResponse response = invoke(streaming, MockHttpRequest.post("/upload")
                .contentType(TEXT_PLAIN)
                .content("hello".getBytes(UTF_8)));

        // Then: logged as the resource method read it, from captures all released, which nothing reached after
        assertEquals(200, response.getStatus());
        assertEquals("read 5", response.getContentAsString());
        assertEquals("Received POST /upload" + LF + "hello", received().getMessage().getFormattedMessage());
        assertEquals(2, releases.get());
        assertEquals(0, bytesAfterRelease.get());
    }

    @Test
    @DisplayName("Check a body the resource reads in part as a stream is logged as far as it read it, once answered")
    void checkStreamedBodyReadInPartLogged() throws Exception {
        // When: the resource method reads the first bytes of its entity only, and leaves the stream open
        MockHttpResponse response = invoke(MockHttpRequest.post("/upload/head")
                .contentType(TEXT_PLAIN)
                .content("hello world".getBytes(UTF_8)));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("head hello", response.getContentAsString());
        assertEquals("Received POST /upload/head" + LF + "hello", received().getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("Check a body the resource reads through a reader is logged")
    void checkBodyReadThroughReaderLogged() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.post("/upload/text")
                .contentType(TEXT_PLAIN)
                .content("hello".getBytes(UTF_8)));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("Received POST /upload/text" + LF + "hello", received().getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("Check a request is logged in full and at the level of its status whatever MDC leaves out")
    void checkLinesIndependentOfMdcFields() throws Exception {
        // Given
        Dispatcher bare = dispatcherWith(LoggedFilterConfiguration.builder()
                .withoutField(REQUEST_METHOD)
                .withoutField(REQUEST_URI)
                .withoutField(RESPONSE_STATUS)
                .withoutField(DURATION)
                .build(), FailingResource.class);

        // When
        MockHttpResponse response = invoke(bare, MockHttpRequest.get("/failing"));

        // Then
        assertEquals(500, response.getStatus());
        LogEvent event = processed();
        assertTrue(event.getMessage().getFormattedMessage().matches("Processed GET /failing with status 500 in \\d+ms"),
                event.getMessage().getFormattedMessage());
        assertEquals(Level.ERROR.name(), event.getLevel().name());
        assertNull(event.getContextData().getValue("response-status"));
    }

    @Test
    @DisplayName("Check a request is logged in full even once the application cleared MDC")
    void checkLinesIndependentOfMdcCleared() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.get("/clearing"));

        // Then
        assertEquals(200, response.getStatus());
        String line = processed().getMessage().getFormattedMessage();
        assertTrue(line.matches("Processed GET /clearing with status 200 in \\d+ms"), line);
    }

    @Test
    @DisplayName("Check the feature a container instantiates is configured the way the application declares")
    void checkConfigurationDeclaredByApplication() throws Exception {
        // Given: an application declaring its configuration, next to a feature it does not instantiate itself
        Dispatcher declaring = MockDispatcherFactory.createDispatcher();
        declaring.getProviderFactory().registerProvider(TraceConfiguration.class);
        declaring.getProviderFactory().registerProvider(LoggedFeature.class);
        declaring.getRegistry().addPerRequestResource(ArticleResource.class);

        // When
        MockHttpResponse response = invoke(declaring, MockHttpRequest.get("/article").header("X-Trace-ID", "abc-123"));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("abc-123", processed().getContextData().getValue("trace-id"));
        assertEquals("abc-123", response.getOutputHeaders().getFirst("X-Trace-ID"));
        assertNull(response.getOutputHeaders().getFirst(REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("Check a request is logged with its MDC entries even once the application cleared MDC")
    void checkEntriesLoggedIndependentOfMdcCleared() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.get("/clearing").header(REQUEST_ID_HEADER, "abc-123"));

        // Then: the line completing the request can still be correlated with the other lines of it
        assertEquals(200, response.getStatus());
        LogEvent event = processed();
        assertEquals("abc-123", event.getContextData().getValue("request-id"));
        assertEquals("/clearing", event.getContextData().getValue("request-uri"));
        Map<String, String> left = MDC.getCopyOfContextMap();
        assertTrue(left == null || left.isEmpty(), () -> "Left in MDC: " + left);
    }

    @Test
    @DisplayName("Check the identifier of a request is returned even once the application cleared MDC")
    void checkRequestIdReturnedIndependentOfMdcCleared() throws Exception {
        // When
        MockHttpResponse response = invoke(MockHttpRequest.get("/clearing").header(REQUEST_ID_HEADER, "abc-123"));

        // Then
        assertEquals(200, response.getStatus());
        assertEquals("abc-123", response.getOutputHeaders().getFirst(REQUEST_ID_HEADER));
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

    @Test
    @DisplayName("Check nothing of a request completed within a context restored after is left for the next one")
    void checkNothingLeftByCompletionInRestoredContext() throws Exception {
        // Given: a request whose response MdcPropagation completes on the thread serving it, restoring after the
        // context map it saved before - the entries of the request included
        MockHttpRequest resumed = MockHttpRequest.get("/resumed").header("X-Tenant", "acme");
        MockHttpResponse response = new MockHttpResponse();
        resumed.setAsynchronousContext(new SynchronousExecutionContext((SynchronousDispatcher) dispatcher, resumed, response));
        dispatcher.invoke(resumed, response);
        assertEquals(200, response.getStatus());

        // When
        invoke(MockHttpRequest.get("/article"));

        // Then: neither the next request nor the thread once it is done carries anything of it
        LogEvent next = listAppender.findFirstMessage("Processed GET /article");
        assertNotNull(next, "The next request was not logged");
        assertNull(next.getContextData().getValue("header-X-Tenant"));
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

    long lines(String start) {
        return listAppender.getMessages().stream()
                .filter(event -> event.getMessage().getFormattedMessage().startsWith(start))
                .count();
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

    @Path("/upload")
    @Logged(@LoggedBody(LOG))
    public static class UploadResource {

        @POST
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        public String upload(InputStream upload) throws IOException {
            return "read " + upload.readAllBytes().length;
        }

        @POST
        @Path("/head")
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        public String head(InputStream upload) throws IOException {
            return "head " + new String(upload.readNBytes(5), UTF_8);
        }

        @POST
        @Path("/text")
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        public String text(Reader upload) throws IOException {
            try (upload) {
                return "text " + upload.read();
            }
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
     * Configures its body logging with repeated {@link LoggedBody} alone, which the compiler wraps into the
     * {@link Logged} they are repeatable in.
     */
    @Path("/drafts")
    public static class DraftResource {

        @POST
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        @LoggedBody(value = LOG, targets = REQUEST)
        @LoggedBody(value = LOG, targets = RESPONSE)
        public String create(String draft) {
            return "drafted " + draft;
        }

    }

    /**
     * Resumes its response right away, on the thread serving the request, within the context map MdcPropagation
     * applies - as a stage given to a {@link CompletableFuture} already complete runs.
     */
    @Path("/resumed")
    @Logged
    @LoggedMapping(type = HEADER, auto = true, mdcPrefix = "header-")
    public static class ResumedResource {

        @GET
        @Produces(TEXT_PLAIN)
        public void get(@Suspended AsyncResponse response) {
            CompletableFuture.completedFuture("resumed").thenAccept(MdcPropagation.wrap(response)::resume);
        }

    }

    @Path("/failing")
    @Logged
    public static class FailingResource {

        @GET
        public Response get() {
            return Response.serverError().build();
        }

    }

    /**
     * Configures its body logging with a single {@link LoggedBody}, which no {@link Logged} wraps.
     */
    @Path("/single")
    public static class SingleBodyResource {

        @POST
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        @LoggedBody(LOG)
        public String create(String body) {
            return "single " + body;
        }

    }

    /**
     * Declares a mapping alone, and nothing else of this library.
     */
    @Path("/mapped")
    @LoggedMapping(type = HEADER, mdcKey = "tenant", paramNames = "X-Tenant")
    public static class MappedResource {

        @GET
        @Produces(TEXT_PLAIN)
        public String get() {
            return "mapped";
        }

    }

    /**
     * A base resource declaring the body logging of the methods its resources implement, which JAX-RS
     * inherits the annotations of.
     */
    public abstract static class BaseResource {

        @POST
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        @LoggedBody(LOG)
        public abstract String create(String body);

    }

    /**
     * Extends a base resource, and declares nothing of this library of its own.
     */
    @Path("/concrete")
    public static class ConcreteResource extends BaseResource {

        @Override
        public String create(String body) {
            return "concrete " + body;
        }

    }

    /**
     * Carries no annotation of this library.
     */
    @Path("/plain")
    public static class PlainResource {

        @POST
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        public String create(String body) {
            return "plain " + body;
        }

        @GET
        @Path("/mdc")
        @Produces(TEXT_PLAIN)
        public String mdc() {
            return String.valueOf(MDC.getCopyOfContextMap());
        }

    }

    /**
     * Asks the configuration of the application for the user agent with an annotation of its own, the body logging
     * being configured as usual.
     */
    @Path("/user")
    @UserLogged(userAgent = true)
    @LoggedBody(LOG)
    public static class UserResource {

        @POST
        @Consumes(TEXT_PLAIN)
        @Produces(TEXT_PLAIN)
        public String create(String body) {
            return "user " + body;
        }

    }

    /**
     * Clears the MDC of the thread serving it, as an application may do with its own entries in mind.
     */
    @Path("/clearing")
    @Logged
    public static class ClearingResource {

        @GET
        @Produces(TEXT_PLAIN)
        public String get() {
            MDC.clear();
            return "cleared";
        }

    }

    /**
     * In-memory capture counting the bytes its sink is given once it was released, and the captures released.
     */
    static final class ReleaseTrackingCapture extends BoundedLoggedBodyCapture {

        final AtomicLong bytesAfterRelease;
        final AtomicInteger releases;
        final AtomicBoolean released = new AtomicBoolean();

        ReleaseTrackingCapture(int limit, AtomicLong bytesAfterRelease, AtomicInteger releases) {
            super(limit);
            this.bytesAfterRelease = bytesAfterRelease;
            this.releases = releases;
        }

        @Override
        public OutputStream sink() {
            OutputStream sink = super.sink();
            return new OutputStream() {

                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (released.get()) {
                        bytesAfterRelease.addAndGet(len);
                    }
                    sink.write(b, off, len);
                }

            };
        }

        @Override
        public void close() {
            released.set(true);
            releases.incrementAndGet();
        }

    }

    /**
     * Configuration an application declares, which the features it does not instantiate itself look up.
     */
    @Provider
    public static class TraceConfiguration implements ContextResolver<LoggedFilterConfiguration> {

        static final LoggedFilterConfiguration CONFIGURATION = LoggedFilterConfiguration.builder()
                .fieldName(REQUEST_ID, "trace-id")
                .requestIdHeader("X-Trace-ID")
                .build();

        @Override
        public LoggedFilterConfiguration getContext(Class<?> type) {
            return CONFIGURATION;
        }

    }

    /**
     * Stands in for an authentication filter, which runs before the request filter of this library and
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

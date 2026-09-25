# JAX-RS Requests Logging

[![System tests](https://github.com/chavaillaz/jaxrs-logging/actions/workflows/system-tests.yml/badge.svg)](https://github.com/chavaillaz/jaxrs-logging/actions/workflows/system-tests.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.chavaillaz/jaxrs-logging)](https://central.sonatype.com/artifact/com.chavaillaz/jaxrs-logging)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

This library allows you to easily log (with MDC) requests and responses by annotation of JAX-RS resources.

## Installation

The dependency is available in maven central (see badge for version):

```xml
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>jaxrs-logging</artifactId>
</dependency>
```

The jar is a named module, `com.chavaillaz.jaxrs.logging`, exporting the following packages:

| Package                             | Content                                                                    |
|-------------------------------------|----------------------------------------------------------------------------|
| `com.chavaillaz.jakarta.rs`         | Annotations activating and configuring the logging, and the providers      |
| `com.chavaillaz.jakarta.rs.client`  | Logging of the calls made through a JAX-RS client                          |
| `com.chavaillaz.jakarta.rs.filter`  | Filters keeping values out of the bodies logged                            |
| `com.chavaillaz.jakarta.rs.capture` | How the bodies logged are captured                                         |
| `com.chavaillaz.jakarta.rs.mdc`     | Propagation of MDC to the threads a task is handed to                      |

On the module path, export the package of the body filter classes your resources name to this module, which
instantiates them by reflection.

## Usage

The logging of requests and responses is done through a filter that can be activated on a resource with:

```java
@Logged
```

It will add the following information to MDC for the request processing
(meaning that all logs within the processing of the request by the resource will have them):

* Request identifier (from X-Request-ID header or random UUID)
* Request HTTP method
* Request URI path relative to the base URI
* Request query parameters
* Resource class matched by the current request
* Resource method matched by the current request

Once the response is computed, the request will be logged using the format:

```
Processed [method] [URI] with status [status] in [duration]ms
```

with the following MDC fields set:

* Response HTTP status
* Response duration in milliseconds, covering the whole request including serializing and writing the
  response entity (a response whose body takes 200ms to render is reported as such, not as the handful of
  milliseconds preceding it)

That line is written at a level derived from the status: `ERROR` for a server error (5xx), `WARN` for a
client error (4xx), `INFO` otherwise. Client errors are deliberately not errors - a `404` or a `400` is
the application working as designed and says something about the caller, not about the service - but a
failed request logged at the same level as a successful one is a line nobody is alerted on, and the
library is the one place that already knows which it was. Set `responseLevel` in the
[configuration](#configuration) to fit your own conventions.

Note that setting the logger above `INFO` disables body capture entirely (see below), so failures are
then logged at `WARN`/`ERROR` but without their bodies: whether a request failed is only known once it
has been answered, long after the decision to capture its body had to be made.

The identifier the request was logged under is also returned to the caller as `X-Request-ID`. Without it,
that identifier only exists on the server: a caller reporting "your API returned a 500 at about 14:32"
leaves whoever picks up the report searching by timestamp, while a caller quoting the identifier from the
response points straight at the request. A header already set by the application (or by a gateway in front
of it) is left alone. The [configuration](#configuration) reads and returns it in another header with
`requestIdHeader`, or stops returning it with `withoutReturnedRequestId()`.

Additional logging features can be activated by adding `@LoggedBody` (repeatable) to `@Logged`:

```java
@Logged(@LoggedBody(value = {LOG, MDC}, filters = YourBodyFilter.class, limit = 10_000))
```

* **value**: Types of body logging to activate
    * `LOG`: Logging the body in a new log line (`Received [method] [URI] [body]` for the request,
      appended to `Processed ...` for the response)
    * `MDC`: Logging the body as MDC only, included in the `Processed ...` log line
* **filters**: Classes implementing the functional interface
  [LoggedBodyFilter](src/main/java/com/chavaillaz/jakarta/rs/filter/LoggedBodyFilter.java) to filter any body
  before writing it in logs, for example to remove sensitive data that could be present
  (see [Body filters](#body-filters) for the ready-made ones).
* **limit**: Size limit in bytes of the body logged (not limited by default).
* **targets**: Whether the configuration applies to the request, the response, or both (default).

By default, `@LoggedBody` applies to both the request and the response. Repeat the annotation with different
`targets` to configure them separately:

```java
@LoggedBody(value = MDC, targets = REQUEST)
@LoggedBody(value = LOG, targets = RESPONSE)
```

A configuration targeting a single direction wins over one targeting both, and among several as specific as
one another, the first one declared wins.

`@LoggedBody` and `@LoggedMapping` configure the logging `@Logged` activates, they do not activate it: on a
resource that is not `@Logged` at its method or class level, they do nothing. Repeating `@LoggedBody` is the
one exception, as the compiler wraps the repeated annotations into the `@Logged` they are repeatable in - so
two of them on a method log its bodies, while a single one silently does not.

Be careful when activating any body logging, as it may produce performance or memory issues if the body size
is not limited: the captured body is buffered in memory, so an endpoint accepting large (or client-controlled)
payloads should always set a `limit`. A body cut short by that limit ends with `...[truncated]`, so a partial
payload is never mistaken for what the application actually sent or received. Without a limit, a body is cut
the same way where the heap has no room for more of it, and one too large to be rendered with the memory left
is left out of the logs: logging it fails neither way the request it only observes.

A body whose content type is not text-based (for example `application/octet-stream`, `application/pdf` or
an `image/*`/`multipart/*` type) is logged as a lowercase hexadecimal string instead of being decoded as
text, to avoid filling logs with replacement characters for binary payloads such as file uploads. A text
body is decoded with the charset its content type declares (`text/plain; charset=ISO-8859-1`, for
example), and as UTF-8 when it declares none.

Nothing is captured at all when the logger is configured above `INFO`, so an application that turns this
logging off does not pay for buffering and filtering bodies it will never write.

Bodies are captured by a second provider,
[LoggedBodyInterceptor](src/main/java/com/chavaillaz/jakarta/rs/LoggedBodyInterceptor.java), which runs after
any entity coder so that what is logged is the entity itself rather than its transfer encoding - a
`Content-Encoding: gzip` request or response is logged as the payload, not as gzip noise. It is discovered
like any other `@Provider`; if you register providers explicitly, register it alongside `LoggedFilter`
(without it you only lose the bodies, not the log lines).

## Example

Given an endpoint on which users can create new articles, annotated with `@Logged`
(the annotation can also be on methods, for example in case of specific configuration):

```java
@Path("/article")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Logged(@LoggedBody(MDC))
public class ArticleResource {

    @POST
    public Article create(Article article) {
        // Creation of the article in the database
    }

}
```

When the following request is sent:

```
POST service.company.com/article
Content-Type: application/json

{ "content": "Something" }
```

Then the following log is written:

```
Processed POST /article with status 200 in 15ms
```

with the following MDC fields:

* `request-id: 02625ee3-03ae-4e26-a83b-74477c5824d2`
* `request-method: POST`
* `request-uri: /article`
* `request-body: { "content": "Something" }`
* `resource-class: ArticleResource`
* `resource-method: create`
* `response-status: 200`
* `response-body: { "id" : 1, "content": "Something" }`
* `duration: 15`

## MDC Mappings

Mappings can be defined to create MDC entries applied to any logs during the request processing from:

* Any header of the request
* Any query parameter of the request
* Any path parameter of the request

Those mappings are defined with annotations to put on JAX-RS methods, classes and possibly their interfaces.

The example below defines three mappings and creates the following MDC entries:

* `request-user-agent` from the header `User-Agent`
* `request-topic` from the path parameter `topic`
* `request-draft` from the query parameter `draft`

```java
@POST
@Logged
@Path("/{topic}/article")
@LoggedMapping(type = HEADER, mdcKey = "request-user-agent", paramNames = "User-Agent")
@LoggedMapping(type = PATH, mdcKey = "request-topic", paramNames = "topic")
@LoggedMapping(type = QUERY, mdcKey = "request-draft", paramNames = "draft")
public Article create(@PathParam("topic") String topicId, @QueryParam("draft") Boolean draft, Article article) {
    // Creation of the article
}
```

Automatic mapping can also be enabled to create MDC entries with the same names as the parameters.
A prefix can be specified to avoid conflicts with other MDC entries:

```java
@LoggedMapping(type = HEADER, auto = true, mdcPrefix = "header-")
```

This automatic mapping creates, for example, the following MDC entries for a common HTTP request:

* `header-Accept` with value `*/*`
* `header-Accept-Encoding` with value `gzip,deflate`
* `header-Connection` with value `Keep-Alive`
* `header-Host` with value `localhost:8081`

Specific mappings can also be excluded (without giving `mdcKey` value):

```java
@LoggedMapping(type = HEADER, paramNames = "Accept")
```

The mappings declared on the resource method, its interfaces and its class all apply, but a parameter is
mapped once at most: of the mappings naming it, the one declared closest to the resource method wins (see
[Annotation resolution](#annotation-resolution)), and among those declared at the same place, the first one,
whether they map the parameter or exclude it. An automatic mapping leaves out the parameters named by the
mappings that apply.

Every MDC entry the library creates is removed once the request has been logged. When a request completes
on a different thread than the one that started it (a resumed `@Suspended` response, a reactive resource
method), the removal cannot reach the thread that set them, so the library also sweeps its own leftovers
at the start of every request - mapped keys included, whose names are only known once the client has sent
the request.

Automatic mapping never copies a credential-carrying header (`Authorization`, `Cookie`, `X-Api-Key`, ...)
into MDC, as `auto = true` is a blanket "map whatever the client sent" instruction and is otherwise an easy
way to end up with bearer tokens and session cookies permanently stored in a log aggregator. The exact list
is in [CredentialNames](src/main/java/com/chavaillaz/jakarta/rs/internal/CredentialNames.java); extend or restrict
it with `sensitiveParameters` in the [configuration](#configuration). An explicit mapping naming a header
is a deliberate decision and is left alone.

## Credentials in query parameters

Query parameters are logged by default as `request-parameters`, with nothing to configure, so a caller
passing a credential in the query string puts it in the logs of every service it reaches. That is bad
practice and well known as such, and it is also what OAuth's implicit and authorization-code-in-URL flows,
presigned URLs and plenty of internal APIs do - the application has no say in what its callers send.

The value of a parameter whose name is a well-known credential name (`access_token`, `password`,
`client_secret`, the `X-Amz-Signature` of a presigned URL, ... see [CredentialNames](src/main/java/com/chavaillaz/jakarta/rs/internal/CredentialNames.java))
is therefore replaced with `***`, while the name stays visible - knowing a token was supplied at all is
the useful part for troubleshooting, and the name is not the secret:

```
request-parameters: access_token=***&topic=news
```

Automatic `QUERY` mapping skips those parameters entirely, the way it does for headers. Names too commonly
used for ordinary things to mask for everyone (`code`, for instance) are not in the list; add whatever
your callers actually send with `sensitiveParameters` in the [configuration](#configuration), composing
with the default list:

```java
LoggedFilterConfiguration.builder()
        .sensitiveParameters((type, name) -> isCredential(type, name)
                || (type == QUERY && "url-signature".equalsIgnoreCase(name)))
        .build();
```

The same goes for the calls logged by [LoggedClientFilter](#client-calls), which log the URI of each call whole:
the value of the same query parameters is masked, and so is the user information a URI may embed, which is
either a credential or the name going with one:

```
Calling GET https://***@service.company.com/article?topic=news&access_token=***
```

Override `isSensitiveQueryParameter(String)` on the client filter to mask the parameters of the services your
application calls, such as the key a partner API expects in its query string.

## Body filters

A body filter rewrites a captured body before it is logged, which is how a value that must never reach the
logs is kept out of them. Three ready-made ones cover the usual cases:

| Filter                                                                                               | Masks                                                                 |
|------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| [JsonMaskingBodyFilter](src/main/java/com/chavaillaz/jakarta/rs/filter/JsonMaskingBodyFilter.java)   | The value of the named JSON properties, at any depth                  |
| [FormMaskingBodyFilter](src/main/java/com/chavaillaz/jakarta/rs/filter/FormMaskingBodyFilter.java)   | The value of the named `application/x-www-form-urlencoded` parameters |
| [RegexMaskingBodyFilter](src/main/java/com/chavaillaz/jakarta/rs/filter/RegexMaskingBodyFilter.java) | Whatever a given regular expression captures, for any other format    |

```
{"user":"jane","password":"hunter2"}   ->  {"user":"jane","password":"***"}
grant_type=password&password=hunter2   ->  grant_type=password&password=***
```

`@LoggedBody(filters = ...)` takes classes, which must be instantiable without arguments, so configuring
one for a resource means declaring a subclass that fixes its arguments:

```java
public class CredentialsMask extends JsonMaskingBodyFilter {

    public CredentialsMask() {
        super("password", "token");
    }

}
```

```java
@Logged(@LoggedBody(value = MDC, filters = CredentialsMask.class))
```

`LoggedClientFilter.builder()` accepts instances as well, so no subclass is needed there:

```java
client.register(LoggedClientFilter.builder()
        .logRequestBody()
        .bodyFilters(new JsonMaskingBodyFilter("password", "token"))
        .build());
```

Filters run in the order they are declared, so one masking a value and another truncating it behave
predictably. They work on the captured text rather than on a parsed document on purpose: a body reaching a
filter may have been cut by `limit`, or be malformed - which is exactly when the logs matter most - and a
parser would reject both.

A filter of your own only has to implement `filter(StringBuilder)`, which is given a copy of the body. If it
has nothing to change in most bodies, also override `apply(CharSequence)` to return the body it is given as
it is in that case, as the ready-made filters do: that spares copying every body logged just to find there
was nothing to filter in it.

A filter that throws never reaches the application: the body is dropped and logged as
`[body dropped: a filter failed]`, and the failure is reported on the library's own logger. Dropping it
rather than falling back to the captured text is deliberate - a filter that threw has by definition not
finished redacting, so what it was working on is exactly what must not be written. The same goes for a
filter class that cannot be instantiated (a constructor or class that is not `public`, an inner class that
is not `static`, ...): the failure is reported once, and every body it was declared for is dropped rather
than logged unredacted.

More generally, nothing this library does while logging is allowed to fail a request: capture, filtering and
log writing all run inside a `finally` block, where a thrown exception would otherwise replace the exception
the exchange actually failed with (or turn a good response into a 500).

## Annotation resolution

`@Logged`, `@LoggedBody` and `@LoggedMapping` are looked up, for the resource method matched by the request,
at four declaration sites, from the most to the least specific:

1. the resource method itself
2. the methods it overrides on the interfaces implemented by the resource class
3. those interfaces themselves
4. the resource class itself

The first site declaring `@Logged` or `@LoggedBody` wins **entirely** - a more specific declaration replaces
a less specific one rather than being merged with it. This is what lets a method opt out of a class-level
configuration by redeclaring an empty one:

```java
@Logged(@LoggedBody(MDC))
public class ArticleResource {

    @POST
    public Article create(Article article) {
        // Inherits the class-level body logging
    }

    @POST
    @Path("/import")
    @Logged // Redeclared empty: no body logging for this (potentially huge) payload
    public void importArchive(InputStream archive) {
    }

}
```

`@LoggedMapping` is the exception: the mappings of every site are merged, a mapping only giving way to one
naming the same parameter at a more specific site (see [MDC Mappings](#mdc-mappings)).

Resolution is cached per resource class and method, so the reflection above happens once per endpoint
rather than once per request.

## Client calls

The client-side counterpart [LoggedClientFilter](src/main/java/com/chavaillaz/jakarta/rs/client/LoggedClientFilter.java)
logs outgoing JAX-RS Client calls and propagates the current request identifier (from MDC) to the downstream
service as `X-Request-ID`, so a service calling another service exposing its own `@Logged` resource produces a
single, correlated identifier across both sides of the call.

Unlike `@Logged`, which is resolved per resource method from annotations, `LoggedClientFilter` has no resource
method to attach annotations to: an instance is configured once through its builder and applies to every call
made through the `Client`/`WebTarget` it is registered on.

```java
client.register(LoggedClientFilter.builder()
        .logRequestBody()
        .logResponseBody()
        .bodyLimit(10_000)
        .bodyFilters(YourBodyFilter.class)
        .build());
```

It logs `Calling [method] [uri]` before sending the request and `Called [method] [uri] with status [status]
in [duration]ms` once the response is received, the latter at a level derived from the status the same way
as on the server side (`getResponseLevel(int)`). The roles are reversed there: a 5xx is the downstream
service failing, which is this application's problem to react to, and a 4xx means this application sent
something that service rejected - a bug on this side rather than somebody else's typo.

If body logging is activated, the body is logged as a further, separate line rather than merged into those
two: the response body is only available if/when the calling code actually reads the response entity, which
may happen after (or not at all after) the `Called ...` line, so there is no single point to merge them into,
unlike the server-side filter.

For the same reason, only logging the body as a new log line is supported, not adding it to MDC: on the server
side, `@LoggedBody(MDC)` works because `LoggedFilter` has a single, well-defined point (`logResponse`) at which
the whole request is known to be complete, so an MDC entry can be added and removed around exactly that point.
`LoggedClientFilter` has no equivalent point to scope such an entry to, since the response body may become
available only after (or never, relative to) the point the call is considered done, so there is nothing for
an MDC entry holding the body to be reliably paired with.

## MDC propagation across threads

MDC is backed by a thread-local: entries set for a request (by `@Logged` or by application code) are only
visible on the thread that set them, and are lost as soon as the work continues on another thread - a task
submitted to an `ExecutorService`, a manually started `Thread`, a `@Suspended AsyncResponse` resumed from a
different worker, or a reactive resource method.

[MdcPropagation](src/main/java/com/chavaillaz/jakarta/rs/mdc/MdcPropagation.java) copies the calling thread's
MDC context map onto the thread that runs a wrapped task, and restores that thread's own previous context
map once the task completes:

```java
executorService.submit(MdcPropagation.wrap(() -> {
    // Runs with the submitting thread's MDC context map
}));
```

A whole `ExecutorService` can be wrapped instead, so every task submitted to it (through `execute`, `submit`
or `invokeAll`/`invokeAny`) propagates context automatically:

```java
ExecutorService executorService = MdcPropagation.wrap(Executors.newFixedThreadPool(10));
```

### CompletableFuture

Every `*Async` method of `CompletableFuture` accepts an `Executor`, so passing a wrapped one to each stage
carries the context along the whole chain: each stage runs with the context restored, and therefore submits
the next one from a thread that already has it.

```java
Executor executor = MdcPropagation.wrap(pool);

CompletableFuture.supplyAsync(() -> load(id), executor)
        .thenApplyAsync(this::render, executor)
        .thenAcceptAsync(response::resume, executor);
```

The `*Async` methods taking no executor run on the common `ForkJoinPool`, which cannot be wrapped. Wrap the
stage functions themselves instead:

```java
CompletableFuture.supplyAsync(MdcPropagation.wrapSupplier(() -> load(id)))
        .thenApplyAsync(MdcPropagation.wrapFunction(this::render))
        .thenAcceptAsync(MdcPropagation.wrapConsumer(response::resume));
```

There is one method per shape (`wrapSupplier`, `wrapFunction`, `wrapConsumer`, `wrapBiFunction`,
`wrapBiConsumer`) rather than more `wrap` overloads, because `Supplier` has the same shape as `Callable` and
`Function` the same as `Consumer`: overloading them would make `wrap(() -> value)` ambiguous rather than
resolving to the one meant.

### @Suspended AsyncResponse

A resource method taking a `@Suspended AsyncResponse` returns before the response exists, and the container
only runs the response filters and writes the entity when `resume` is called - on whatever thread the
application calls it from. Without propagation, that thread carries none of the request's MDC: the library
still logs its own `Processed ...` line with the entries it put for the request, but every other line logged
while completing it - by a response filter or a message body writer of the application, for instance - lands
with no request identifier, no URI and no method.

Wrapping the response covers the completion however the application got there - a pool, a
`CompletableFuture` chain, a callback from a client library - and covers the timeout handler too, which the
container would otherwise invoke with the unwrapped response on a timer thread:

```java
@GET
public void get(@Suspended AsyncResponse response) {
    AsyncResponse propagating = MdcPropagation.wrap(response);
    pool.execute(() -> propagating.resume(load()));
}
```

## Configuration

`LoggedFilter` applies the same configuration to every resource it logs: what varies from a resource to
another is declared on the resource itself with `@LoggedBody` and `@LoggedMapping`. That configuration is a
[LoggedFilterConfiguration](src/main/java/com/chavaillaz/jakarta/rs/LoggedFilterConfiguration.java), built
with `LoggedFilterConfiguration.builder()`:

* **fieldName** / **withoutField**: Renames the MDC entry of a field, for example to align it with other
  applications or with the schema of whatever the logs are shipped to, or leaves the field out altogether.
* **requestIdHeader**: Header the request identifier is read from and returned in (`X-Request-ID` by default).
* **requestId**: How the request identifier is obtained, for example to always generate it server-side
  when the callers are untrusted.
* **withoutReturnedRequestId**: Stops returning the request identifier to the caller.
* **sensitiveParameters**: Parameters whose value is kept out of the logs (credential names by default, see
  [Credentials in query parameters](#credentials-in-query-parameters)).
* **responseLevel**: Level the `Processed ...` line is logged at, given the response status.
* **bodyCapture**: How bodies are captured (in memory by default), for example to spill very large ones to
  a temporary file.

A container instantiates a provider through its no-argument constructor, so pass the configuration from the
constructor of a subclass:

```java
@Provider
public class ApplicationLoggedFilter extends LoggedFilter {

    public ApplicationLoggedFilter() {
        super(LoggedFilterConfiguration.builder()
                .fieldName(REQUEST_ID, "trace-id")
                .requestIdHeader("X-Trace-ID")
                .responseLevel(status -> status == 404 ? Level.INFO : LoggedSupport.levelOf(status))
                .build());
    }

}
```

An application registering its providers explicitly can pass it to `new LoggedFilter(configuration)` instead.

A subclass can also put entries of its own in MDC, through `putMdc` so they are removed once the request is
done. An example is available with [UserLogged](src/test/java/com/chavaillaz/jakarta/rs/UserLogged.java)
and [UserLoggedFilter](src/test/java/com/chavaillaz/jakarta/rs/UserLoggedFilter.java), which:

* Logs a new **user-id** field in MDC
* Logs a new **user-agent** field in MDC if activated in its annotation
* Reads the **request-id** from another header
* Renames the MDC field of **request-id** to **request-identifier**

A subclass bound to an annotation of its own, as `UserLoggedFilter` is to `@UserLogged`, logs bodies only if
[LoggedBodyInterceptor](src/main/java/com/chavaillaz/jakarta/rs/LoggedBodyInterceptor.java) runs for the same
resources: it is bound to `@Logged`, so declare a subclass of it bound to your annotation too. The body logging
configuration itself is still read from `@Logged` and `@LoggedBody`, declared on the resources as usual.

## Contributing

If you have a feature request or found a bug, you can:

- Write an issue
- Create a pull request

If you want to contribute then

- Please write tests covering all your changes
- Ensure you didn't break the build by running `mvn test`
- Fork the repo and create a pull request

## License

This project is under Apache 2.0 License.
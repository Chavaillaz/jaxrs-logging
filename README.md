# JAX-RS Requests Logging

![Dependency Check](https://github.com/chavaillaz/jaxrs-logging/actions/workflows/system-tests.yml/badge.svg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/jaxrs-logging/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/jaxrs-logging)
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
* Response duration in milliseconds

Additional logging features can be activated using properties of the annotation:

```java
@Logged(requestBody = {LOG, MDC}, responseBody = {LOG, MDC}, filtersBody = {YourBodyFilter.class})
```

* **requestBody**
    * `LOG`: Logging the request body in a new log line `Received [method] [URI] [body]`
    * `MDC`: Logging the request body as MDC only in the `Processed ...` log line
* **responseBody**
    * `LOG`: Logging the response body at the end of the `Processed ...` log line
    * `MDC`: Logging the response body as MDC only in the `Processed ...` log line
* **filtersBody**: Classes implementing the functional interface
  [LoggedBodyFilter](src/main/java/com/chavaillaz/jakarta/rs/LoggedBodyFilter.java) to filter any body
  before writing it in logs, for example to remove sensitive data that could be present.
* **limitBody**: Size limit in bytes of the request / response body logged (not limited by default).

By careful when activating any body logging, it may produce issues if the body size is not limited.

## Example

Given an endpoint on which users can create new articles, annotated with `@Logged`
(the annotation can also be on methods, for example in case of specific configuration):

```java
@Path("/article")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Logged(requestBody = MDC, responseBody = MDC)
public class ArticleResource {

    @POST
    @Path("/create")
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
* `request-topic` from the query parameter `topic`
* `request-draft` from the path parameter `draft`

```java
@POST
@Path("/{topic}/article")
@LoggedMapping(type = HEADER, mdcKey = "request-user-agent", paramNames = "User-Agent")
@LoggedMapping(type = QUERY, mdcKey = "request-topic", paramNames = "topic")
@LoggedMapping(type = PATH, mdcKey = "request-draft", paramNames = "draft")
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

Note that a field can only be mapped once, and its exclusion will have priority.
If you try to map a field that is already mapped, it will be ignored.

## Extension

An example of extension of the filter is available
with [UserLogged](src/test/java/com/chavaillaz/jakarta/rs/UserLogged.java)
and [UserLoggedFilter](src/test/java/com/chavaillaz/jakarta/rs/UserLoggedFilter.java).
In this example, you can find the following customization of the original filter:

* Log new **user-id** field in MDC
* Log new **user-agent** field in MDC if activated in annotation
* Change **request-id** logic to get it from a header field
* Rename MDC field of **request-id** to **request-identifier**

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
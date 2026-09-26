package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_PARAMETERS;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_CLASS;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.container.ResourceInfo;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.resteasy.core.interception.jaxrs.PreMatchContainerRequestContext;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link RequestDescriber} directly: the request identifier edge cases are covered through a
 * request by {@link LoggedFilterTest}.
 */
@DisplayName("Request describer")
class RequestDescriberTest {

    final RequestDescriber describer = new RequestDescriber((type, name) -> type == QUERY && "access_token".equals(name));

    interface ArticleResource {

        void find();

    }

    static ResourceInfo resource(Class<?> resourceClass, Method resourceMethod) {
        return new ResourceInfo() {

            @Override
            public Method getResourceMethod() {
                return resourceMethod;
            }

            @Override
            public Class<?> getResourceClass() {
                return resourceClass;
            }

        };
    }

    Map<LoggedField, String> describe(String method, String uri, ResourceInfo resource) throws Exception {
        Map<LoggedField, String> fields = new EnumMap<>(LoggedField.class);
        describer.describe(new PreMatchContainerRequestContext(MockHttpRequest.create(method, uri)), resource, "abc-123", fields::put);
        return fields;
    }

    @Test
    @DisplayName("Check a request is described along with the resource matched for it")
    void checkRequestDescribed() throws Exception {
        Map<LoggedField, String> fields = describe("GET", "/articles/42?topic=news",
                resource(ArticleResource.class, ArticleResource.class.getMethod("find")));

        assertEquals(Map.of(
                REQUEST_ID, "abc-123",
                REQUEST_URI, "/articles/42",
                REQUEST_PARAMETERS, "topic=news",
                REQUEST_METHOD, "GET",
                RESOURCE_CLASS, "ArticleResource",
                RESOURCE_METHOD, "find"), fields);
    }

    @Test
    @DisplayName("Check a request no resource was matched for is described without one")
    void checkRequestDescribedWithoutResource() throws Exception {
        Map<LoggedField, String> fields = describe("GET", "/articles", resource(null, null));

        assertEquals(Set.of(REQUEST_ID, REQUEST_URI, REQUEST_PARAMETERS, REQUEST_METHOD), fields.keySet());
    }

    @Test
    @DisplayName("Check control characters the client sent are removed from the description")
    void checkDescriptionSanitized() throws Exception {
        Map<LoggedField, String> fields = describe("GET", "/articles/%0D%0A42?topic=a%E2%80%A8b", resource(null, null));

        assertEquals("/articles/  42", fields.get(REQUEST_URI));
        assertEquals("topic=a b", fields.get(REQUEST_PARAMETERS));
    }

    @Test
    @DisplayName("Check query parameters are rendered in name order, with credentials masked")
    void checkQueryDescribed() {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        parameters.put("topic", List.of("news", "sport"));
        parameters.put("access_token", List.of("secret-token"));
        parameters.put("page", List.of("2"));

        assertEquals("access_token=***&page=2&topic=news,sport", describer.describeQuery(parameters));
        assertEquals("", describer.describeQuery(Map.of()));
    }

    @Test
    @DisplayName("Check only the query parameters the predicate reports for queries are masked")
    void checkQueryMaskingFollowsPredicate() {
        RequestDescriber headersOnly = new RequestDescriber((type, name) -> type == HEADER);

        assertEquals("access_token=secret-token", headersOnly.describeQuery(Map.of("access_token", List.of("secret-token"))));
    }

}

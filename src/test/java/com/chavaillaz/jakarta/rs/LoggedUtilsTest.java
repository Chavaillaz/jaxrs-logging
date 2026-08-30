package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getMergedMappings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("Logged utils")
@ExtendWith(MockitoExtension.class)
class LoggedUtilsTest {

    @Mock
    ResourceInfo resourceInfo;

    interface MappingInterface {

        // Repeated mappings declared on an interface method (not the concrete class)
        @LoggedMapping(type = QUERY, mdcKey = "interface-a", paramNames = "a")
        @LoggedMapping(type = HEADER, mdcKey = "interface-b", paramNames = "b")
        void mapped();

    }

    // Single (non-repeated) mapping declared directly on the concrete resource class
    @LoggedMapping(type = QUERY, mdcKey = "class-level", paramNames = "single")
    static class MappingResource implements MappingInterface {

        @Override
        public void mapped() {
            // No-op
        }

    }

    @Test
    @DisplayName("Check mappings are found regardless of being single or repeated, and on the class or an interface")
    void checkMergedMappings() throws Exception {
        // Given
        doReturn(MappingResource.class).when(resourceInfo).getResourceClass();
        doReturn(MappingResource.class.getMethod("mapped")).when(resourceInfo).getResourceMethod();

        // When
        Set<LoggedMapping> mappings = getMergedMappings(resourceInfo);

        // Then
        Set<String> mdcKeys = mappings.stream().map(LoggedMapping::mdcKey).collect(Collectors.toSet());
        assertEquals(3, mappings.size());
        assertTrue(mdcKeys.contains("interface-a"));
        assertTrue(mdcKeys.contains("interface-b"));
        assertTrue(mdcKeys.contains("class-level"));
    }

}

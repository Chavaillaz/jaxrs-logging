package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.BoundedOutputStream.trimIncompleteTrailingCharacter;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Set;

/**
 * Default {@link LoggedBodyCapture} implementation, capturing at most a configured number of bytes in
 * memory.
 */
public class BoundedLoggedBodyCapture implements LoggedBodyCapture {

    protected final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    protected final BoundedOutputStream sink;

    /**
     * Creates a new bounded, in-memory body capture.
     *
     * @param limit The maximum number of bytes to capture, or {@code -1} for no limit
     */
    public BoundedLoggedBodyCapture(int limit) {
        this.sink = new BoundedOutputStream(buffer, limit);
    }

    @Override
    public OutputStream sink() {
        return sink;
    }

    @Override
    public String content(Set<LoggedBodyFilter> filters) {
        byte[] bytes = buffer.toByteArray();
        if (sink.isTruncated()) {
            // A limit reached mid-character would otherwise decode to a trailing replacement character
            bytes = trimIncompleteTrailingCharacter(bytes);
        }

        String body = new String(bytes, UTF_8);
        if (filters.isEmpty()) {
            return body;
        }

        StringBuilder bodyBuilder = new StringBuilder(body);
        filters.forEach(filter -> filter.filter(bodyBuilder));
        return bodyBuilder.toString();
    }

}

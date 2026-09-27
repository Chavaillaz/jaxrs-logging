package com.chavaillaz.jakarta.rs.capture;

import jakarta.ws.rs.core.MediaType;
import java.io.OutputStream;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Capture of an application keeping bodies in memory by delegating to a {@link BoundedBodyCapture}, for a test to
 * override the method it stands in for: a sink failing, a rendering failing, a capture released.
 */
public class DelegatingBodyCapture implements LoggedBodyCapture {

    private final BoundedBodyCapture capture;

    /**
     * Creates a capture keeping at most the given number of bytes in memory.
     *
     * @param limit The maximum number of bytes to capture, or {@link LoggedBodyCapture#NO_LIMIT} for no limit
     */
    public DelegatingBodyCapture(int limit) {
        this.capture = new BoundedBodyCapture(limit);
    }

    @Override
    public OutputStream getSink() {
        return capture.getSink();
    }

    @Override
    public @Nullable String getContent(List<LoggedBodyFilter> filters, @Nullable MediaType mediaType) {
        return capture.getContent(filters, mediaType);
    }

    @Override
    public void close() {
        capture.close();
    }

}

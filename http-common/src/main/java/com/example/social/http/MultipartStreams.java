package com.example.social.http;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.http4k.multipart.StreamingMultipartFormParts;
import org.http4k.multipart.StreamingPart;

/**
 * Adapter for the JVM-public raw parser in the pinned http4k 6.20 multipart dependency.
 * Kotlin's multipartIterator eagerly reads fields into Strings; this adapter lets us bound each
 * stream first. Keep its narrow dependency on http4k's Kotlin-internal parser covered by upload tests.
 */
public final class MultipartStreams {
    private MultipartStreams() {}

    public record Part(String name, boolean field, String filename, String contentType, InputStream content) {}

    public static Iterable<Part> parse(String boundary, InputStream stream) {
        Iterable<StreamingPart> parsed = StreamingMultipartFormParts.Companion.parse(
            boundary.getBytes(StandardCharsets.UTF_8), stream, StandardCharsets.UTF_8);
        return () -> new Iterator<>() {
            private final Iterator<StreamingPart> parts = parsed.iterator();
            @Override public boolean hasNext() { return parts.hasNext(); }
            @Override public Part next() {
                StreamingPart part = parts.next();
                return new Part(part.getFieldName(), part.isFormField(), part.getFileName(), part.getContentType(), part.getInputStream());
            }
        };
    }
}

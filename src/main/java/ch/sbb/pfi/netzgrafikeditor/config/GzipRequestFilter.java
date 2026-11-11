package ch.sbb.pfi.netzgrafikeditor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Transparently decompresses request bodies sent with {@code Content-Encoding: gzip}.
 *
 * Requests without {@code Content-Encoding} (or {@code identity}) are passed through unchanged,
 * unsupported encodings are rejected with 415 (RFC 7694).
 * The decompressed size is limited to protect against decompression bombs.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // before Spring Security
public class GzipRequestFilter extends OncePerRequestFilter {

    static final String GZIP = "gzip";
    private static final String IDENTITY = "identity";

    private final long maxDecompressedBytes;

    public GzipRequestFilter(
            @Value("${request.decompression.max-size-bytes:134217728}") long maxDecompressedBytes) {
        this.maxDecompressedBytes = maxDecompressedBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        if (encoding == null || encoding.isBlank() || IDENTITY.equalsIgnoreCase(encoding.trim())) {
            chain.doFilter(request, response);
            return;
        }
        if (!GZIP.equalsIgnoreCase(encoding.trim())) {
            log.warn(
                    "Unsupported request Content-Encoding '{}': {} {}",
                    encoding,
                    request.getMethod(),
                    request.getRequestURI());
            response.setHeader(HttpHeaders.ACCEPT_ENCODING, GZIP);
            response.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "Decompressing gzip request body ({} bytes compressed): {} {}",
                    request.getContentLengthLong(),
                    request.getMethod(),
                    request.getRequestURI());
        }
        chain.doFilter(new GzipRequestWrapper(request, maxDecompressedBytes), response);
    }

    private static final class GzipRequestWrapper extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream stream;
        private BufferedReader reader;

        GzipRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called");
            }
            if (stream == null) {
                stream = new LimitedGzipServletInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                if (stream != null) {
                    throw new IllegalStateException("getInputStream() has already been called");
                }
                String enc = getCharacterEncoding();
                Charset charset = enc != null ? Charset.forName(enc) : StandardCharsets.UTF_8;
                stream = new LimitedGzipServletInputStream(super.getInputStream(), maxBytes);
                reader = new BufferedReader(new InputStreamReader(stream, charset));
            }
            return reader;
        }

        // the body is no longer encoded and its length is unknown -> hide these headers
        @Override
        public String getHeader(String name) {
            return isHidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isHidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names =
                    Collections.list(super.getHeaderNames()).stream()
                            .filter(n -> !isHidden(n))
                            .toList();
            return Collections.enumeration(names);
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        private static boolean isHidden(String name) {
            return HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)
                    || HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name);
        }
    }

    private static final class LimitedGzipServletInputStream extends ServletInputStream {
        private final ServletInputStream source;
        private final long maxBytes;
        private InputStream gzip;
        private long count;
        private boolean finished;

        LimitedGzipServletInputStream(ServletInputStream source, long maxBytes) {
            this.source = source;
            this.maxBytes = maxBytes;
        }

        private InputStream gzip() throws IOException {
            if (gzip == null) {
                // lazily: GZIPInputStream reads the gzip header in its constructor
                gzip = new GZIPInputStream(source, 64 * 1024);
            }
            return gzip;
        }

        @Override
        public int read() throws IOException {
            int b = gzip().read();
            if (b < 0) {
                finished = true;
            } else {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = gzip().read(buf, off, len);
            if (n < 0) {
                finished = true;
            } else {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            count += n;
            if (count > maxBytes) {
                throw new IOException(
                        "Decompressed request body exceeds limit of " + maxBytes + " bytes");
            }
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Async reading is not supported");
        }

        @Override
        public void close() throws IOException {
            if (gzip != null) {
                gzip.close();
            } else {
                source.close();
            }
        }
    }
}

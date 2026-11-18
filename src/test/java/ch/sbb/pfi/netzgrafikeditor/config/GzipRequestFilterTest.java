package ch.sbb.pfi.netzgrafikeditor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

class GzipRequestFilterTest {

    private static final String JSON =
            "{\"name\":\"äöü\",\"model\":\"" + "x".repeat(10_000) + "\"}";

    private final GzipRequestFilter filter = new GzipRequestFilter(1_000_000);

    @Test
    void decompressesGzipBody() throws Exception {
        var request = request(gzip(JSON), "gzip");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        var forwarded = (HttpServletRequest) chain.getRequest();
        assertThat(new String(forwarded.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(JSON);
        assertThat(forwarded.getHeader(HttpHeaders.CONTENT_ENCODING)).isNull();
        assertThat(forwarded.getContentLengthLong()).isEqualTo(-1);
    }

    @Test
    void decompressesViaReader() throws Exception {
        var request = request(gzip(JSON), "GZIP");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        var reader = ((HttpServletRequest) chain.getRequest()).getReader();
        assertThat(reader.lines().reduce("", String::concat)).isEqualTo(JSON);
    }

    @Test
    void passesUncompressedBodyThrough() throws Exception {
        var request = request(JSON.getBytes(StandardCharsets.UTF_8), null);
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void rejectsUnsupportedEncoding() throws Exception {
        var request = request(JSON.getBytes(StandardCharsets.UTF_8), "br");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(415);
        assertThat(response.getHeader(HttpHeaders.ACCEPT_ENCODING)).isEqualTo("gzip");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsDecompressionBomb() throws Exception {
        var request = request(gzip("0".repeat(2_000_000)), "gzip");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        var in = chain.getRequest().getInputStream();
        assertThatThrownBy(in::readAllBytes)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds limit");
    }

    private static MockHttpServletRequest request(byte[] body, String encoding) {
        var request = new MockHttpServletRequest("POST", "/v1/versions/1/snapshot");
        request.setContentType("application/json");
        request.setCharacterEncoding("UTF-8");
        request.setContent(body);
        if (encoding != null) {
            request.addHeader(HttpHeaders.CONTENT_ENCODING, encoding);
        }
        return request;
    }

    private static byte[] gzip(String s) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}

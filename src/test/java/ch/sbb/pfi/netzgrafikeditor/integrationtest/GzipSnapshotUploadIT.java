package ch.sbb.pfi.netzgrafikeditor.integrationtest;

import static ch.sbb.pfi.netzgrafikeditor.integrationtest.helper.UserHelper.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.sbb.pfi.netzgrafikeditor.api.version.model.VersionCreateSnapshotDto;
import ch.sbb.pfi.netzgrafikeditor.config.SecurityConfig;
import ch.sbb.pfi.netzgrafikeditor.integrationtest.setup.IntegrationTest;
import ch.sbb.pfi.netzgrafikeditor.integrationtest.setup.testdata.VersionTestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Snapshot upload with a gzip compressed request body */
public class GzipSnapshotUploadIT extends IntegrationTest {

    @BeforeEach
    protected void insertTestData() {
        testDataService.insertTestData(
                List.of(
                        VersionTestData.PROJECT,
                        VersionTestData.PROJECTS_USERS_A,
                        VersionTestData.PROJECTS_USERS_B,
                        VersionTestData.PROJECTS_USERS_C,
                        VersionTestData.VARIANT,
                        VersionTestData.SNAPSHOT_VERSION_1_1));
        controllableNowProvider.setNow(VersionTestData.DATE);
    }

    @Test
    public void createSnapshot_withGzipBody__expectModelStored() throws Exception {
        var model = "{\"nodes\":\"" + "abc".repeat(100_000) + "\"}";
        var dto = VersionCreateSnapshotDto.builder().name("gzip").comment("").model(model).build();
        var json = objectMapper.writeValueAsBytes(dto);
        var gzipped = gzip(json);
        assertThat(gzipped.length).isLessThan(json.length / 10);

        var result =
                mockMvc.perform(
                                post(
                                                "/v1/versions/{baseVersionId}/snapshot",
                                                VersionTestData.SNAPSHOT_VERSION_1_1.getId())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                                        .content(gzipped)
                                        .with(
                                                user(
                                                        VersionTestData.USER_A,
                                                        SecurityConfig.USER_ROLE)))
                        .andExpect(status().isCreated())
                        .andReturn();
        var versionId = Long.parseLong(result.getResponse().getContentAsString());

        var stored =
                mockMvc.perform(
                                get("/v1/versions/{versionId}/model", versionId)
                                        .with(
                                                user(
                                                        VersionTestData.USER_A,
                                                        SecurityConfig.USER_ROLE)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        assertThat(stored).contains("abcabcabc");
        assertThat(stored.length()).isGreaterThanOrEqualTo(300_000);
    }

    @Test
    public void createSnapshot_withUnsupportedEncoding__expect415() throws Exception {
        mockMvc.perform(
                        post(
                                        "/v1/versions/{baseVersionId}/snapshot",
                                        VersionTestData.SNAPSHOT_VERSION_1_1.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(HttpHeaders.CONTENT_ENCODING, "br")
                                .content("{}")
                                .with(user(VersionTestData.USER_A, SecurityConfig.USER_ROLE)))
                .andExpect(status().isUnsupportedMediaType());
    }

    private static byte[] gzip(byte[] data) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }
}

package com.aisys.dataset.service;

import com.aisys.common.s3.service.S3StorageService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DatasetPreviewServiceTest {
    private DatasetPreviewService service(byte[] bytes) {
        S3StorageService storage = mock(S3StorageService.class);
        when(storage.bucket()).thenReturn("test");
        when(storage.getStream(anyString(), anyString())).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return new DatasetPreviewService(storage, new ObjectMapper());
    }

    @Test
    void zipUsesAllImagesAndActualArchiveSizeAndAllowsLastPageBeyond5000() throws Exception {
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6S8AAAAASUVORK5CYII=");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, Charset.forName("GB18030"))) {
            for (int i = 0; i < 6000; i++) {
                String split = i < 4200 ? "train" : i < 5100 ? "val" : "test";
                zip.putNextEntry(new ZipEntry(split + "/类别_" + (i % 8) + "/" + String.format("%05d.png", i)));
                zip.write(png);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        byte[] bytes = out.toByteArray();
        DatasetPreviewService service = service(bytes);
        var stats = service.statistics("images.zip", "imagenet", (long) bytes.length, 6000L, null, null);
        assertEquals(6000L, stats.sampleCount());
        assertEquals(6001, stats.fileCount());
        assertEquals((long) bytes.length, stats.fileSize());
        assertEquals((long) png.length * 6000 + 3, stats.totalSize());
        assertEquals(Map.of("train", 4200L, "val", 900L, "test", 900L), stats.splitDistribution());
        assertEquals(8, stats.classesCount());
        assertTrue(((Map<?, ?>) stats.classDistribution()).values().stream().allMatch(n -> n.equals(750L)));
        assertEquals(Map.of("< 320x240", 6000L), stats.imageSizeDistribution());
        assertNull(stats.totalAnnotations(), "classification has no detection box annotations");
        assertNull(stats.avgAnnotationsPerSample());
        var preview = service.preview("images.zip", "imagenet", 1L, 2L, 600, 10);
        assertEquals(6000, preview.total());
        assertEquals(10, preview.rows().size());
        assertEquals(6000, preview.rows().getLast().get("id"));
        assertArrayEquals(png, service.sampleImage("images.zip", "imagenet", 5999).bytes());
        assertTrue(service.preview("images.zip", "imagenet", 1L, 2L, Integer.MAX_VALUE, 200).rows().isEmpty());
    }

    @Test
    void textFormatsPreviewAllRowsBeyond5000AndJsonlStatisticsIncludeFirstRow() {
        StringBuilder csv = new StringBuilder("value,label\n");
        StringBuilder jsonl = new StringBuilder();
        StringBuilder array = new StringBuilder("[");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            csv.append(i).append(",class_").append(i % 2).append('\n');
            String row = "{\"value\":" + i + ",\"label\":\"class_" + i % 2 + "\"}";
            jsonl.append(row).append('\n');
            if (i > 0) array.append(',');
            array.append(row);
            text.append("sample ").append(i).append('\n');
        }
        array.append(']');
        Map<String, String> files = Map.of("data.csv", csv.toString(), "data.jsonl", jsonl.toString(), "data.json", array.toString(), "data.txt", text.toString());
        files.forEach((key, data) -> {
            DatasetPreviewService service = service(data.getBytes(StandardCharsets.UTF_8));
            var preview = service.preview(key, null, 1L, 2L, 600, 10);
            assertEquals(6000, preview.total(), key);
            assertEquals(10, preview.rows().size(), key);
            assertTrue(service.preview(key, null, 1L, 2L, Integer.MAX_VALUE, 200).rows().isEmpty(), key);
        });
        for (String key : new String[]{"data.csv", "data.jsonl"}) {
            byte[] bytes = files.get(key).getBytes(StandardCharsets.UTF_8);
            var stats = service(bytes).statistics(key, null, (long) bytes.length, null, null, null);
            assertEquals(6000L, stats.sampleCount(), key);
            assertEquals(Map.of("class_0", 3000L, "class_1", 3000L), stats.classDistribution(), key);
        }
    }
}

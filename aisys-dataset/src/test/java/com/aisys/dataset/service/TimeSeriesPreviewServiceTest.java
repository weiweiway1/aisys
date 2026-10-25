package com.aisys.dataset.service;

import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.s3.service.S3StorageService;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TimeSeriesPreviewServiceTest {
    private TimeSeriesPreviewService service(byte[] bytes) {
        S3StorageService s3 = mock(S3StorageService.class);
        when(s3.bucket()).thenReturn("test");
        when(s3.getStream(anyString(), anyString())).thenAnswer(call -> new ByteArrayInputStream(bytes));
        return new TimeSeriesPreviewService(s3);
    }
    private byte[] archive(int count) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < count; i++) {
                String split = i < 3600 ? "train" : i < 4800 ? "val" : "test";
                String label = i % 2 == 0 ? "normal" : "fault";
                zip.putNextEntry(new ZipEntry(split + "/" + label + "/sample_" + String.format("%05d", i) + ".csv"));
                zip.write("t,value\n0,10\n1,12\n2,14\n".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("metadata.csv"));
            zip.write("id,label\n1,normal\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return out.toByteArray();
    }
    @Test
    void countsCsvWindowsAndFiltersByClassAndSplitWithoutPreviewTotalLimit() throws Exception {
        byte[] zip = archive(6000);
        var service = service(zip);
        var stats = service.statistics("signals.zip", (long) zip.length, null);
        assertEquals(6000L, stats.sampleCount());
        assertEquals(Map.of("train",3600L,"val",1200L,"test",1200L), stats.splitDistribution());
        assertEquals(Map.of("normal",3000L,"fault",3000L), stats.classDistribution());
        assertEquals(6001, stats.fileCount());
        assertEquals(2, stats.classesCount());
        var lastPage = service.preview("signals.zip",600,10,null,null,null);
        assertEquals(6000, lastPage.total());
        assertEquals(6000,lastPage.rows().getLast().get("id"));
        var filtered = service.preview("signals.zip",1,20,"test","normal",null);
        assertEquals(600,filtered.total());
        var row = filtered.rows().getFirst();
        var sample = service.sample("signals.zip",((Number)row.get("id")).intValue()-1);
        assertEquals("normal",sample.label()); assertEquals("test",sample.split());
        assertEquals(row.get("path"),sample.path());
        assertEquals(3,sample.pointCount()); assertEquals("index",sample.axisKind());
        assertEquals(1,sample.channels().size()); assertEquals("value",sample.channels().getFirst().name());
        var summary = service.inspect(new ByteArrayInputStream(zip),"signals.csv");
        assertEquals(6000,summary.samples()); assertEquals(List.of("t","value"),summary.columns());
        assertThrows(BusinessException.class, () -> service.sample("signals.zip",6000));
    }
    @Test
    void parsesQuotedChannelsMissingValuesAndAccurateRawSignalStatistics() {
        byte[] csv = "\uFEFFt,\"voltage,V\",current\r\n0,10,2\r\n1,12,\r\n2,14,NaN\r\n3,,Infinity\r\n".getBytes(StandardCharsets.UTF_8);
        var sample = service(csv).sample("signal.csv",0);
        assertEquals(4,sample.pointCount()); assertEquals(2,sample.channels().size());
        var channel = sample.channels().getFirst();
        assertEquals("voltage,V",channel.name()); assertEquals(12d,channel.mean());
        assertEquals(10d,channel.min()); assertEquals(14d,channel.max()); assertEquals(1,channel.missingCount());
        assertEquals(Math.sqrt(8d/3),channel.std(),1e-9);
        assertEquals(Math.sqrt(440d/3),channel.rms(),1e-9);
        assertNull(channel.values().getLast()); assertEquals(3,sample.channels().getLast().missingCount());
    }
    @Test
    void supportsHeaderlessTsvAndDoesNotInventSamplingRateOrTimeUnits() {
        var sample = service("1\t2\n3\t4\n".getBytes(StandardCharsets.UTF_8)).sample("data.tsv",0);
        assertEquals("sample_index",sample.axisName()); assertEquals("index",sample.axisKind());
        assertEquals(List.of(0d,1d),sample.axis()); assertEquals(2,sample.channels().size());
    }
    @Test
    void recognizesExplicitTimeAxisAndReportsInvalidAxisValues() {
        var valid = service("time,v\n0.0,2\n0.1,3\n".getBytes(StandardCharsets.UTF_8)).sample("data.csv",0);
        assertEquals("time", valid.axisKind());
        var sample = service("time,v\n0.0,2\n0.1,3\n,4\n".getBytes(StandardCharsets.UTF_8)).sample("data.csv",0);
        assertEquals("index",sample.axisKind()); assertEquals(1,sample.warnings().size());
        assertEquals(2d,sample.axis().getLast());
    }
    @Test
    void rejectsNonNumericSignalsInsteadOfShowingAnEmptyWaveform() {
        assertThrows(BusinessException.class, () -> service("t,note\n0,hello\n".getBytes(StandardCharsets.UTF_8)).sample("bad.csv",0));
    }
}

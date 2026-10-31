package com.coursetrace.media;

import com.coursetrace.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
@EnableConfigurationProperties(MediaProperties.class)
public class MediaProbe {
    private final MediaProperties properties;
    private final ObjectMapper json;

    public MediaProbe(MediaProperties properties, ObjectMapper json) { this.properties = properties; this.json = json; }

    public MediaInfo inspect(Path file) {
        Path report = null;
        Process process = null;
        try {
            report = Files.createTempFile(file.getParent(), "probe-", ".json");
            process = new ProcessBuilder(properties.ffprobePath(), "-v", "error", "-protocol_whitelist", "file,pipe",
                    "-show_format", "-show_streams", "-of", "json", "-i", file.toAbsolutePath().toString())
                    .redirectErrorStream(true).redirectOutput(report.toFile()).start();
            if (!process.waitFor(properties.probeTimeoutSeconds(), TimeUnit.SECONDS)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROBE_TIMEOUT", "视频校验超时，请尝试较短的视频");
            }
            if (process.exitValue() != 0 || Files.size(report) > 131072) throw invalid();
            return parse(json.readTree(report.toFile()));
        } catch (ApiException ex) { throw ex; }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROBE_INTERRUPTED", "视频校验中断，请重试");
        } catch (IOException ex) {
            if (process == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROBE_UNAVAILABLE", "找不到视频校验工具，请检查 FFPROBE_PATH");
            throw invalid();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            if (report != null) try { Files.deleteIfExists(report); } catch (IOException ignored) {}
        }
    }

    MediaInfo parse(JsonNode root) {
        if (!root.path("format").path("format_name").asText().contains("mp4")) throw invalid();
        JsonNode video = null;
        String audio = null;
        for (var stream : root.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText()) && stream.path("disposition").path("attached_pic").asInt() != 1 && video == null) video = stream;
            if ("audio".equals(stream.path("codec_type").asText())) {
                if (!"aac".equals(stream.path("codec_name").asText())) throw unsupported();
                audio = "aac";
            }
        }
        if (video == null) throw invalid();
        if (!"h264".equals(video.path("codec_name").asText()) ||
                !java.util.Set.of("yuv420p", "yuvj420p").contains(video.path("pix_fmt").asText())) throw unsupported();
        long duration;
        try {
            duration = new BigDecimal(root.path("format").path("duration").asText())
                    .multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (RuntimeException ex) { throw invalid(); }
        int width = video.path("width").asInt(), height = video.path("height").asInt();
        if (duration <= 0 || width <= 0 || height <= 0) throw invalid();
        return new MediaInfo(duration, width, height, "h264", audio);
    }

    private ApiException invalid() { return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_VIDEO", "文件不是有效的 MP4 视频，或文件已损坏"); }
    private ApiException unsupported() { return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_CODEC", "请上传 H.264（8 位）视频、AAC 音轨的 MP4 文件"); }
}

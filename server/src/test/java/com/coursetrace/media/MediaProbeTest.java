package com.coursetrace.media;

import com.coursetrace.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class MediaProbeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MediaProbe probe = new MediaProbe(new MediaProperties("unused","unused",15),json);
    private String metadata(String video, String audio, String format, String duration) {
        return "{\"format\":{\"format_name\":\""+format+"\",\"duration\":\""+duration+"\"},\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\""+video+"\",\"pix_fmt\":\"yuv420p\",\"width\":640,\"height\":360}"+audio+"]}";
    }
    @Test void readsRealDurationAndSupportsSilentVideo() throws Exception {
        var info=probe.parse(json.readTree(metadata("h264","","mov,mp4,m4a,3gp,3g2,mj2","12.345")));
        assertThat(info.durationMs()).isEqualTo(12345); assertThat(info.audioCodec()).isNull();
        info=probe.parse(json.readTree(metadata("h264",",{\"codec_type\":\"audio\",\"codec_name\":\"aac\"}","mp4","1")));
        assertThat(info.audioCodec()).isEqualTo("aac");
    }
    @ParameterizedTest @ValueSource(strings={"hevc","mpeg4","vp9"})
    void rejectsCodecsThatCannotBePromisedToPlay(String codec) throws Exception {
        var node=json.readTree(metadata(codec,"","mp4","10"));
        assertThatThrownBy(()->probe.parse(node)).isInstanceOf(ApiException.class).hasMessageContaining("H.264");
    }
    @Test void rejectsFakeContainersInvalidDurationAndUnsupportedAudio() throws Exception {
        for(String text:new String[]{metadata("h264","","matroska,webm","10"),metadata("h264","","mp4","NaN"),metadata("h264","","mp4","-1"),metadata("h264",",{\"codec_type\":\"audio\",\"codec_name\":\"opus\"}","mp4","10")}) {
            var node=json.readTree(text); assertThatThrownBy(()->probe.parse(node)).isInstanceOf(ApiException.class);
        }
    }
}

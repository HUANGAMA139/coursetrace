package com.coursetrace.learning;

import com.coursetrace.video.CourseVideo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningValidationTest {
    final LearningJson json=new LearningJson(new ObjectMapper());
    final TranscriptService transcript=new TranscriptService(mock(SegmentMapper.class),json,Clock.systemUTC());
    final LearningConfig config=new LearningConfig("asr","chat","https://dashscope.aliyuncs.com/api/v1","https://dashscope.aliyuncs.com/compatible-mode/v1","v1",3600,8000,8,240,60);

    @Test void localTimestampsAreMappedToVideoOrigin() {
        var video=new CourseVideo();video.setDurationMs(5000L);
        var audio=new Artifact();audio.metadataJson="{\"audioOriginMs\":2000}";
        var lines=transcript.prepare("{\"transcripts\":[{\"sentences\":[{\"begin_time\":500,\"end_time\":1000,\"text\":\"课程内容\"}]}]}",audio,video,config);
        assertThat(lines.getFirst().beginMs()).isEqualTo(2500);assertThat(lines.getFirst().endMs()).isEqualTo(3000);
    }
    @Test void outOfBoundsOrNonNumericTimestampsAreRejected() {
        var video=new CourseVideo();video.setDurationMs(5000L);var audio=new Artifact();audio.metadataJson="{\"audioOriginMs\":0}";
        for(String begin:List.of("-1000","\"NaN\"","6000")){
            String raw="{\"transcripts\":[{\"sentences\":[{\"begin_time\":"+begin+",\"end_time\":7000,\"text\":\"课程内容\"}]}]}";
            assertThatThrownBy(()->transcript.prepare(raw,audio,video,config)).isInstanceOf(LearningFailure.class);
        }
    }
    @Test void credentialsCannotBeSentToUserSuppliedOrInsecureHosts() {
        for(String value:List.of("http://dashscope.aliyuncs.com/api/v1","https://localhost/api/v1","https://dashscope.aliyuncs.com.evil.example/api/v1","https://secret@dashscope.aliyuncs.com/api/v1")){
            assertThatThrownBy(()->QwenAsrClient.official(value,true)).isInstanceOf(LearningFailure.class);
        }
        assertThatCode(()->QwenAsrClient.official("https://workspace.cn-beijing.maas.aliyuncs.com/api/v1",true)).doesNotThrowAnyException();
        assertThatCode(()->QwenAsrClient.official("https://bucket.oss-cn-beijing.aliyuncs.com/result.json?signature=example",false)).doesNotThrowAnyException();
    }
    @Test void jsonModeStillRequiresSourceIdsFromThisWindow() {
        var service=new NoteService(null,null,null,null,null,json,Clock.systemUTC(),mock(org.springframework.transaction.PlatformTransactionManager.class));
        var segment=new Segment();segment.id=10L;segment.beginMs=1000;segment.endMs=2000;segment.text="有序数组";
        var valid="{\"chapters\":[{\"title\":\"前提\",\"summary\":\"数组需要有序\",\"points\":[\"有序数组\"],\"sourceSegmentIds\":[10]}]}";
        var notes=service.validate(valid,List.of(segment),1,2,1,"digest",config);
        assertThat(notes.getFirst().beginMs).isEqualTo(1000);
        assertThatThrownBy(()->service.validate(valid.replace("[10]","[11]"),List.of(segment),1,2,1,"digest",config)).isInstanceOf(LearningFailure.class);
        assertThatThrownBy(()->service.validate("not JSON",List.of(segment),1,2,1,"digest",config)).isInstanceOf(LearningFailure.class);
    }
}

package com.coursetrace.learning;

import com.coursetrace.media.MediaProperties;
import com.coursetrace.storage.StorageProperties;
import com.coursetrace.video.CourseVideo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AudioExtractorTest {
    @TempDir Path directory;
    Path ffmpeg,ffprobe,source,extracted,tempRoot;
    MediaStorage storage;
    ArtifactStore store;
    LearningJson json=new LearningJson(new ObjectMapper());
    LearningProperties settings;

    @BeforeEach void prepare() throws Exception {
        var root=Path.of("").toAbsolutePath();
        if(!Files.isDirectory(root.resolve(".tools")))root=root.getParent();
        ffmpeg=root.resolve(".tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe");
        ffprobe=ffmpeg.resolveSibling("ffprobe.exe");
        Assumptions.assumeTrue(Files.isRegularFile(ffmpeg)&&Files.isRegularFile(ffprobe),"Portable FFmpeg is required for this real-media test");
        source=directory.resolve("delayed.mp4");extracted=directory.resolve("extracted.flac");tempRoot=directory.resolve("work");
        run(List.of(ffmpeg.toString(),"-hide_banner","-loglevel","error","-y","-f","lavfi","-i","color=c=black:s=160x120:r=10:d=5",
            "-itsoffset","2","-f","lavfi","-i","sine=frequency=440:duration=2","-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p",
            "-c:a","aac","-t","5",source.toString()));
        storage=mock(MediaStorage.class);
        doAnswer(call->{Files.copy(source,call.getArgument(2,Path.class),StandardCopyOption.REPLACE_EXISTING);return null;})
            .when(storage).download(anyString(),anyString(),any(),anyLong());
        doAnswer(call->{Files.copy(call.getArgument(2,Path.class),extracted,StandardCopyOption.REPLACE_EXISTING);return null;})
            .when(storage).put(anyString(),anyString(),any(),anyString());
        store=new ArtifactStore(mock(ArtifactMapper.class),storage,new StorageProperties("unused","unused","unused","test-videos"),Clock.systemUTC());
        settings=new LearningProperties();settings.setFfmpegPath(ffmpeg.toString());
    }
    private CourseVideo video() throws Exception {
        var video=new CourseVideo();video.setId(1L);video.setBucketName("test-videos");video.setObjectKey("delayed.mp4");video.setSizeBytes(Files.size(source));video.setDurationMs(5000L);return video;
    }
    @Test void actualFfmpegPreservesTwoSecondAudioDelayAndCleansTemporaryDirectory() throws Exception {
        var extractor=new AudioExtractor(store,storage,new MediaProperties(ffprobe.toString(),tempRoot.toString(),15),settings,json);
        var artifact=extractor.extract(video());
        assertThat(json.read(artifact.metadataJson).path("audioOriginMs").asLong()).isZero();
        assertThat(json.read(artifact.metadataJson).path("durationMs").asLong()).isEqualTo(5000);
        assertThat(artifact.sha256).hasSize(64);
        var pcm=directory.resolve("samples.bin");
        run(List.of(ffmpeg.toString(),"-hide_banner","-loglevel","error","-y","-i",extracted.toString(),"-f","f32le","-c:a","pcm_f32le",pcm.toString()));
        var bytes=ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(rms(bytes,8000,22000)).isLessThan(.001);
        assertThat(rms(bytes,38000,54000)).isGreaterThan(.03);
        try(var files=Files.list(tempRoot)){assertThat(files.count()).isZero();}
    }
    @Test void missingToolFailsClearlyAndDeletesDownloadedSource() throws Exception {
        settings.setFfmpegPath(directory.resolve("missing-ffmpeg.exe").toString());
        var extractor=new AudioExtractor(store,storage,new MediaProperties(ffprobe.toString(),tempRoot.toString(),15),settings,json);
        assertThatThrownBy(()->extractor.extract(video())).isInstanceOf(LearningFailure.class)
            .extracting(e->((LearningFailure)e).code).isEqualTo("MEDIA_UNAVAILABLE");
        try(var files=Files.list(tempRoot)){assertThat(files.count()).isZero();}
        verify(storage,never()).put(anyString(),anyString(),any(),anyString());
    }
    private double rms(ByteBuffer bytes,int begin,int end){double sum=0;for(int i=begin;i<end;i++){float f=bytes.getFloat(i*4);sum+=f*f;}return Math.sqrt(sum/(end-begin));}
    private void run(List<String> args) throws Exception {
        var output=directory.resolve(UUID.randomUUID()+".log");
        var process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try{assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(Files.readString(output)).isZero();}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
}

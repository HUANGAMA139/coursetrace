package com.coursetrace.learning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

@Component @Validated
@ConfigurationProperties("coursetrace.learning")
public class LearningProperties {
    private String apiKey="",nativeBase="https://dashscope.aliyuncs.com/api/v1",chatBase="https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String asrModel="qwen-audio-3.1-asr-flash-filetrans",chatModel="qwen3.7-plus",promptVersion="course-notes-v1";
    private String ffmpegPath=".tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe";
    @Min(1) @Max(14400) private int maxDurationSeconds=3600;
    @Min(200) @Max(8000) private int windowChars=8000;
    @Min(1) @Max(8) private int maxWindows=8;
    @Min(1) @Max(240) private int maxPolls=240;
    @Min(1) @Max(60) private int waitMinutes=60;
    public LearningConfig snapshot() { return new LearningConfig(asrModel,chatModel,nativeBase,chatBase,promptVersion,maxDurationSeconds,windowChars,maxWindows,maxPolls,waitMinutes); }
    public String getApiKey(){return apiKey;} public void setApiKey(String v){apiKey=v;}
    public String getNativeBase(){return nativeBase;} public void setNativeBase(String v){nativeBase=v;}
    public String getChatBase(){return chatBase;} public void setChatBase(String v){chatBase=v;}
    public String getAsrModel(){return asrModel;} public void setAsrModel(String v){asrModel=v;}
    public String getChatModel(){return chatModel;} public void setChatModel(String v){chatModel=v;}
    public String getPromptVersion(){return promptVersion;} public void setPromptVersion(String v){promptVersion=v;}
    public String getFfmpegPath(){return ffmpegPath;} public void setFfmpegPath(String v){ffmpegPath=v;}
    public int getMaxDurationSeconds(){return maxDurationSeconds;} public void setMaxDurationSeconds(int v){maxDurationSeconds=v;}
    public int getWindowChars(){return windowChars;} public void setWindowChars(int v){windowChars=v;}
    public int getMaxWindows(){return maxWindows;} public void setMaxWindows(int v){maxWindows=v;}
    public int getMaxPolls(){return maxPolls;} public void setMaxPolls(int v){maxPolls=v;}
    public int getWaitMinutes(){return waitMinutes;} public void setWaitMinutes(int v){waitMinutes=v;}
}

package com.coursetrace.learning;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class LearningJson {
    private final ObjectMapper json;
    public LearningJson(ObjectMapper json){this.json=json;}
    public String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Could not encode learning data");}}
    public JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw LearningFailure.permanent("INVALID_LEARNING_DATA","处理数据格式不正确");}}
    public LearningConfig config(String value){try{return json.readValue(value,LearningConfig.class);}catch(Exception e){throw LearningFailure.permanent("INVALID_TASK_CONFIG","任务配置无法读取");}}
    public static String hash(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
    public static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
}

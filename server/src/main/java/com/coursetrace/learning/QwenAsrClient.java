package com.coursetrace.learning;

import okhttp3.*;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Component
public class QwenAsrClient {
    private final LearningProperties properties;
    private final LearningJson json;
    private final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(30)).callTimeout(Duration.ofSeconds(30)).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build();
    public QwenAsrClient(LearningProperties properties,LearningJson json){this.properties=properties;this.json=json;}
    public boolean configured(){
        try{official(properties.getNativeBase(),true);official(properties.getChatBase(),true);return !properties.getApiKey().isBlank();}
        catch(RuntimeException e){return false;}
    }
    public String upload(Path audio,LearningConfig config){
        var policy=json.read(call(config.nativeBase()+"/uploads?action=getPolicy&model="+config.asrModel(),null,true,Map.of(),30,false)).path("data");
        String host=policy.path("upload_host").asText();official(host,false);
        String filename=UUID.randomUUID()+".flac",key=policy.path("upload_dir").asText()+"/"+filename;
        var body=new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("OSSAccessKeyId",policy.path("oss_access_key_id").asText())
            .addFormDataPart("Signature",policy.path("signature").asText()).addFormDataPart("policy",policy.path("policy").asText())
            .addFormDataPart("x-oss-object-acl",policy.path("x_oss_object_acl").asText())
            .addFormDataPart("x-oss-forbid-overwrite",policy.path("x_oss_forbid_overwrite").asText())
            .addFormDataPart("key",key).addFormDataPart("success_action_status","200")
            .addFormDataPart("file",filename,RequestBody.create(audio.toFile(),MediaType.get("audio/flac"))).build();
        call(host,body,false,Map.of(),60,false);
        return "oss://"+key;
    }
    public String submit(String audioUrl,LearningConfig config){
        var body=RequestBody.create(json.write(Map.of("model",config.asrModel(),"input",Map.of("file_urls",List.of(audioUrl)),
            "parameters",Map.of("channel_id",List.of(0),"language_hints",List.of("zh","en")))),MediaType.get("application/json"));
        String raw=call(config.nativeBase()+"/services/audio/asr/transcription",body,true,
            Map.of("X-DashScope-Async","enable","X-DashScope-OssResourceResolve","enable"),30,true);
        com.fasterxml.jackson.databind.JsonNode response;
        try{response=json.read(raw);}catch(LearningFailure invalid){throw new LearningFailure("ASR_SUBMISSION_UNKNOWN","转录响应无法确认，暂不自动重新提交",false,true);}
        String id=response.path("output").path("task_id").asText();
        if(id.isBlank() || !id.matches("[a-zA-Z0-9-]{1,100}"))throw new LearningFailure("ASR_SUBMISSION_UNKNOWN","转录提交结果待确认，暂不自动重新提交",false,true);
        return id;
    }
    public com.fasterxml.jackson.databind.JsonNode poll(String taskId,LearningConfig config){
        if(!taskId.matches("[a-zA-Z0-9-]{1,100}"))throw LearningFailure.permanent("ASR_TASK_INVALID","转录任务标识不正确");
        return json.read(call(config.nativeBase()+"/tasks/"+taskId,null,true,Map.of(),15,false));
    }
    public String result(String url){return call(url,null,false,Map.of(),30,false);}
    private String call(String url,RequestBody body,boolean authenticated,Map<String,String> headers,int seconds,boolean paidPost){
        official(url,authenticated);
        var request=new Request.Builder().url(url);
        if(authenticated){if(properties.getApiKey().isBlank())throw LearningFailure.permanent("AI_NOT_CONFIGURED","转录服务尚未配置");request.header("Authorization","Bearer "+properties.getApiKey());}
        headers.forEach(request::header);if(body!=null)request.post(body);
        try(var response=client.newBuilder().callTimeout(Duration.ofSeconds(seconds)).build().newCall(request.build()).execute()){
            if(!response.isSuccessful()){
                int status=response.code();
                boolean uncertain=paidPost && (status>=500 || status==408);
                throw new LearningFailure(uncertain?"ASR_SUBMISSION_UNKNOWN":status==401||status==403?"AI_AUTH_FAILED":"AI_HTTP_"+status,
                    uncertain?"转录提交结果待确认，暂不自动重新提交":status==401||status==403?"模型服务鉴权失败，请检查本地配置":"模型服务暂时无法完成请求",
                    !uncertain && (status==429 || status>=500),uncertain);
            }
            if(response.body()==null)return "";
            var bytes=response.body().byteStream().readNBytes(4*1024*1024+1);
            if(bytes.length>4*1024*1024)throw LearningFailure.permanent("ASR_RESULT_TOO_LARGE","转录结果超过允许大小");
            return new String(bytes,StandardCharsets.UTF_8);
        }catch(LearningFailure e){throw e;}
        catch(Exception e){throw new LearningFailure(paidPost?"ASR_SUBMISSION_UNKNOWN":"AI_NETWORK_ERROR",
            paidPost?"转录提交结果待确认，暂不自动重新提交":"模型服务连接超时或中断",!paidPost,paidPost);}
    }
    public static void official(String value,boolean authenticated){
        try{
            var uri=URI.create(value);String host=uri.getHost();
            boolean allowed=host!=null && (authenticated
                ? Set.of("dashscope.aliyuncs.com","dashscope-intl.aliyuncs.com","dashscope-us.aliyuncs.com").contains(host)
                    || host.endsWith(".maas.aliyuncs.com")
                : host.endsWith(".aliyuncs.com"));
            if(!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || !allowed || uri.getFragment()!=null)
                throw new IllegalArgumentException();
        }catch(Exception e){throw LearningFailure.permanent("AI_ENDPOINT_INVALID","模型服务地址配置不正确");}
    }
}

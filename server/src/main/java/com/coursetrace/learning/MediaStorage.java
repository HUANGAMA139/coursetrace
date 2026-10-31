package com.coursetrace.learning;

import com.coursetrace.storage.StorageProperties;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;

@Component
public class MediaStorage implements AutoCloseable {
    private final StorageProperties properties;
    private MinioClient client;
    public MediaStorage(StorageProperties properties){this.properties=properties;}
    private synchronized MinioClient client(){
        if(client==null) {
            if(properties.secretKey()==null || properties.secretKey().isBlank()) throw new LearningFailure("STORAGE_UNAVAILABLE","视频存储尚未配置",true);
            client=MinioClient.builder().endpoint(properties.endpoint()).credentials(properties.accessKey(),properties.secretKey())
                .httpClient(new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(30))
                    .writeTimeout(Duration.ofSeconds(60)).callTimeout(Duration.ofSeconds(60)).retryOnConnectionFailure(false).addInterceptor(new com.coursetrace.common.ReadOnlyConnectionRecovery()).build()).build();
        }
        return client;
    }
    public long size(String bucket,String key){
        try{return client().statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size();}
        catch(Exception e){throw failure(e);}
    }
    public void download(String bucket,String key,Path target,long maxBytes){
        try(var input=client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
            var output=Files.newOutputStream(target)){
            byte[] buffer=new byte[65536];long count=0;int length;
            while((length=input.read(buffer))!=-1){count+=length;if(count>maxBytes)throw LearningFailure.permanent("ARTIFACT_TOO_LARGE","处理文件超过允许大小");output.write(buffer,0,length);}
        }catch(LearningFailure e){throw e;}catch(Exception e){throw failure(e);}
    }
    public byte[] bytes(String bucket,String key,int limit){
        try(var input=client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())){
            var bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw LearningFailure.permanent("ARTIFACT_TOO_LARGE","处理结果超过允许大小");return bytes;
        }catch(LearningFailure e){throw e;}catch(Exception e){throw failure(e);}
    }
    public void put(String bucket,String key,Path file,String type){
        try(var input=Files.newInputStream(file)){
            client().putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(type).stream(input,Files.size(file),-1L).build());
        }catch(Exception e){throw failure(e);}
    }
    private LearningFailure failure(Exception e){
        if(e instanceof ErrorResponseException error && "NoSuchKey".equals(error.errorResponse().code()))
            return LearningFailure.permanent("ARTIFACT_MISSING","视频或处理文件不存在，请检查原文件");
        return new LearningFailure("STORAGE_UNAVAILABLE","视频存储暂时不可用",true);
    }
    @Override public void close() throws Exception {if(client!=null)client.close();}
    public void remove(String bucket,String key){
        try{client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());}
        catch(Exception e){throw failure(e);}
    }
}

package com.coursetrace.storage;

import com.coursetrace.common.ApiException;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Component
@EnableConfigurationProperties(StorageProperties.class)
public class MinioObjectStorage implements ObjectStorage, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);
    private final StorageProperties properties;
    private volatile MinioClient client;
    private volatile boolean bucketReady;

    public MinioObjectStorage(StorageProperties properties) { this.properties = properties; }

    // Lazy construction keeps login/course APIs usable if storage credentials are absent.
    private synchronized MinioClient client() {
        if (client == null) {
            if (properties.secretKey() == null || properties.secretKey().isBlank()) throw unavailable();
            client = MinioClient.builder().endpoint(properties.endpoint())
                    .credentials(properties.accessKey(), properties.secretKey())
                    .httpClient(new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5))
                            .readTimeout(Duration.ofSeconds(30)).writeTimeout(Duration.ofSeconds(60))
                            .callTimeout(Duration.ofMinutes(5)).retryOnConnectionFailure(false).addInterceptor(new com.coursetrace.common.ReadOnlyConnectionRecovery()).build()).build();
        }
        return client;
    }

    private synchronized void ensureBucket(String bucket) throws Exception {
        if (bucketReady) return;
        var api = client();
        if (!api.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            try { api.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); }
            catch (ErrorResponseException ex) {
                if (!"BucketAlreadyOwnedByYou".equals(ex.errorResponse().code())) throw ex;
            }
        }
        // Newly created buckets stay private: no anonymous policy is installed.
        bucketReady = true;
    }

    @Override
    public void put(String bucket, String key, Path file, long size) {
        putFile(bucket,key,file,size,"video/mp4");
    }

    @Override
    public void putFile(String bucket,String key,Path file,long size,String contentType) {
        try {
            ensureBucket(bucket);
            try (var input = Files.newInputStream(file)) {
                client().putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                        .stream(input, size, -1L).contentType(contentType).build());
            }
        } catch (Exception ex) { throw failure(ex); }
    }

    @Override
    public void compose(String bucket,java.util.List<String> keys,String destination){
        try{
            ensureBucket(bucket);
            // MinIO 9 replaces source entries after HEAD; it requires a mutable list.
            var sources=new java.util.ArrayList<>(keys.stream().map(key->SourceObject.builder().bucket(bucket).object(key).build()).toList());
            client().composeObject(ComposeObjectArgs.builder().bucket(bucket).object(destination).sources(sources)
                    .headers(java.util.Map.of("Content-Type","video/mp4")).build());
        }catch(Exception ex){throw failure(ex);}
    }

    @Override
    public java.util.List<String> list(String bucket,String prefix){
        try{
            var keys=new java.util.ArrayList<String>();
            for(var item:client().listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())){
                keys.add(item.get().objectName());if(keys.size()>=2000)break;
            }
            return keys;
        }catch(Exception ex){throw failure(ex);}
    }

    @Override
    public InputStream open(String bucket, String key, long offset, long length) {
        try {
            return client().getObject(GetObjectArgs.builder().bucket(bucket).object(key)
                    .offset(offset).length(length).build());
        } catch (Exception ex) { throw failure(ex); }
    }

    @Override
    public long size(String bucket, String key) {
        try { return client().statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size(); }
        catch (Exception ex) { throw failure(ex); }
    }

    @Override
    public void remove(String bucket, String key) {
        try { client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception ex) { throw failure(ex); }
    }

    private ApiException failure(Exception ex) {
        // Do not log SDK requests, signed URLs or credentials.
        if (ex instanceof ErrorResponseException error && "NoSuchKey".equals(error.errorResponse().code()))
            return new ApiException(HttpStatus.NOT_FOUND, "VIDEO_FILE_NOT_FOUND", "视频文件不存在，请重新上传");
        log.warn("Object storage failed: type={}, origin={}", ex.getClass().getSimpleName(), ex.getStackTrace().length==0?"unknown":ex.getStackTrace()[0]);
        return unavailable();
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "视频存储暂时不可用，请确认 MinIO 已启动后重试");
    }

    @Override
    public void close() throws Exception { if (client != null) client.close(); }
}

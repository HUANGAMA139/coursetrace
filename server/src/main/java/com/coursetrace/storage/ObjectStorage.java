package com.coursetrace.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface ObjectStorage {
    void put(String bucket, String key, Path file, long size);
    InputStream open(String bucket, String key, long offset, long length);
    long size(String bucket, String key);
    void remove(String bucket, String key);
    default void putFile(String bucket,String key,Path file,long size,String contentType){put(bucket,key,file,size);}
    default void compose(String bucket,java.util.List<String> keys,String destination){throw new UnsupportedOperationException("Object composition is not supported");}
    default java.util.List<String> list(String bucket,String prefix){throw new UnsupportedOperationException("Object listing is not supported");}
}

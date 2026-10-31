package com.coursetrace.storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** Real adapter regression: MinIO 9 mutates its source list during composition. */
@EnabledIfEnvironmentVariable(named="COURSETRACE_LIVE_STORAGE",matches="true")
class MinioComposeLiveTest {
 @TempDir Path temporary;
 @Test void composesRealPartsWithCurrentSdkAndServer()throws Exception{
  var root=Path.of(System.getProperty("user.dir")).toAbsolutePath();if(!Files.exists(root.resolve(".env.local")))root=root.getParent();
  var config=new HashMap<String,String>();for(String row:Files.readAllLines(root.resolve(".env.local"))){int at=row.indexOf('=');if(at>0)config.put(row.substring(0,at).strip(),row.substring(at+1).strip());}
  String bucket=config.getOrDefault("MINIO_VIDEO_BUCKET","coursetrace-videos"),prefix="integration/compose-"+UUID.randomUUID()+"/";
  var settings=new StorageProperties(config.getOrDefault("MINIO_ENDPOINT","http://127.0.0.1:9000"),config.getOrDefault("MINIO_ROOT_USER","coursetrace"),config.get("MINIO_ROOT_PASSWORD"),bucket);
  try(var storage=new MinioObjectStorage(settings)){
   try{
    byte[] first=new byte[8*1024*1024],last=new byte[]{1,2,3};Path a=temporary.resolve("a"),b=temporary.resolve("b");Files.write(a,first);Files.write(b,last);
    storage.putFile(bucket,prefix+"a",a,first.length,"application/octet-stream");storage.putFile(bucket,prefix+"b",b,last.length,"application/octet-stream");
    storage.compose(bucket,List.of(prefix+"a",prefix+"b"),prefix+"final.mp4");
    assertThat(storage.size(bucket,prefix+"final.mp4")).isEqualTo(first.length+last.length);
    try(var result=storage.open(bucket,prefix+"final.mp4",0,first.length+last.length)){var bytes=result.readAllBytes();assertThat(bytes).hasSize(first.length+last.length);assertThat(Arrays.copyOfRange(bytes,first.length,bytes.length)).isEqualTo(last);}
   }finally{for(String key:List.of("a","b","final.mp4"))try{storage.remove(bucket,prefix+key);}catch(Exception ignored){}}
  }
 }
}

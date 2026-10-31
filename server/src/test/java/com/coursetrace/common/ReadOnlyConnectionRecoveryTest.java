package com.coursetrace.common;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class ReadOnlyConnectionRecoveryTest {
 @Test void readRecoversAfterConnectionClosesBeforeHeaders()throws Exception{verify("GET",true);}
 @Test void writeIsNeverAutomaticallyReplayed()throws Exception{verify("POST",false);}
 private void verify(String method,boolean shouldRecover)throws Exception{
  var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{
   exchange.getRequestBody().readAllBytes();if(calls.incrementAndGet()==1){exchange.close();return;}
   byte[] body="data".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
  });server.start();
  var client=new OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(Duration.ofSeconds(3)).addInterceptor(new ReadOnlyConnectionRecovery()).build();
  try{
   var request=new Request.Builder().url("http://127.0.0.1:"+server.getAddress().getPort()+"/").method(method,"POST".equals(method)?RequestBody.create("write",MediaType.get("text/plain")):null).build();
   if(shouldRecover){try(var response=client.newCall(request).execute()){assertThat(response.body().string()).isEqualTo("data");}assertThat(calls.get()).isEqualTo(2);}
   else{assertThatThrownBy(()->client.newCall(request).execute()).isInstanceOf(java.io.IOException.class);assertThat(calls.get()).isEqualTo(1);}
  }finally{server.stop(0);client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
 }
}

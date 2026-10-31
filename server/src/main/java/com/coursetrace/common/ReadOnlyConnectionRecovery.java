package com.coursetrace.common;
import okhttp3.*;
import java.io.IOException;
/** Reopen a failed GET/HEAD connection once; never replay storage writes. */
public final class ReadOnlyConnectionRecovery implements Interceptor {
 @Override public Response intercept(Chain chain)throws IOException{
  var request=chain.request();
  try{return chain.proceed(request);}
  catch(IOException first){
   if(!"GET".equals(request.method())&&!"HEAD".equals(request.method()))throw first;
   return chain.proceed(request);
  }
 }
}

package com.coursetrace.retrieval;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import com.coursetrace.learning.*;
@Component @ConfigurationProperties("coursetrace.retrieval")
public class RetrievalProperties {
 private String qdrantUrl="http://127.0.0.1:6333";
 private String embeddingModel="text-embedding-v4";
 private int dimensions=1024;
 private String collection="coursetrace_segments_te4_1024_v1";
 private int targetChars=800;
 private int maxChars=1200;
 private int maxBytes=6000;
 private int overlapChars=160;
 private int batchSize=10;
 private int maxChunks=200;
 private int topK=6;
 private String chunkerVersion="subtitle-chunk-v1";
 public String getQdrantUrl(){return qdrantUrl;} public void setQdrantUrl(String v){qdrantUrl=v;}
 public String getEmbeddingModel(){return embeddingModel;} public void setEmbeddingModel(String v){embeddingModel=v;}
 public int getDimensions(){return dimensions;} public void setDimensions(int v){dimensions=v;}
 public String getCollection(){return collection;} public void setCollection(String v){collection=v;}
 public int getTargetChars(){return targetChars;} public void setTargetChars(int v){targetChars=v;}
 public int getMaxChars(){return maxChars;} public void setMaxChars(int v){maxChars=v;}
 public int getMaxBytes(){return maxBytes;} public void setMaxBytes(int v){maxBytes=v;}
 public int getOverlapChars(){return overlapChars;} public void setOverlapChars(int v){overlapChars=v;}
 public int getBatchSize(){return batchSize;} public void setBatchSize(int v){batchSize=v;}
 public int getMaxChunks(){return maxChunks;} public void setMaxChunks(int v){maxChunks=v;}
 public int getTopK(){return topK;} public void setTopK(int v){topK=v;}
 public String getChunkerVersion(){return chunkerVersion;} public void setChunkerVersion(String v){chunkerVersion=v;}
 public Options snapshot(LearningProperties learning) {
  if(dimensions!=1024||batchSize<1||batchSize>10||maxChunks<1||maxChunks>200||targetChars<100||targetChars>maxChars||maxChars>1200||maxBytes<100||maxBytes>6000||overlapChars<0||overlapChars>=targetChars||topK<1||topK>10||!collection.matches("[a-zA-Z0-9_-]{1,100}"))
   throw LearningFailure.permanent("INVALID_SEARCH_CONFIG","检索配置超出本阶段限制");
  return new Options(embeddingModel,dimensions,collection,targetChars,maxChars,maxBytes,overlapChars,batchSize,maxChunks,topK,chunkerVersion,learning.getChatBase(),learning.getChatModel(),"course-qa-v1");
 }
 public record Options(String embeddingModel,int dimensions,String collection,int targetChars,int maxChars,int maxBytes,int overlapChars,int batchSize,int maxChunks,int topK,String chunkerVersion,String embeddingBase,String chatModel,String promptVersion){}
 public record IndexRun(long indexId,Options config){}
}

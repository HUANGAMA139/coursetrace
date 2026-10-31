package com.coursetrace.agent;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Component @ConfigurationProperties("coursetrace.agent")
public class AgentProperties {
 private int maxRounds=4,deadlineSeconds=120;
 public int getMaxRounds(){return maxRounds;} public void setMaxRounds(int v){maxRounds=v;}
 public int getDeadlineSeconds(){return deadlineSeconds;} public void setDeadlineSeconds(int v){deadlineSeconds=v;}
 public Budget snapshot(){
  if(maxRounds<1||maxRounds>4||deadlineSeconds<40||deadlineSeconds>120)throw new IllegalStateException("Invalid Agent budget");
  return new Budget(maxRounds,4,2,2,5,5000,deadlineSeconds,"course-agent-v1");
 }
 public record Budget(int maxRounds,int maxTools,int maxSearches,int maxReads,int maxChats,int maxOutputTokens,int deadlineSeconds,String promptVersion){}
}

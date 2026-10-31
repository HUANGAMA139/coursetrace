package com.coursetrace.limit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component @ConfigurationProperties("coursetrace.rate-limit")
public class RateProperties {
 private boolean enabled=true;
 private int qaCapacity=2,qaRefillSeconds=10,globalQaCapacity=10,globalQaRefillSeconds=1;
 private int learningCapacity=1,learningRefillSeconds=60,indexCapacity=2,indexRefillSeconds=30;
 private int globalLearningCapacity=3,globalLearningRefillSeconds=20,globalIndexCapacity=4,globalIndexRefillSeconds=10;
 public boolean isEnabled(){return enabled;}public void setEnabled(boolean v){enabled=v;}
 public int getQaCapacity(){return qaCapacity;}public void setQaCapacity(int v){qaCapacity=v;}
 public int getQaRefillSeconds(){return qaRefillSeconds;}public void setQaRefillSeconds(int v){qaRefillSeconds=v;}
 public int getGlobalQaCapacity(){return globalQaCapacity;}public void setGlobalQaCapacity(int v){globalQaCapacity=v;}
 public int getGlobalQaRefillSeconds(){return globalQaRefillSeconds;}public void setGlobalQaRefillSeconds(int v){globalQaRefillSeconds=v;}
 public int getLearningCapacity(){return learningCapacity;}public void setLearningCapacity(int v){learningCapacity=v;}
 public int getLearningRefillSeconds(){return learningRefillSeconds;}public void setLearningRefillSeconds(int v){learningRefillSeconds=v;}
 public int getIndexCapacity(){return indexCapacity;}public void setIndexCapacity(int v){indexCapacity=v;}
 public int getIndexRefillSeconds(){return indexRefillSeconds;}public void setIndexRefillSeconds(int v){indexRefillSeconds=v;}
 public int getGlobalLearningCapacity(){return globalLearningCapacity;}public void setGlobalLearningCapacity(int v){globalLearningCapacity=v;}
 public int getGlobalLearningRefillSeconds(){return globalLearningRefillSeconds;}public void setGlobalLearningRefillSeconds(int v){globalLearningRefillSeconds=v;}
 public int getGlobalIndexCapacity(){return globalIndexCapacity;}public void setGlobalIndexCapacity(int v){globalIndexCapacity=v;}
 public int getGlobalIndexRefillSeconds(){return globalIndexRefillSeconds;}public void setGlobalIndexRefillSeconds(int v){globalIndexRefillSeconds=v;}
 public Policy policy(String scope){var p=switch(scope){case "qa"->new Policy(qaCapacity,qaRefillSeconds,globalQaCapacity,globalQaRefillSeconds);case "learning"->new Policy(learningCapacity,learningRefillSeconds,globalLearningCapacity,globalLearningRefillSeconds);case "index"->new Policy(indexCapacity,indexRefillSeconds,globalIndexCapacity,globalIndexRefillSeconds);default->throw new IllegalArgumentException("Unknown rate scope");};if(p.userCapacity<1||p.userSeconds<1||p.globalCapacity<1||p.globalSeconds<1)throw new IllegalStateException("Invalid rate configuration");return p;}
 public record Policy(int userCapacity,int userSeconds,int globalCapacity,int globalSeconds){}
}

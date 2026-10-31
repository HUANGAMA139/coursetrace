package com.coursetrace.learning;

public class LearningFailure extends RuntimeException {
    public final String code;
    public final boolean retryable;
    public final boolean uncertain;
    public LearningFailure(String code,String message,boolean retryable) { this(code,message,retryable,false); }
    public LearningFailure(String code,String message,boolean retryable,boolean uncertain) {
        super(message); this.code=code; this.retryable=retryable; this.uncertain=uncertain;
    }
    public static LearningFailure permanent(String code,String message) { return new LearningFailure(code,message,false); }
}

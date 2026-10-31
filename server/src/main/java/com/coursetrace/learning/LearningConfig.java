package com.coursetrace.learning;

public record LearningConfig(String asrModel,String chatModel,String nativeBase,String chatBase,String promptVersion,
                             int maxDurationSeconds,int windowChars,int maxWindows,int maxPolls,int waitMinutes) {}

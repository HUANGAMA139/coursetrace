package com.coursetrace.processing;

public final class ProcessingFailures {
    private ProcessingFailures() {}
    public static class Retryable extends RuntimeException {
        public Retryable() { super("Video storage temporarily unavailable"); }
    }
    public static class Permanent extends RuntimeException {
        public Permanent() { super("Video source requires attention"); }
    }
    public static class InvalidEvent extends RuntimeException {
        public InvalidEvent() { super("Invalid processing event"); }
    }
}

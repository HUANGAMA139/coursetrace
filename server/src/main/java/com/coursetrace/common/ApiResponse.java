package com.coursetrace.common;

import java.util.Map;

public record ApiResponse<T>(String code, String message, T data, Map<String, String> fieldErrors) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "操作成功", data, null);
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(code, message, null, null);
    }
}

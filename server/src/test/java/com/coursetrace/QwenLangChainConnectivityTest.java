package com.coursetrace;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in prevents ordinary builds from making billable API calls. */
@EnabledIfEnvironmentVariable(named = "COURSETRACE_LIVE_AI", matches = "true")
class QwenLangChainConnectivityTest {
    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void qwenChatWorksThroughThePinnedJavaAdapter() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        assertThat(key).as("DASHSCOPE_API_KEY must be configured").isNotBlank();
        var model = OpenAiChatModel.builder()
                .baseUrl(setting("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .apiKey(key)
                .modelName(setting("QWEN_CHAT_MODEL", "qwen3.7-plus"))
                .customParameters(Map.of("enable_thinking", false))
                .maxTokens(64)
                .maxRetries(0)
                .timeout(Duration.ofSeconds(30))
                .build();
        assertThat(model.chat("请只回复：CourseTrace Java 接入成功。"))
                .contains("CourseTrace");
    }

    @Test
    void qwenEmbeddingWorksThroughThePinnedJavaAdapter() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        assertThat(key).as("DASHSCOPE_API_KEY must be configured").isNotBlank();
        var model = OpenAiEmbeddingModel.builder()
                .baseUrl(setting("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .apiKey(key)
                .modelName(setting("QWEN_EMBEDDING_MODEL", "text-embedding-v4"))
                .dimensions(1024)
                .timeout(Duration.ofSeconds(30))
                .build();
        assertThat(model.embed("二叉树的前序遍历").content().vector()).hasSize(1024);
    }
}

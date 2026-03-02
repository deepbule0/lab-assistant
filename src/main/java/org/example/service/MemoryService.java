package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Memory 服务
 * 当单 session 的对话历史超过阈值时，自动调用 LLM 对历史进行摘要压缩，
 * 摘要注入到后续 system prompt 中，避免 context 超限。
 */
@Service
public class MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${memory.threshold:8}")
    private int threshold;

    @Value("${memory.model:qwen3-max}")
    private String model;

    private Generation generation;

    @PostConstruct
    public void init() {
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        generation = new Generation();
        logger.info("MemoryService 初始化完成，threshold={}, model={}", threshold, model);
    }

    public int getThreshold() {
        return threshold;
    }

    /**
     * 对历史消息列表生成摘要
     *
     * @param history 历史消息列表（role/content 格式）
     * @return 摘要文本
     */
    public String summarize(List<Map<String, String>> history) {
        if (history.isEmpty()) return "";

        logger.info("开始对 {} 条历史消息进行摘要压缩", history.size());

        StringBuilder historyText = new StringBuilder();
        for (Map<String, String> msg : history) {
            String role = "user".equals(msg.get("role")) ? "用户" : "助手";
            historyText.append(role).append(": ").append(msg.get("content")).append("\n");
        }

        String prompt = "请对以下对话历史进行简洁摘要，保留关键信息、用户意图和重要结论，摘要控制在300字以内：\n\n" + historyText;

        try {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build());

            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .resultFormat("message")
                    .messages(messages)
                    .build();

            GenerationResult result = generation.call(param);
            String summary = result.getOutput().getChoices().get(0).getMessage().getContent();
            logger.info("摘要生成完成，长度: {}", summary.length());
            return summary;
        } catch (Exception e) {
            logger.error("摘要生成失败", e);
            return "（历史摘要生成失败）";
        }
    }
}

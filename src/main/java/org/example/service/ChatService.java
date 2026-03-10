package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.CodeTools;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private CodeTools codeTools;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史消息和 memory 摘要）
     */
    public String buildSystemPrompt(List<Map<String, String>> history, String memorySummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Lab 实验室智能助手，可以：\n");
        sb.append("- 获取当前时间（getCurrentDateTime）\n");
        sb.append("- 检索实验室内部文档知识库（queryInternalDocs）\n");
        sb.append("- 生成、解释、调试代码（generateCode / explainCode / debugCode）\n\n");

        if (memorySummary != null && !memorySummary.isBlank()) {
            sb.append("--- 历史对话摘要（Memory）---\n");
            sb.append(memorySummary).append("\n");
            sb.append("--- 摘要结束 ---\n\n");
        }

        if (!history.isEmpty()) {
            sb.append("--- 近期对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    sb.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    sb.append("助手: ").append(content).append("\n");
                }
            }
            sb.append("--- 历史结束 ---\n\n");
        }

        sb.append("请基于以上上下文，回答用户的新问题。");
        return sb.toString();
    }

    public Object[] buildMethodToolsArray() {
        return new Object[]{dateTimeTools, internalDocsTools, codeTools};
    }

    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("lab_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .build();
    }

    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call()");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}

package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.CodeTools;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * 实验室 Agent 服务
 * 使用 Planner + Executor + Supervisor 多 Agent 架构，对用户问题进行深度分析和高质量解答
 */
@Service
public class LabAgentService {

    private static final Logger logger = LoggerFactory.getLogger(LabAgentService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private CodeTools codeTools;

    /**
     * 执行多 Agent 问答流程
     */
    public Optional<OverAllState> executeLabAgent(DashScopeChatModel chatModel, String userQuestion) throws GraphRunnerException {
        logger.info("启动 Lab 多 Agent 流程，问题: {}", userQuestion);

        Object[] tools = new Object[]{dateTimeTools, internalDocsTools, codeTools};

        ReactAgent plannerAgent = ReactAgent.builder()
                .name("planner_agent")
                .description("负责分析用户问题，制定回答策略和步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(tools)
                .outputKey("planner_plan")
                .build();

        ReactAgent executorAgent = ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 制定的步骤，调用工具收集信息")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(tools)
                .outputKey("executor_feedback")
                .build();

        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("lab_supervisor")
                .description("调度 Planner 与 Executor 完成高质量问答")
                .model(chatModel)
                .systemPrompt(buildSupervisorPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

        String taskPrompt = "用户问题：" + userQuestion + "\n\n请通过规划→执行→再规划的闭环，给出高质量、有深度的回答。";
        logger.info("调用 Supervisor Agent 开始编排...");
        return supervisorAgent.invoke(taskPrompt);
    }

    /**
     * 从执行结果中提取最终回答
     */
    public Optional<String> extractFinalAnswer(OverAllState state) {
        return state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .map(AssistantMessage::getText)
                .map(this::parseAgentResponse);
    }

    /**
     * 解析 Agent 响应，提取干净的回答内容
     * 如果响应是 JSON 格式且包含 decision=FINISH，则提取 answer 字段
     * 否则直接返回原始文本
     */
    private String parseAgentResponse(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return rawText;
        }

        String trimmed = rawText.trim();

        // 检查是否是 JSON 格式（以 { 开头）
        if (!trimmed.startsWith("{")) {
            return rawText;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(trimmed);

            // 检查是否包含 decision 字段且值为 FINISH
            if (root.has("decision") && "FINISH".equalsIgnoreCase(root.get("decision").asText())) {
                // 提取 answer 字段
                if (root.has("answer")) {
                    return root.get("answer").asText();
                }
            }

            // 如果不是 FINISH 决策或没有 answer 字段，返回原始文本
            return rawText;

        } catch (Exception e) {
            // JSON 解析失败，说明不是有效的 JSON，直接返回原始文本
            logger.debug("无法解析为 JSON，返回原始文本: {}", e.getMessage());
            return rawText;
        }
    }

    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取用户问题 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析问题类型（知识查询/代码生成/代码解释/综合分析），制定回答策略。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期工具。
                4. 可用工具：queryInternalDocs（检索实验室文档）、generateCode/explainCode/debugCode（代码相关）、getCurrentDateTime（时间）。
                5. 当 decision=FINISH 时，直接输出完整的 Markdown 格式回答，不要输出 JSON。

                ## 最终回答格式要求
                当 decision=FINISH 时，输出结构化 Markdown 回答，包含：
                - 问题理解
                - 核心解答（引用检索到的文档或生成的代码）
                - 总结与建议
                """;
    }

    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，调用相应工具并收集结果。
                - 将结果整理成结构化摘要，方便 Planner 生成最终回答。
                - 以 JSON 形式返回执行状态和证据：
                  {"status": "SUCCESS", "summary": "...", "evidence": "...", "nextHint": "..."}
                """;
    }

    private String buildSupervisorPrompt() {
        return """
                你是 Lab Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要分析问题或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. 当 planner_agent 输出 decision=FINISH 时：
                   - 如果输出是 JSON 格式（包含 decision 和 answer 字段），提取 answer 字段内容
                   - 如果输出已经是 Markdown 格式，直接使用
                   - 确保最终向用户输出的是干净的 Markdown 格式回答，不包含任何 JSON 结构

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。
                """;
    }
}

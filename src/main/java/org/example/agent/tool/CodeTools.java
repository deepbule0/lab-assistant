package org.example.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 代码助手工具
 * 提供代码生成、解释、调试功能，供 Agent 调用
 */
@Component
public class CodeTools {

    @Tool(description = "根据需求描述生成指定编程语言的代码。参数：requirement（需求描述），language（编程语言，如 Java/Python/JavaScript 等）")
    public String generateCode(String requirement, String language) {
        // 此工具由 Agent 框架调用，实际代码生成由 LLM 完成
        // 这里返回结构化提示，让 LLM 知道应该生成代码
        return String.format("""
                [代码生成任务]
                需求：%s
                语言：%s
                请生成完整、可运行的代码，包含必要的注释说明。
                """, requirement, language);
    }

    @Tool(description = "解释一段代码的逻辑和功能。参数：code（需要解释的代码片段）")
    public String explainCode(String code) {
        return String.format("""
                [代码解释任务]
                代码：
                %s
                请详细解释这段代码的功能、逻辑流程和关键点。
                """, code);
    }

    @Tool(description = "调试代码并给出修复建议。参数：code（有问题的代码），errorMessage（错误信息或问题描述）")
    public String debugCode(String code, String errorMessage) {
        return String.format("""
                [代码调试任务]
                代码：
                %s
                错误信息：%s
                请分析错误原因并给出修复后的代码。
                """, code, errorMessage);
    }
}

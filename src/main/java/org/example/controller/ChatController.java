package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.service.ChatService;
import org.example.service.LabAgentService;
import org.example.service.MemoryService;
import org.example.service.SharedSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired private ChatService chatService;
    @Autowired private LabAgentService labAgentService;
    @Autowired private MemoryService memoryService;
    @Autowired private SharedSessionService sharedSessionService;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    // ==================== 普通流式对话 ====================

    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);
        if (isBlank(request.getQuestion())) {
            sendAndComplete(emitter, SseMessage.error("问题不能为空"));
            return emitter;
        }
        executor.execute(() -> {
            try {
                SessionInfo session = getOrCreateSession(request.getId());
                checkAndSummarize(session);

                DashScopeApi api = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(api);
                String systemPrompt = chatService.buildSystemPrompt(session.getHistory(), session.getMemorySummary());
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);

                StringBuilder fullAnswer = new StringBuilder();
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());

                stream.subscribe(
                    output -> {
                        if (output instanceof StreamingOutput so && so.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
                            String chunk = so.message().getText();
                            if (chunk != null && !chunk.isEmpty()) {
                                fullAnswer.append(chunk);
                                trySend(emitter, SseMessage.content(chunk));
                            }
                        }
                    },
                    error -> {
                        trySend(emitter, SseMessage.error(error.getMessage()));
                        emitter.completeWithError(error);
                    },
                    () -> {
                        session.addMessage(request.getQuestion(), fullAnswer.toString());
                        trySend(emitter, SseMessage.sessionInfo(session.getMessagePairCount(), session.getMemorySummary() != null && !session.getMemorySummary().isBlank()));
                        trySend(emitter, SseMessage.done());
                        emitter.complete();
                    }
                );
            } catch (Exception e) {
                logger.error("chat_stream 失败", e);
                trySend(emitter, SseMessage.error(e.getMessage()));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // ==================== 多 Agent 流式对话 ====================

    @PostMapping(value = "/agent_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter agentStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(600000L);
        if (isBlank(request.getQuestion())) {
            sendAndComplete(emitter, SseMessage.error("问题不能为空"));
            return emitter;
        }
        executor.execute(() -> {
            try {
                SessionInfo session = getOrCreateSession(request.getId());
                checkAndSummarize(session);

                DashScopeApi api = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = DashScopeChatModel.builder()
                        .dashScopeApi(api)
                        .defaultOptions(DashScopeChatOptions.builder()
                                .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                                .withTemperature(0.5)
                                .withMaxToken(4000)
                                .withTopP(0.9)
                                .build())
                        .build();

                trySend(emitter, SseMessage.content("正在启动多 Agent 分析...\n\n"));

                Optional<OverAllState> stateOpt = labAgentService.executeLabAgent(chatModel, request.getQuestion());
                if (stateOpt.isEmpty()) {
                    trySend(emitter, SseMessage.error("多 Agent 未返回结果"));
                    emitter.complete();
                    return;
                }

                Optional<String> answerOpt = labAgentService.extractFinalAnswer(stateOpt.get());
                String answer = answerOpt.orElse("（未能提取最终回答）");

                // 分块发送
                int chunkSize = 50;
                for (int i = 0; i < answer.length(); i += chunkSize) {
                    trySend(emitter, SseMessage.content(answer.substring(i, Math.min(i + chunkSize, answer.length()))));
                }

                session.addMessage(request.getQuestion(), answer);
                trySend(emitter, SseMessage.sessionInfo(session.getMessagePairCount(), session.getMemorySummary() != null && !session.getMemorySummary().isBlank()));
                trySend(emitter, SseMessage.done());
                emitter.complete();
            } catch (Exception e) {
                logger.error("agent_stream 失败", e);
                trySend(emitter, SseMessage.error(e.getMessage()));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // ==================== 会话管理 ====================

    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearHistory(@RequestBody ClearRequest request) {
        SessionInfo session = sessions.get(request.getId());
        if (session != null) {
            session.clearHistory();
            return ResponseEntity.ok(ApiResponse.success("已清空"));
        }
        return ResponseEntity.ok(ApiResponse.error("会话不存在"));
    }

    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        SessionInfo session = sessions.get(sessionId);
        if (session == null) return ResponseEntity.ok(ApiResponse.error("会话不存在"));
        SessionInfoResponse resp = new SessionInfoResponse();
        resp.setSessionId(sessionId);
        resp.setMessagePairCount(session.getMessagePairCount());
        resp.setCreateTime(session.createTime);
        resp.setHasMemory(session.getMemorySummary() != null && !session.getMemorySummary().isBlank());
        resp.setMemorySummary(session.getMemorySummary());
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    // ==================== 共享 Session ====================

    @PostMapping("/shared/create")
    public ResponseEntity<ApiResponse<Map<String, String>>> createSharedRoom() {
        String code = sharedSessionService.createRoom();
        return ResponseEntity.ok(ApiResponse.success(Map.of("roomCode", code)));
    }

    @PostMapping("/shared/join")
    public ResponseEntity<ApiResponse<String>> joinSharedRoom(@RequestBody SharedJoinRequest request) {
        if (!sharedSessionService.roomExists(request.getRoomCode())) {
            return ResponseEntity.ok(ApiResponse.error("房间不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success("加入成功"));
    }

    @GetMapping(value = "/shared/stream/{roomCode}", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter sharedStream(@PathVariable String roomCode, @RequestParam String userId) {
        if (!sharedSessionService.roomExists(roomCode)) {
            SseEmitter emitter = new SseEmitter();
            sendAndComplete(emitter, Map.of("type", "error", "content", "房间不存在"));
            return emitter;
        }
        return sharedSessionService.joinRoom(roomCode, userId);
    }

    @PostMapping("/shared/send")
    public ResponseEntity<ApiResponse<String>> sharedSend(@RequestBody SharedSendRequest request) {
        if (!sharedSessionService.roomExists(request.getRoomCode())) {
            return ResponseEntity.ok(ApiResponse.error("房间不存在"));
        }
        sharedSessionService.sendMessage(request.getRoomCode(), request.getUserId(), request.getQuestion());
        return ResponseEntity.ok(ApiResponse.success("已发送"));
    }

    // ==================== 辅助方法 ====================

    private SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) sessionId = UUID.randomUUID().toString();
        return sessions.computeIfAbsent(sessionId, SessionInfo::new);
    }

    private void checkAndSummarize(SessionInfo session) {
        if (session.getMessagePairCount() >= memoryService.getThreshold()) {
            String summary = memoryService.summarize(session.getHistory());
            session.setMemorySummary(summary);
            session.clearHistory();
            logger.info("Session {} 触发 Memory 自动总结", session.sessionId);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void trySend(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().name("message").data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            logger.warn("SSE 发送失败: {}", e.getMessage());
        }
    }

    private void sendAndComplete(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().name("message").data(data, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    // ==================== 内部类：SessionInfo ====================

    private static class SessionInfo {
        final String sessionId;
        final long createTime;
        private final List<Map<String, String>> messageHistory = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        private String memorySummary = "";

        SessionInfo(String sessionId) {
            this.sessionId = sessionId;
            this.createTime = System.currentTimeMillis();
        }

        void addMessage(String question, String answer) {
            lock.lock();
            try {
                messageHistory.add(Map.of("role", "user", "content", question));
                messageHistory.add(Map.of("role", "assistant", "content", answer));
            } finally {
                lock.unlock();
            }
        }

        List<Map<String, String>> getHistory() {
            lock.lock();
            try { return new ArrayList<>(messageHistory); }
            finally { lock.unlock(); }
        }

        void clearHistory() {
            lock.lock();
            try { messageHistory.clear(); }
            finally { lock.unlock(); }
        }

        int getMessagePairCount() {
            lock.lock();
            try { return messageHistory.size() / 2; }
            finally { lock.unlock(); }
        }

        String getMemorySummary() { return memorySummary; }
        void setMemorySummary(String s) { this.memorySummary = s; }
    }

    // ==================== DTO ====================

    @Getter @Setter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"}) private String Id;
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"}) private String Question;
        public String getId() { return Id; }
        public String getQuestion() { return Question; }
    }

    @Getter @Setter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"}) private String Id;
        public String getId() { return Id; }
    }

    @Getter @Setter
    public static class SharedJoinRequest {
        private String roomCode;
        private String userId;
    }

    @Getter @Setter
    public static class SharedSendRequest {
        private String roomCode;
        private String userId;
        private String question;
    }

    @Getter @Setter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
        private boolean hasMemory;
        private String memorySummary;
    }

    @Getter @Setter
    public static class SseMessage {
        private String type;
        private String data;
        private Object extra;

        public static SseMessage content(String data) {
            SseMessage m = new SseMessage(); m.type = "content"; m.data = data; return m;
        }
        public static SseMessage error(String data) {
            SseMessage m = new SseMessage(); m.type = "error"; m.data = data; return m;
        }
        public static SseMessage done() {
            SseMessage m = new SseMessage(); m.type = "done"; return m;
        }
        public static SseMessage sessionInfo(int pairCount, boolean hasMemory) {
            SseMessage m = new SseMessage();
            m.type = "session_info";
            m.extra = Map.of("pairCount", pairCount, "hasMemory", hasMemory);
            return m;
        }
    }

    @Getter @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> r = new ApiResponse<>(); r.code = 200; r.message = "success"; r.data = data; return r;
        }
        public static <T> ApiResponse<T> error(String msg) {
            ApiResponse<T> r = new ApiResponse<>(); r.code = 500; r.message = msg; return r;
        }
    }
}

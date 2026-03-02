package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 共享 Session 服务
 * 支持多用户加入同一个房间，共享对话历史，AI 回复实时广播给所有在线用户
 */
@Service
public class SharedSessionService {

    private static final Logger logger = LoggerFactory.getLogger(SharedSessionService.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private MemoryService memoryService;

    private final Map<String, SharedRoom> rooms = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 创建共享房间，返回 6 位房间码
     */
    public String createRoom() {
        String code = generateRoomCode();
        rooms.put(code, new SharedRoom(code));
        logger.info("创建共享房间: {}", code);
        return code;
    }

    /**
     * 加入房间，返回 SseEmitter 用于接收广播
     */
    public SseEmitter joinRoom(String roomCode, String userId) {
        SharedRoom room = rooms.get(roomCode);
        if (room == null) {
            throw new IllegalArgumentException("房间不存在: " + roomCode);
        }
        SseEmitter emitter = new SseEmitter(600000L);
        room.addUser(userId, emitter);
        emitter.onCompletion(() -> room.removeUser(userId));
        emitter.onTimeout(() -> room.removeUser(userId));
        logger.info("用户 {} 加入房间 {}", userId, roomCode);
        return emitter;
    }

    /**
     * 向共享房间发送消息，触发 AI 回复并广播
     */
    public void sendMessage(String roomCode, String userId, String question) {
        SharedRoom room = rooms.get(roomCode);
        if (room == null) throw new IllegalArgumentException("房间不存在: " + roomCode);

        executor.execute(() -> {
            try {
                // 广播用户消息
                room.broadcast(buildUserMsg(userId, question));

                // 检查 memory
                List<Map<String, String>> history = room.getHistory();
                String memorySummary = room.getMemorySummary();
                if (room.getMessagePairCount() >= memoryService.getThreshold()) {
                    memorySummary = memoryService.summarize(history);
                    room.setMemorySummary(memorySummary);
                    room.clearHistory();
                    history = new ArrayList<>();
                    room.broadcast(buildSystemMsg("[Memory] 对话历史已自动压缩摘要"));
                }

                DashScopeApi api = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(api);
                String systemPrompt = chatService.buildSystemPrompt(history, memorySummary);
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);

                StringBuilder fullAnswer = new StringBuilder();
                Flux<NodeOutput> stream = agent.stream(question);

                stream.subscribe(
                    output -> {
                        if (output instanceof StreamingOutput so && so.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
                            String chunk = so.message().getText();
                            if (chunk != null && !chunk.isEmpty()) {
                                fullAnswer.append(chunk);
                                room.broadcast(buildChunkMsg(chunk));
                            }
                        }
                    },
                    error -> {
                        logger.error("共享房间 AI 回复失败", error);
                        room.broadcast(buildErrorMsg(error.getMessage()));
                    },
                    () -> {
                        room.addMessage(question, fullAnswer.toString());
                        room.broadcast(buildDoneMsg());
                        logger.info("共享房间 {} AI 回复完成", roomCode);
                    }
                );
            } catch (Exception e) {
                logger.error("共享房间消息处理失败", e);
                room.broadcast(buildErrorMsg(e.getMessage()));
            }
        });
    }

    public boolean roomExists(String roomCode) {
        return rooms.containsKey(roomCode);
    }

    public int getRoomUserCount(String roomCode) {
        SharedRoom room = rooms.get(roomCode);
        return room == null ? 0 : room.getUserCount();
    }

    // ==================== 消息构建 ====================

    private Map<String, Object> buildUserMsg(String userId, String content) {
        return Map.of("type", "user_message", "userId", userId, "content", content);
    }

    private Map<String, Object> buildChunkMsg(String chunk) {
        return Map.of("type", "ai_chunk", "content", chunk);
    }

    private Map<String, Object> buildDoneMsg() {
        return Map.of("type", "done");
    }

    private Map<String, Object> buildErrorMsg(String error) {
        return Map.of("type", "error", "content", error != null ? error : "未知错误");
    }

    private Map<String, Object> buildSystemMsg(String msg) {
        return Map.of("type", "system", "content", msg);
    }

    private String generateRoomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    // ==================== 内部类 ====================

    private static class SharedRoom {
        private final String roomCode;
        private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
        private final List<Map<String, String>> messageHistory = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        private String memorySummary = "";

        SharedRoom(String roomCode) {
            this.roomCode = roomCode;
        }

        void addUser(String userId, SseEmitter emitter) {
            emitters.put(userId, emitter);
        }

        void removeUser(String userId) {
            emitters.remove(userId);
        }

        int getUserCount() {
            return emitters.size();
        }

        void broadcast(Map<String, Object> data) {
            List<String> dead = new ArrayList<>();
            for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
                try {
                    entry.getValue().send(SseEmitter.event().name("message").data(data));
                } catch (IOException e) {
                    dead.add(entry.getKey());
                }
            }
            dead.forEach(emitters::remove);
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
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
            } finally {
                lock.unlock();
            }
        }

        int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }

        String getMemorySummary() { return memorySummary; }
        void setMemorySummary(String s) { this.memorySummary = s; }
    }
}

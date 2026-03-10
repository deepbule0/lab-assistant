// Lab Assistant 前端应用
class LabApp {
    constructor() {
        this.apiBase = 'http://localhost:9901/api';
        this.sessionId = this.loadOrCreateSessionId();
        this.currentMode = 'chat'; // chat | agent | code
        this.isStreaming = false;
        this.currentChatHistory = [];
        this.chatHistories = this.loadChatHistories();
        this.messagePairCount = 0;
        this.hasMemory = false;

        // 共享房间状态
        this.roomCode = null;
        this.userId = null;
        this.roomEventSource = null;

        this.initElements();
        this.bindEvents();
        this.initMarkdown();
        this.renderChatHistoryList();
        this.updateMemoryBar();
    }

    // ==================== 初始化 ====================

    initElements() {
        this.messageInput = document.getElementById('messageInput');
        this.sendButton = document.getElementById('sendButton');
        this.chatMessages = document.getElementById('chatMessages');
        this.welcomeGreeting = document.getElementById('welcomeGreeting');
        this.chatHistoryList = document.getElementById('chatHistoryList');
        this.memoryBar = document.getElementById('memoryBar');
        this.memoryBarText = document.getElementById('memoryBarText');
        this.roomBar = document.getElementById('roomBar');
        this.roomBarText = document.getElementById('roomBarText');
        this.toolsMenu = document.getElementById('toolsMenu');
    }

    bindEvents() {
        this.sendButton.addEventListener('click', () => this.sendMessage());
        this.messageInput.addEventListener('keydown', e => {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this.sendMessage(); }
        });
        this.messageInput.addEventListener('input', () => this.autoResizeTextarea());

        document.getElementById('newChatBtn').addEventListener('click', () => this.newChat());
        document.getElementById('clearHistoriesBtn').addEventListener('click', () => this.clearAllHistories());
        document.getElementById('toolsBtn').addEventListener('click', e => {
            e.stopPropagation();
            this.toolsMenu.classList.toggle('open');
        });
        document.addEventListener('click', () => this.toolsMenu.classList.remove('open'));

        document.getElementById('uploadFileItem').addEventListener('click', () => {
            document.getElementById('fileInput').click();
            this.toolsMenu.classList.remove('open');
        });
        document.getElementById('fileInput').addEventListener('change', e => this.uploadFile(e));
        document.getElementById('clearHistoryItem').addEventListener('click', () => this.clearHistory());

        // 模式切换
        document.querySelectorAll('.mode-tab').forEach(tab => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('.mode-tab').forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                this.currentMode = tab.dataset.mode;
                this.updatePlaceholder();
            });
        });

        // 共享房间
        document.getElementById('createRoomBtn').addEventListener('click', () => this.createRoom());
        document.getElementById('joinRoomBtn').addEventListener('click', () => {
            document.getElementById('joinRoomModal').style.display = 'flex';
        });
        document.getElementById('closeCreateModal').addEventListener('click', () => {
            document.getElementById('createRoomModal').style.display = 'none';
        });
        document.getElementById('closeJoinModal').addEventListener('click', () => {
            document.getElementById('joinRoomModal').style.display = 'none';
        });
        document.getElementById('confirmJoinBtn').addEventListener('click', () => this.joinRoom());
        document.getElementById('copyRoomCode').addEventListener('click', () => {
            const code = document.getElementById('roomCodeDisplay').textContent;
            navigator.clipboard.writeText(code).then(() => this.showToast('房间码已复制'));
        });
        document.getElementById('leaveRoomBtn').addEventListener('click', () => this.leaveRoom());
    }

    initMarkdown() {
        if (typeof marked !== 'undefined') {
            marked.setOptions({ breaks: true, gfm: true, headerIds: false, mangle: false });
        }
    }

    updatePlaceholder() {
        const placeholders = {
            chat: '输入问题，支持 RAG 知识检索...',
            agent: '输入复杂问题，多 Agent 深度分析...',
            code: '描述代码需求，或粘贴代码请求解释/调试...'
        };
        this.messageInput.placeholder = placeholders[this.currentMode] || '输入问题...';
    }

    autoResizeTextarea() {
        const ta = this.messageInput;
        ta.style.height = 'auto';
        ta.style.height = Math.min(ta.scrollHeight, 160) + 'px';
    }

    // ==================== 发送消息 ====================

    async sendMessage() {
        const question = this.messageInput.value.trim();
        if (!question || this.isStreaming) return;

        // 共享房间模式
        if (this.roomCode) {
            this.sendSharedMessage(question);
            return;
        }

        this.messageInput.value = '';
        this.messageInput.style.height = 'auto';
        this.isStreaming = true;
        this.sendButton.disabled = true;
        this.welcomeGreeting.style.display = 'none';

        this.appendMessage('user', question);
        const aiMsgEl = this.appendMessage('assistant', '', true);

        const endpoint = this.currentMode === 'agent' ? '/agent_stream' : '/chat_stream';

        try {
            const response = await fetch(this.apiBase + endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ Id: this.sessionId, Question: question })
            });

            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let fullContent = '';

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop();

                for (const line of lines) {
                    if (!line.startsWith('data:')) continue;
                    try {
                        const msg = JSON.parse(line.slice(5).trim());
                        if (msg.type === 'content') {
                            fullContent += msg.data;
                            this.updateStreamingMessage(aiMsgEl, fullContent);
                        } else if (msg.type === 'session_info') {
                            this.messagePairCount = msg.extra?.pairCount || 0;
                            this.hasMemory = msg.extra?.hasMemory || false;
                            this.updateMemoryBar();
                        } else if (msg.type === 'done') {
                            this.finalizeMessage(aiMsgEl, fullContent);
                            this.saveToHistory(question, fullContent);
                        } else if (msg.type === 'error') {
                            this.finalizeMessage(aiMsgEl, '错误: ' + msg.data);
                        }
                    } catch (_) {}
                }
            }
        } catch (e) {
            this.finalizeMessage(aiMsgEl, '请求失败: ' + e.message);
        } finally {
            this.isStreaming = false;
            this.sendButton.disabled = false;
        }
    }

    // ==================== 共享房间消息 ====================

    async sendSharedMessage(question) {
        this.messageInput.value = '';
        this.messageInput.style.height = 'auto';
        try {
            await fetch(this.apiBase + '/shared/send', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ roomCode: this.roomCode, userId: this.userId, question })
            });
        } catch (e) {
            this.appendSystemMessage('发送失败: ' + e.message);
        }
    }

    // ==================== 消息渲染 ====================

    appendMessage(role, content, streaming = false) {
        this.welcomeGreeting.style.display = 'none';
        const div = document.createElement('div');
        div.className = `message ${role}`;

        const avatar = document.createElement('div');
        avatar.className = 'message-avatar';
        avatar.textContent = role === 'user' ? '你' : 'AI';

        const bubble = document.createElement('div');
        bubble.className = 'message-bubble' + (streaming ? ' streaming-cursor' : '');

        if (content) {
            bubble.innerHTML = this.renderMarkdown(content);
            this.addCopyButtons(bubble);
        }

        div.appendChild(avatar);
        div.appendChild(bubble);
        this.chatMessages.appendChild(div);
        this.scrollToBottom();
        return bubble;
    }

    appendUserLabelMessage(userId, content) {
        this.welcomeGreeting.style.display = 'none';
        const div = document.createElement('div');
        div.className = 'message user';
        const avatar = document.createElement('div');
        avatar.className = 'message-avatar';
        avatar.textContent = userId.charAt(0).toUpperCase();
        const wrap = document.createElement('div');
        const label = document.createElement('div');
        label.className = 'user-label';
        label.textContent = userId;
        const bubble = document.createElement('div');
        bubble.className = 'message-bubble';
        bubble.textContent = content;
        wrap.appendChild(label);
        wrap.appendChild(bubble);
        div.appendChild(avatar);
        div.appendChild(wrap);
        this.chatMessages.appendChild(div);
        this.scrollToBottom();
    }

    appendSystemMessage(content) {
        const div = document.createElement('div');
        div.className = 'message system-msg';
        const avatar = document.createElement('div');
        avatar.className = 'message-avatar';
        avatar.textContent = '⚙';
        const bubble = document.createElement('div');
        bubble.className = 'message-bubble';
        bubble.textContent = content;
        div.appendChild(avatar);
        div.appendChild(bubble);
        this.chatMessages.appendChild(div);
        this.scrollToBottom();
    }

    updateStreamingMessage(bubble, content) {
        bubble.innerHTML = this.renderMarkdown(content);
        this.addCopyButtons(bubble);
        this.scrollToBottom();
    }

    finalizeMessage(bubble, content) {
        bubble.classList.remove('streaming-cursor');
        bubble.innerHTML = this.renderMarkdown(content);
        this.addCopyButtons(bubble);
        this.scrollToBottom();
    }

    renderMarkdown(text) {
        if (typeof marked === 'undefined') return this.escapeHtml(text);
        try {
            let html = marked.parse(text);
            // 包装代码块
            html = html.replace(/<pre><code class="language-(\w+)">([\s\S]*?)<\/code><\/pre>/g, (_, lang, code) => {
                return `<div class="code-block-wrapper">
                    <div class="code-block-header"><span>${lang}</span><button class="copy-code-btn" onclick="copyCode(this)">复制</button></div>
                    <pre><code class="language-${lang}">${code}</code></pre>
                </div>`;
            });
            html = html.replace(/<pre><code>([\s\S]*?)<\/code><\/pre>/g, (_, code) => {
                return `<div class="code-block-wrapper">
                    <div class="code-block-header"><span>code</span><button class="copy-code-btn" onclick="copyCode(this)">复制</button></div>
                    <pre><code>${code}</code></pre>
                </div>`;
            });
            return html;
        } catch (_) { return this.escapeHtml(text); }
    }

    addCopyButtons(bubble) {
        if (typeof hljs !== 'undefined') {
            bubble.querySelectorAll('pre code').forEach(block => hljs.highlightElement(block));
        }
    }

    escapeHtml(text) {
        return text.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    scrollToBottom() {
        this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
    }

    // ==================== Memory ====================

    updateMemoryBar() {
        this.memoryBar.style.display = 'flex';
        let text = `对话轮数: ${this.messagePairCount}`;
        if (this.hasMemory) text += ' · 已启用 Memory 摘要';
        this.memoryBarText.textContent = text;
    }

    // ==================== 共享房间 ====================

    async createRoom() {
        try {
            const res = await fetch(this.apiBase + '/shared/create', { method: 'POST' });
            const data = await res.json();
            const code = data.data?.roomCode;
            if (code) {
                document.getElementById('roomCodeDisplay').textContent = code;
                document.getElementById('createRoomModal').style.display = 'flex';
            }
        } catch (e) {
            this.showToast('创建失败: ' + e.message);
        }
    }

    async joinRoom() {
        const code = document.getElementById('roomCodeInput').value.trim().toUpperCase();
        const uid = document.getElementById('userIdInput').value.trim() || ('用户' + Math.floor(Math.random() * 1000));
        if (!code) { this.showToast('请输入房间码'); return; }

        try {
            const res = await fetch(this.apiBase + '/shared/join', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ roomCode: code, userId: uid })
            });
            const data = await res.json();
            if (data.code !== 200) { this.showToast(data.message); return; }

            this.roomCode = code;
            this.userId = uid;
            document.getElementById('joinRoomModal').style.display = 'none';
            this.connectRoomSSE();
            this.showRoomBar();
            this.appendSystemMessage(`已加入房间 ${code}，昵称：${uid}`);
        } catch (e) {
            this.showToast('加入失败: ' + e.message);
        }
    }

    connectRoomSSE() {
        if (this.roomEventSource) this.roomEventSource.close();
        const url = `${this.apiBase}/shared/stream/${this.roomCode}?userId=${encodeURIComponent(this.userId)}`;
        this.roomEventSource = new EventSource(url);
        let aiMsgEl = null;
        let fullContent = '';

        this.roomEventSource.addEventListener('message', e => {
            try {
                const msg = JSON.parse(e.data);
                if (msg.type === 'user_message') {
                    if (msg.userId !== this.userId) {
                        this.appendUserLabelMessage(msg.userId, msg.content);
                    }
                    aiMsgEl = this.appendMessage('assistant', '', true);
                    fullContent = '';
                } else if (msg.type === 'ai_chunk') {
                    fullContent += msg.content;
                    if (aiMsgEl) this.updateStreamingMessage(aiMsgEl, fullContent);
                } else if (msg.type === 'done') {
                    if (aiMsgEl) this.finalizeMessage(aiMsgEl, fullContent);
                    aiMsgEl = null;
                } else if (msg.type === 'system') {
                    this.appendSystemMessage(msg.content);
                } else if (msg.type === 'error') {
                    if (aiMsgEl) this.finalizeMessage(aiMsgEl, '错误: ' + msg.content);
                    aiMsgEl = null;
                }
            } catch (_) {}
        });

        this.roomEventSource.onerror = () => {
            this.appendSystemMessage('与房间的连接已断开');
        };
    }

    leaveRoom() {
        if (this.roomEventSource) { this.roomEventSource.close(); this.roomEventSource = null; }
        this.roomCode = null;
        this.userId = null;
        this.roomBar.style.display = 'none';
        this.appendSystemMessage('已离开共享房间');
    }

    showRoomBar() {
        this.roomBar.style.display = 'flex';
        this.roomBarText.textContent = `共享房间: ${this.roomCode} (${this.userId})`;
    }

    // ==================== 文件上传 ====================

    async uploadFile(e) {
        const file = e.target.files[0];
        if (!file) return;
        const formData = new FormData();
        formData.append('file', file);
        this.appendSystemMessage(`正在上传 ${file.name}...`);
        try {
            const res = await fetch(this.apiBase.replace('/api', '') + '/api/upload', { method: 'POST', body: formData });
            const data = await res.json();
            if (data.code === 200) {
                this.appendSystemMessage(`文件 ${file.name} 上传成功，已加入知识库`);
            } else {
                this.appendSystemMessage(`上传失败: ${data.message}`);
            }
        } catch (err) {
            this.appendSystemMessage(`上传失败: ${err.message}`);
        }
        e.target.value = '';
    }

    // ==================== 历史管理 ====================

    async clearHistory() {
        try {
            await fetch(this.apiBase + '/chat/clear', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ Id: this.sessionId })
            });
            this.messagePairCount = 0;
            this.hasMemory = false;
            this.updateMemoryBar();
            this.appendSystemMessage('对话历史已清空');
        } catch (e) {
            this.showToast('清空失败');
        }
        this.toolsMenu.classList.remove('open');
    }

    newChat() {
        // 保存当前对话到历史记录
        if (this.currentChatHistory.length > 0) {
            this.saveChatToHistories();
        }

        // 创建新的会话
        this.sessionId = this.generateSessionId();
        this.saveSessionId();
        this.currentChatHistory = [];
        this.messagePairCount = 0;
        this.hasMemory = false;

        // 更新 UI
        this.chatMessages.innerHTML = '';
        this.welcomeGreeting.style.display = 'block';
        this.updateMemoryBar();
        this.renderChatHistoryList();
    }

    saveToHistory(question, answer) {
        this.currentChatHistory.push({ role: 'user', content: question });
        this.currentChatHistory.push({ role: 'assistant', content: answer });
    }

    saveChatToHistories() {
        if (this.currentChatHistory.length === 0) return;
        const firstMsg = this.currentChatHistory.find(m => m.role === 'user');
        const title = firstMsg ? firstMsg.content.slice(0, 30) : '对话';
        const entry = { id: this.sessionId, title, messages: [...this.currentChatHistory], time: Date.now() };

        // 若该 sessionId 已存在则更新，否则插入头部
        const idx = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (idx !== -1) {
            this.chatHistories[idx] = entry;
        } else {
            this.chatHistories.unshift(entry);
            if (this.chatHistories.length > 20) this.chatHistories.pop();
        }

        localStorage.setItem('lab_histories', JSON.stringify(this.chatHistories));
        this.renderChatHistoryList();
    }

    loadChatHistories() {
        try { return JSON.parse(localStorage.getItem('lab_histories') || '[]'); } catch (_) { return []; }
    }

    clearAllHistories() {
        if (!confirm('确认清除所有历史记录？此操作不可恢复。')) return;
        this.chatHistories = [];
        localStorage.removeItem('lab_histories');
        this.renderChatHistoryList();
        this.showToast('历史记录已清除');
    }

    renderChatHistoryList() {
        this.chatHistoryList.innerHTML = '';
        this.chatHistories.forEach(h => {
            const item = document.createElement('div');
            item.className = 'history-item' + (h.id === this.sessionId ? ' active' : '');
            item.textContent = h.title;
            item.addEventListener('click', () => this.loadHistory(h));
            this.chatHistoryList.appendChild(item);
        });
    }

    loadHistory(h) {
        this.sessionId = h.id;
        this.saveSessionId();
        this.currentChatHistory = [...h.messages];
        this.chatMessages.innerHTML = '';
        this.welcomeGreeting.style.display = 'none';
        h.messages.forEach(m => this.appendMessage(m.role, m.content));
        this.renderChatHistoryList();
    }

    loadOrCreateSessionId() {
        return localStorage.getItem('lab_session') || this.generateSessionId();
    }

    saveSessionId() {
        localStorage.setItem('lab_session', this.sessionId);
    }

    generateSessionId() {
        const id = 'sess_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
        localStorage.setItem('lab_session', id);
        return id;
    }

    // ==================== 工具 ====================

    showToast(msg) {
        const toast = document.createElement('div');
        toast.style.cssText = 'position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:#1e1e2e;color:#cdd6f4;padding:10px 20px;border-radius:8px;font-size:13px;z-index:9999;box-shadow:0 4px 12px rgba(0,0,0,0.2)';
        toast.textContent = msg;
        document.body.appendChild(toast);
        setTimeout(() => toast.remove(), 2500);
    }
}

// 全局复制代码函数
function copyCode(btn) {
    const code = btn.closest('.code-block-wrapper').querySelector('code').textContent;
    navigator.clipboard.writeText(code).then(() => {
        btn.textContent = '已复制';
        setTimeout(() => btn.textContent = '复制', 2000);
    });
}

// 启动应用
window.addEventListener('DOMContentLoaded', () => {
    window.app = new LabApp();
});

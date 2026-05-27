package dev.vatn.examples.chatapp;

import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VWsListener;
import dev.vatn.api.VWsSession;

import java.nio.charset.StandardCharsets;

/**
 * Full-stack chat application plugin.
 *
 * - GET  /chat          → serves the HTML/CSS/JS page
 * - GET  /chat/users    → returns the online user list as JSON (REST fallback)
 * - WS   /ws/chat       → WebSocket endpoint (registered in Main.java)
 */
public class ChatAppPlugin implements VNodePlugin {

    private ChatRoom room;

    @Override public String getId()      { return "dev.vatn.examples.chat-app"; }
    @Override public String getName()    { return "VATN Chat App"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        room = new ChatRoom(ctx.getMessaging());
        ctx.register("/chat", new ChatHttpService());
    }

    /** Called from Main.java to get the WebSocket listener after initialization. */
    public VWsListener wsListener() {
        return new VWsListener() {
            @Override
            public void onOpen(VWsSession session) {
                room.onConnect(session);
            }

            @Override
            public void onMessage(VWsSession session, String text, boolean last) {
                room.onMessage(session, text);
            }

            @Override
            public void onClose(VWsSession session, int statusCode, String reason) {
                room.onClose(session);
            }

            @Override
            public void onError(VWsSession session, Throwable t) {
                room.onClose(session);
            }
        };
    }

    // ── HTTP service: serves the chat page ────────────────────────────────────

    private static class ChatHttpService implements VHttpService {
        @Override
        public void routing(VHttpRoutes routes) {
            routes.get("/", (req, res) -> {
                res.setHeader("Content-Type", "text/html; charset=utf-8");
                res.send(HTML.getBytes(StandardCharsets.UTF_8));
            });
        }
    }

    // ── Embedded HTML/CSS/JS ──────────────────────────────────────────────────

    private static final String HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>VATN Chat</title>
            <style>
              *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

              body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: #0d1117;
                color: #c9d1d9;
                height: 100vh;
                display: flex;
                flex-direction: column;
              }

              /* ── header ── */
              header {
                background: #161b22;
                border-bottom: 1px solid #30363d;
                padding: 0.75rem 1.5rem;
                display: flex;
                align-items: center;
                gap: 0.75rem;
              }
              header .logo { font-size: 1.4rem; }
              header h1 { font-size: 1.1rem; font-weight: 600; color: #58a6ff; }
              header .status {
                margin-left: auto;
                font-size: 0.75rem;
                display: flex;
                align-items: center;
                gap: 0.4rem;
                color: #8b949e;
              }
              .dot {
                width: 8px; height: 8px;
                border-radius: 50%;
                background: #3fb950;
              }
              .dot.offline { background: #6e7681; }

              /* ── layout ── */
              .layout {
                flex: 1;
                display: flex;
                overflow: hidden;
              }

              /* ── sidebar ── */
              aside {
                width: 220px;
                background: #161b22;
                border-right: 1px solid #30363d;
                display: flex;
                flex-direction: column;
                padding: 1rem;
                gap: 0.5rem;
                overflow-y: auto;
              }
              aside h2 {
                font-size: 0.7rem;
                text-transform: uppercase;
                letter-spacing: 0.08em;
                color: #8b949e;
                margin-bottom: 0.25rem;
              }
              .user-item {
                display: flex;
                align-items: center;
                gap: 0.5rem;
                font-size: 0.875rem;
                padding: 0.3rem 0.5rem;
                border-radius: 6px;
              }
              .user-item.me { color: #58a6ff; font-weight: 600; }
              .avatar {
                width: 28px; height: 28px;
                border-radius: 50%;
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 0.75rem;
                font-weight: 700;
                color: #fff;
                flex-shrink: 0;
              }

              /* ── chat area ── */
              main {
                flex: 1;
                display: flex;
                flex-direction: column;
                overflow: hidden;
              }

              #messages {
                flex: 1;
                overflow-y: auto;
                padding: 1rem 1.5rem;
                display: flex;
                flex-direction: column;
                gap: 0.5rem;
                scroll-behavior: smooth;
              }

              .msg {
                display: flex;
                gap: 0.75rem;
                max-width: 720px;
              }
              .msg .avatar { flex-shrink: 0; margin-top: 2px; }
              .msg-body {}
              .msg-meta {
                font-size: 0.7rem;
                color: #8b949e;
                margin-bottom: 0.15rem;
              }
              .msg-meta .name { font-weight: 600; color: #c9d1d9; margin-right: 0.4rem; }
              .msg-meta .name.me { color: #58a6ff; }
              .msg-text {
                font-size: 0.9rem;
                line-height: 1.5;
                color: #c9d1d9;
                word-break: break-word;
              }

              .msg.system {
                justify-content: center;
              }
              .msg.system .sys-text {
                font-size: 0.75rem;
                color: #8b949e;
                background: #161b22;
                padding: 0.25rem 0.75rem;
                border-radius: 12px;
              }

              /* ── typing indicator ── */
              #typing-bar {
                min-height: 1.5rem;
                padding: 0 1.5rem;
                font-size: 0.75rem;
                color: #8b949e;
                font-style: italic;
              }

              /* ── input area ── */
              .input-area {
                padding: 0.75rem 1.5rem 1rem;
                background: #0d1117;
                border-top: 1px solid #30363d;
                display: flex;
                gap: 0.75rem;
                align-items: flex-end;
              }
              .input-area input {
                flex: 1;
                background: #161b22;
                border: 1px solid #30363d;
                border-radius: 8px;
                padding: 0.65rem 1rem;
                color: #c9d1d9;
                font-size: 0.9rem;
                outline: none;
                transition: border-color 0.15s;
              }
              .input-area input:focus { border-color: #58a6ff; }
              .input-area input:disabled { opacity: 0.4; cursor: not-allowed; }
              .send-btn {
                background: #238636;
                color: #fff;
                border: none;
                border-radius: 8px;
                padding: 0.65rem 1.2rem;
                font-size: 0.9rem;
                font-weight: 600;
                cursor: pointer;
                transition: background 0.15s;
                white-space: nowrap;
              }
              .send-btn:hover { background: #2ea043; }
              .send-btn:disabled { opacity: 0.4; cursor: not-allowed; }

              /* ── join modal ── */
              #join-overlay {
                position: fixed; inset: 0;
                background: rgba(1,4,9,0.85);
                display: flex;
                align-items: center;
                justify-content: center;
                z-index: 100;
              }
              .join-box {
                background: #161b22;
                border: 1px solid #30363d;
                border-radius: 12px;
                padding: 2rem;
                width: 340px;
                display: flex;
                flex-direction: column;
                gap: 1rem;
              }
              .join-box h2 { color: #58a6ff; font-size: 1.2rem; }
              .join-box p { color: #8b949e; font-size: 0.85rem; }
              .join-box input {
                background: #0d1117;
                border: 1px solid #30363d;
                border-radius: 8px;
                padding: 0.65rem 1rem;
                color: #c9d1d9;
                font-size: 0.9rem;
                outline: none;
              }
              .join-box input:focus { border-color: #58a6ff; }
              .join-btn {
                background: #238636;
                color: #fff;
                border: none;
                border-radius: 8px;
                padding: 0.65rem;
                font-size: 0.95rem;
                font-weight: 600;
                cursor: pointer;
              }
              .join-btn:hover { background: #2ea043; }
              .join-error { color: #f85149; font-size: 0.8rem; display: none; }
            </style>
            </head>
            <body>

            <!-- Join overlay -->
            <div id="join-overlay">
              <div class="join-box">
                <h2>⚡ VATN Chat</h2>
                <p>Pick a username to join the room.</p>
                <input id="username-input" type="text" placeholder="Your username" maxlength="32" autofocus>
                <span id="join-error" class="join-error"></span>
                <button class="join-btn" id="join-btn">Join Chat</button>
              </div>
            </div>

            <!-- App shell -->
            <header>
              <span class="logo">⚡</span>
              <h1>VATN Chat</h1>
              <div class="status">
                <span class="dot offline" id="conn-dot"></span>
                <span id="conn-label">Connecting…</span>
              </div>
            </header>

            <div class="layout">
              <aside>
                <h2>Online</h2>
                <div id="user-list"></div>
              </aside>
              <main>
                <div id="messages"></div>
                <div id="typing-bar"></div>
                <div class="input-area">
                  <input id="msg-input" type="text" placeholder="Type a message…" maxlength="2000" disabled>
                  <button class="send-btn" id="send-btn" disabled>Send</button>
                </div>
              </main>
            </div>

            <script>
            (() => {
              // ── state ─────────────────────────────────────────────────────────
              let ws = null;
              let myUsername = null;
              const typingTimers = {};
              const avatarColors = [
                '#58a6ff','#3fb950','#f0883e','#d2a8ff',
                '#ffa198','#79c0ff','#56d364','#e3b341'
              ];

              // ── DOM refs ──────────────────────────────────────────────────────
              const joinOverlay  = document.getElementById('join-overlay');
              const usernameInput= document.getElementById('username-input');
              const joinBtn      = document.getElementById('join-btn');
              const joinError    = document.getElementById('join-error');
              const connDot      = document.getElementById('conn-dot');
              const connLabel    = document.getElementById('conn-label');
              const messages     = document.getElementById('messages');
              const userList     = document.getElementById('user-list');
              const msgInput     = document.getElementById('msg-input');
              const sendBtn      = document.getElementById('send-btn');
              const typingBar    = document.getElementById('typing-bar');

              // ── utilities ─────────────────────────────────────────────────────
              function avatarColor(name) {
                let h = 0;
                for (let i = 0; i < name.length; i++) h = name.charCodeAt(i) + ((h << 5) - h);
                return avatarColors[Math.abs(h) % avatarColors.length];
              }

              function avatarEl(name) {
                const el = document.createElement('div');
                el.className = 'avatar';
                el.style.background = avatarColor(name);
                el.textContent = name[0].toUpperCase();
                return el;
              }

              function formatTime(ts) {
                const d = new Date(ts);
                return d.getHours().toString().padStart(2,'0') + ':'
                     + d.getMinutes().toString().padStart(2,'0');
              }

              function scrollToBottom() {
                messages.scrollTop = messages.scrollHeight;
              }

              // ── render helpers ────────────────────────────────────────────────
              function appendMessage(username, text, ts) {
                const div = document.createElement('div');
                div.className = 'msg';

                div.appendChild(avatarEl(username));

                const body = document.createElement('div');
                body.className = 'msg-body';

                const meta = document.createElement('div');
                meta.className = 'msg-meta';
                const nameSpan = document.createElement('span');
                nameSpan.className = 'name' + (username === myUsername ? ' me' : '');
                nameSpan.textContent = username === myUsername ? username + ' (you)' : username;
                const timeSpan = document.createElement('span');
                timeSpan.textContent = formatTime(ts);
                meta.appendChild(nameSpan);
                meta.appendChild(timeSpan);

                const textDiv = document.createElement('div');
                textDiv.className = 'msg-text';
                textDiv.textContent = text;

                body.appendChild(meta);
                body.appendChild(textDiv);
                div.appendChild(body);
                messages.appendChild(div);
                scrollToBottom();
              }

              function appendSystem(text) {
                const div = document.createElement('div');
                div.className = 'msg system';
                const span = document.createElement('span');
                span.className = 'sys-text';
                span.textContent = text;
                div.appendChild(span);
                messages.appendChild(div);
                scrollToBottom();
              }

              function renderUserList(users) {
                userList.innerHTML = '';
                [...users].sort().forEach(u => {
                  const item = document.createElement('div');
                  item.className = 'user-item' + (u === myUsername ? ' me' : '');
                  item.appendChild(avatarEl(u));
                  const name = document.createElement('span');
                  name.textContent = u === myUsername ? u + ' (you)' : u;
                  item.appendChild(name);
                  userList.appendChild(item);
                });
              }

              // ── typing indicator ──────────────────────────────────────────────
              const currentlyTyping = new Set();

              function updateTypingBar() {
                const names = [...currentlyTyping];
                if (names.length === 0)      typingBar.textContent = '';
                else if (names.length === 1) typingBar.textContent = names[0] + ' is typing…';
                else if (names.length === 2) typingBar.textContent = names.join(' and ') + ' are typing…';
                else                         typingBar.textContent = 'Several people are typing…';
              }

              function setTyping(username, isTyping) {
                if (username === myUsername) return;
                if (isTyping) {
                  currentlyTyping.add(username);
                  // Auto-clear after 4s in case we miss the false event
                  clearTimeout(typingTimers[username]);
                  typingTimers[username] = setTimeout(() => {
                    currentlyTyping.delete(username);
                    updateTypingBar();
                  }, 4000);
                } else {
                  currentlyTyping.delete(username);
                  clearTimeout(typingTimers[username]);
                }
                updateTypingBar();
              }

              // ── WebSocket ─────────────────────────────────────────────────────
              function connect() {
                const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                ws = new WebSocket(proto + '//' + location.host + '/ws/chat');

                ws.onopen = () => {
                  connDot.classList.remove('offline');
                  connLabel.textContent = 'Connected';
                };

                ws.onclose = () => {
                  connDot.classList.add('offline');
                  connLabel.textContent = 'Disconnected — reload to reconnect';
                  msgInput.disabled = true;
                  sendBtn.disabled = true;
                };

                ws.onerror = () => {
                  connLabel.textContent = 'Connection error';
                };

                ws.onmessage = (e) => {
                  let data;
                  try { data = JSON.parse(e.data); } catch { return; }

                  switch (data.type) {
                    case 'welcome':
                      myUsername = data.username;
                      joinOverlay.style.display = 'none';
                      msgInput.disabled = false;
                      sendBtn.disabled = false;
                      msgInput.focus();
                      renderUserList(data.users);
                      // Replay history
                      (data.history || []).forEach(m => appendMessage(m.username, m.text, m.ts));
                      appendSystem('Welcome, ' + myUsername + '! 👋');
                      break;

                    case 'error':
                      joinError.textContent = data.message || 'Error';
                      joinError.style.display = 'block';
                      break;

                    case 'user_joined':
                      renderUserList(data.users);
                      if (data.username !== myUsername)
                        appendSystem(data.username + ' joined the room');
                      break;

                    case 'user_left':
                      renderUserList(data.users);
                      appendSystem(data.username + ' left the room');
                      setTyping(data.username, false);
                      break;

                    case 'message':
                      appendMessage(data.username, data.text, data.ts);
                      break;

                    case 'typing':
                      setTyping(data.username, data.isTyping);
                      break;
                  }
                };
              }

              // ── send message ──────────────────────────────────────────────────
              let typingSent = false;

              function sendMessage() {
                const text = msgInput.value.trim();
                if (!text || !ws || ws.readyState !== WebSocket.OPEN) return;
                ws.send(JSON.stringify({ type: 'message', text }));
                msgInput.value = '';
                // Stop typing indicator
                if (typingSent) {
                  ws.send(JSON.stringify({ type: 'typing', isTyping: false }));
                  typingSent = false;
                }
              }

              sendBtn.addEventListener('click', sendMessage);
              msgInput.addEventListener('keydown', e => {
                if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
              });

              // ── typing events ─────────────────────────────────────────────────
              let typingTimeout = null;
              msgInput.addEventListener('input', () => {
                if (!ws || ws.readyState !== WebSocket.OPEN) return;
                if (!typingSent) {
                  ws.send(JSON.stringify({ type: 'typing', isTyping: true }));
                  typingSent = true;
                }
                clearTimeout(typingTimeout);
                typingTimeout = setTimeout(() => {
                  if (typingSent) {
                    ws.send(JSON.stringify({ type: 'typing', isTyping: false }));
                    typingSent = false;
                  }
                }, 2000);
              });

              // ── join form ─────────────────────────────────────────────────────
              function doJoin() {
                const name = usernameInput.value.trim();
                if (!name) { joinError.textContent = 'Please enter a username'; joinError.style.display = 'block'; return; }
                joinError.style.display = 'none';
                if (!ws || ws.readyState !== WebSocket.OPEN) { joinError.textContent = 'Not connected yet, please wait'; joinError.style.display = 'block'; return; }
                ws.send(JSON.stringify({ type: 'join', username: name }));
              }

              joinBtn.addEventListener('click', doJoin);
              usernameInput.addEventListener('keydown', e => { if (e.key === 'Enter') doJoin(); });

              // ── boot ──────────────────────────────────────────────────────────
              connect();
            })();
            </script>
            </body>
            </html>
            """;
}

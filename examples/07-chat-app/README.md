# 07 — Real-time Chat App

A complete browser chat application served and powered entirely by a single VATN plugin.
This is the VATN equivalent of the classic Node.js + Socket.io chat tutorial.

## Features

- **Username login** — join screen before entering the chat
- **Message broadcast** — every message goes to all connected users instantly
- **Online user list** — sidebar shows who is currently connected
- **Join / leave notifications** — system messages when users enter or exit
- **Typing indicators** — "Alice is typing…" banner shown to other users
- **Message history** — last 50 messages replayed to new joiners
- **Self-hosted UI** — the HTML/CSS/JS is served directly by the plugin; no static file server needed

## How it compares to the Node.js tutorial

| Node.js + Socket.io | VATN |
|---------------------|------|
| `express` serves `public/` | `VHttpService` serves the HTML page |
| `socket.io` server | `VWsListener` on `VNodeRunner` |
| `socket.emit('chat message', ...)` | `session.send(json)` |
| `io.emit(...)` broadcast | `VMessaging` pub/sub fan-out |
| No persistence | In-memory ring buffer (last 50 msgs) |

## Run it

```bash
mvn package -q
java -jar target/chat-app.jar
open http://localhost:8080/chat    # macOS
```

Open multiple tabs, pick different usernames, and chat.

## Key concepts

- Full-stack plugin: one class handles both HTTP (page serving) and WebSocket (chat logic)
- `VWsListener.onOpen/onMessage/onClose` — WebSocket lifecycle
- `VMessaging` pub/sub for fan-out broadcast to all connected sessions
- `VMessaging.unsubscribe()` — essential cleanup on disconnect
- In-memory ring buffer for message history
- Virtual thread per SSE / blocking sleep pattern avoided: WebSocket threads are parked by the Helidon runtime, not by the plugin

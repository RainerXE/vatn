# 03 — WebSocket Chat

A real-time group chat server. Every connected client receives messages broadcast by any other client. Equivalent to a Node.js + socket.io chat room.

## What it does

- WebSocket endpoint at `/ws/chat`
- On connect: sends `{"type":"welcome","sessionId":"<id>"}` 
- On message: broadcasts `{"type":"message","from":"<id>","text":"..."}` to all connected clients
- On disconnect: broadcasts `{"type":"left","sessionId":"<id>"}`

## Run it

```bash
mvn package -q
java -jar target/ws-chat.jar
# Open two browser tabs and run in the console of each:
# const ws = new WebSocket("ws://localhost:8080/ws/chat");
# ws.onmessage = e => console.log(e.data);
# ws.send("Hello everyone!");
```

## Key concepts

- `VWsListener` — WebSocket lifecycle callbacks (onOpen, onMessage, onClose)
- `VWsSession` — send text frames, close connections
- `VNodeRunner.registerWebSocket(path, listener)` — mount a WS endpoint
- `VMessaging` — pub/sub fanout to all session callbacks

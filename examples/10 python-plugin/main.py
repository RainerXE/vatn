import os
import json
import socket

def main():
    ipc_port = int(os.environ.get("VATN_IPC_PORT", 8080))
    print(f"[PY-PLUGIN] Starting OIPC listener on port {ipc_port}...")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", ipc_port))
        s.listen()
        while True:
            conn, addr = s.accept()
            with conn:
                data = conn.recv(1024)
                if not data: break
                req = json.loads(data.decode("utf-8"))
                print(f"[PY-PLUGIN] Received: {req}")

                # Simple pong for status checks
                if req.get("type") == "STATUS_CHECK":
                    res = {"status": "HEALTHY", "id": "demo-python"}
                    conn.sendall(json.dumps(res).encode("utf-8"))

if __name__ == "__main__":
    main()

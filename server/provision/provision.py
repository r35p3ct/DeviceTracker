import json
from http.server import BaseHTTPRequestHandler, HTTPServer

BROKER_PORT = 1883


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path.rstrip("/") != "/provision":
            self.send_error(404)
            return

        length = int(self.headers.get("Content-Length") or 0)
        body = b""
        if length:
            body = self.rfile.read(length)

        try:
            req = json.loads(body or b"{}")
        except json.JSONDecodeError:
            req = {}

        # Подставляем IP, с которого телефон прислал запрос — так телефон
        # находит брокер Mosquitto в своей сети без правки конфига.
        host = self.headers.get("Host", "localhost")
        host = host.split(":")[0]

        cfg = {
            "broker": f"tcp://{host}:{BROKER_PORT}",
            "interval_sec": 25,
            "topic_telemetry": f"devices/{req.get('device_id', 'unknown')}/telemetry",
            "topic_status": f"devices/{req.get('device_id', 'unknown')}/status",
            "topic_config": f"devices/{req.get('device_id', 'unknown')}/config",
            "topic_cmd": f"devices/{req.get('device_id', 'unknown')}/cmd",
        }

        payload = json.dumps(cfg).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        print(f"[provision] {self.address_string()} {fmt % args}")


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", 8080), Handler)
    print("provision server on :8080", flush=True)
    server.serve_forever()

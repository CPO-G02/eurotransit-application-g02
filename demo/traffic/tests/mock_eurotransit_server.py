#!/usr/bin/env python3
import json
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


ORDERS = {}
RESERVATIONS = {}
PAYMENTS = {}


class Handler(BaseHTTPRequestHandler):
    server_version = "EuroTransitSmoke/1.0"

    def log_message(self, _format, *_args):
        return

    def _json(self, status, body=None):
        payload = b"" if body is None else json.dumps(body).encode("utf-8")
        self.send_response(status)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def _authorized(self):
        value = self.headers.get("Authorization", "")
        if not value.startswith("Bearer "):
            self._json(401, {"error": "unauthorized"})
            return False
        if value == "Bearer forbidden":
            self._json(403, {"error": "forbidden"})
            return False
        return True

    def do_GET(self):
        if self.path == "/":
            self._json(200, {"service": "frontend"})
            return
        if self.path in (
            "/actuator/health/readiness",
            "/actuator/health/liveness",
        ):
            self._json(200, {"status": "UP"})
            return
        if self.path == "/api/v1/catalog/products":
            self._json(
                200,
                {
                    "products": [
                        {
                            "train_id": "TR-SMOKE-001",
                            "origin": "Torino",
                            "destination": "Milano",
                            "departure": "2026-07-16T10:00:00Z",
                            "seat_classes": [
                                {
                                    "class": "standard",
                                    "price": 12.50,
                                    "currency": "EUR",
                                    "available": 50,
                                }
                            ],
                        }
                    ]
                },
            )
            return
        if self.path == "/api/v1/orders":
            if self._authorized():
                self._json(200, [])
            return
        if self.path.startswith("/api/v1/orders/"):
            if not self._authorized():
                return
            order_id = self.path.rsplit("/", 1)[-1]
            order = next(
                (value for value in ORDERS.values() if value["order_id"] == order_id),
                None,
            )
            if order is None:
                self._json(404, {"error": "not_found"})
                return
            order["polls"] += 1
            if order["polls"] >= 2:
                order["status"] = "CONFIRMED"
            self._json(
                200,
                {
                    "order_id": order["order_id"],
                    "status": order["status"],
                    "train_id": order["body"]["train_id"],
                    "seat_class": order["body"]["seat_class"],
                    "quantity": order["body"]["quantity"],
                    "amount": order["body"]["amount"],
                    "currency": order["body"]["currency"],
                    "transaction_id": (
                        f"txn-{order['order_id']}" if order["status"] == "CONFIRMED" else None
                    ),
                    "created_at": "2026-07-16T10:00:00",
                    "confirmed_at": (
                        "2026-07-16T10:00:01" if order["status"] == "CONFIRMED" else None
                    ),
                },
            )
            return
        if self.path.startswith("/status/"):
            self._json(int(self.path.rsplit("/", 1)[-1]), {"status": "fixture"})
            return
        if self.path.startswith("/delay/"):
            milliseconds = int(self.path.rsplit("/", 1)[-1])
            time.sleep(milliseconds / 1000)
            self._json(200, {"delayed_ms": milliseconds})
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self):
        if self.path == "/api/v1/orders":
            if not self._authorized():
                return
            body = self._body()
            key = body["idempotency_key"]
            order = ORDERS.get(key)
            if order is None:
                order = {
                    "order_id": f"ord-{uuid.uuid4()}",
                    "status": "PENDING",
                    "polls": 0,
                    "body": body,
                }
                ORDERS[key] = order
                self._json(
                    202, {"order_id": order["order_id"], "status": order["status"]}
                )
            else:
                if body != order["body"]:
                    self._json(422, {"error": "idempotency_payload_mismatch"})
                    return
                status = 202 if order["status"] == "PENDING" else 409
                self._json(
                    status,
                    {
                        "order_id": order["order_id"],
                        "status": order["status"],
                        "message": "order already processed",
                    },
                )
            return
        if self.path == "/reserve":
            if not self._authorized():
                return
            body = self._body()
            if body["train_id"] == "FULL":
                self._json(409, {"status": "INSUFFICIENT_SEATS"})
                return
            key = body["idempotency_key"]
            existing = RESERVATIONS.get(key)
            if existing is not None and body != existing["body"]:
                self._json(422, {"error": "idempotency_payload_mismatch"})
                return
            if existing is None:
                existing = {"id": f"res-{uuid.uuid4()}", "body": body}
                RESERVATIONS[key] = existing
            self._json(
                200, {"reservation_id": existing["id"], "status": "RESERVED"}
            )
            return
        if self.path == "/api/v1/payments/authorize":
            if not self._authorized():
                return
            body = self._body()
            if float(body["amount"]) > 500:
                self._json(402, {"status": "DECLINED", "reason": "insufficient_funds"})
                return
            key = body["idempotency_key"]
            existing = PAYMENTS.get(key)
            if existing is not None and body != existing["body"]:
                self._json(422, {"error": "idempotency_payload_mismatch"})
                return
            if existing is None:
                existing = {"id": f"txn-{uuid.uuid4()}", "body": body}
                PAYMENTS[key] = existing
            self._json(
                200, {"transaction_id": existing["id"], "status": "AUTHORIZED"}
            )
            return
        if self.path == "/gateway/charge":
            body = self._body()
            delay_ms = int(self.headers.get("X-Simulate-Delay-Ms", "0"))
            if delay_ms:
                time.sleep(delay_ms / 1000)
            if self.headers.get("X-Simulate-Failure", "").lower() == "true":
                self._json(503)
                return
            self._json(
                200,
                {
                    "decision": (
                        "AUTHORIZED" if float(body["amount"]) <= 500 else "DECLINED"
                    )
                },
            )
            return
        self._json(404, {"error": "not_found"})


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: mock_eurotransit_server.py PORT")
    server = ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()

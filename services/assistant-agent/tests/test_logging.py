import json
import logging

from app.logging_setup import JsonFormatter


def test_json_log_includes_session_id():
    rec = logging.LogRecord("x", logging.INFO, "f", 1, "hello %s", ("w",), None)
    rec.sessionId = "s-1"
    out = json.loads(JsonFormatter().format(rec))
    assert out["msg"] == "hello w" and out["sessionId"] == "s-1" and out["level"] == "INFO"

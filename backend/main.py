import json
import os
import threading
from datetime import datetime, timezone

import paho.mqtt.client as mqtt
import pg8000.native
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.templating import Jinja2Templates

MQTT_BROKER = os.environ.get("MQTT_BROKER", "localhost")
MQTT_PORT = int(os.environ.get("MQTT_PORT", 1883))
PG_HOST = os.environ.get("PG_HOST", "localhost")
PG_PORT = int(os.environ.get("PG_PORT", 5432))
PG_USER = os.environ.get("PG_USER", "ttrss")
PG_PASSWORD = os.environ.get("PG_PASSWORD", "ttrss")
PG_DB = os.environ.get("PG_DB", "ttrss")
LISTEN_PORT = int(os.environ.get("LISTEN_PORT", 8000))

app = FastAPI(title="DeviceTracker Backend")
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
templates = Jinja2Templates(directory=os.path.join(BASE_DIR, "templates"))

devices: dict = {}
lock = threading.Lock()


def get_pg():
    return pg8000.native.Connection(
        host=PG_HOST,
        port=PG_PORT,
        user=PG_USER,
        password=PG_PASSWORD,
        database=PG_DB,
        timeout=5,
    )


def init_db():
    conn = get_pg()
    conn.run("""
        CREATE TABLE IF NOT EXISTS device_telemetry (
            id SERIAL PRIMARY KEY,
            device_id TEXT NOT NULL,
            ts BIGINT NOT NULL,
            lat DOUBLE PRECISION,
            lon DOUBLE PRECISION,
            accuracy DOUBLE PRECISION,
            provider TEXT,
            cell_tac INTEGER,
            cell_cid INTEGER,
            cell_mcc INTEGER,
            cell_mnc INTEGER,
            battery_pct INTEGER,
            speed DOUBLE PRECISION,
            raw JSONB,
            received_at TIMESTAMPTZ DEFAULT NOW()
        )
    """)
    conn.run("""
        CREATE INDEX IF NOT EXISTS idx_telem_device_ts
            ON device_telemetry(device_id, ts DESC)
    """)
    conn.close()
    print("DB initialized")


def on_connect(client, _userdata, _flags, rc, _props=None):
    print(f"MQTT connected (rc={rc})")
    res = client.subscribe("devices/+/telemetry")
    print(f"MQTT subscribed: {res}")


def on_message(_client, _userdata, msg):
    print(f"MQTT msg on {msg.topic}: {len(msg.payload)} bytes")
    try:
        data = json.loads(msg.payload)
    except json.JSONDecodeError:
        print(f"MQTT skip: not JSON")
        return

    device_id = data.get("device_id", msg.topic.split("/")[1])
    ts = data.get("ts", 0)
    dt = datetime.fromtimestamp(ts, tz=timezone.utc).isoformat() if ts else None

    loc = data.get("loc") if isinstance(data.get("loc"), dict) else {}
    lat = loc.get("lat") or data.get("lat")
    lon = loc.get("lng") or loc.get("lon") or data.get("lng") or data.get("lon")

    point = {
        "device_id": device_id,
        "ts": ts,
        "time": dt,
        "lat": lat,
        "lon": lon,
        "accuracy": loc.get("acc") or data.get("acc"),
        "provider": loc.get("provider") or data.get("provider"),
        "battery_pct": data.get("battery"),
        "speed": loc.get("speed") or data.get("speed"),
        "cells": data.get("cells"),
        "wifi": data.get("wifi"),
    }

    with lock:
        devices[device_id] = point

    try:
        conn = get_pg()
        conn.run(
            """
            INSERT INTO device_telemetry
                (device_id, ts, lat, lon, accuracy, provider,
                 cell_tac, cell_cid, cell_mcc, cell_mnc,
                 battery_pct, speed, raw)
            VALUES (:device_id, :ts, :lat, :lon, :accuracy, :provider,
                    :tac, :cid, :mcc, :mnc, :batt, :speed, :raw)
            """,
            device_id=device_id,
            ts=ts,
            lat=lat,
            lon=lon,
            accuracy=loc.get("acc") or data.get("acc"),
            provider=loc.get("provider") or data.get("provider"),
            tac=data.get("tac"),
            cid=data.get("cid"),
            mcc=data.get("mcc"),
            mnc=data.get("mnc"),
            batt=data.get("battery"),
            speed=loc.get("speed") or data.get("speed"),
            raw=json.dumps(data),
        )
        conn.close()
    except Exception as e:
        print(f"PG write error: {e}")


def start_mqtt():
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.on_connect = on_connect
    client.on_message = on_message
    try:
        client.connect(MQTT_BROKER, MQTT_PORT, 60)
    except Exception as e:
        print(f"MQTT initial connect failed: {e}")
        return
    client.loop_forever()


@app.on_event("startup")
async def startup():
    try:
        init_db()
    except Exception as e:
        print(f"DB init failed (continuing without PG): {e}")
    t = threading.Thread(target=start_mqtt, daemon=True)
    t.start()
    print(f"Backend ready on port {LISTEN_PORT}")


@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    return templates.TemplateResponse("map.html", {"request": request})


@app.get("/api/devices")
async def api_devices():
    with lock:
        return JSONResponse(list(devices.values()))


@app.get("/api/history/{device_id}")
async def api_history(device_id: str, limit: int = 200):
    try:
        conn = get_pg()
        rows = conn.run(
            """
            SELECT ts, lat, lon, accuracy, provider, battery_pct, speed
            FROM device_telemetry
            WHERE device_id = :device_id
            ORDER BY ts DESC
            LIMIT :limit
            """,
            device_id=device_id,
            limit=limit,
        )
        conn.close()
        result = []
        for r in rows:
            result.append({
                "ts": r[0],
                "time": datetime.fromtimestamp(r[0], tz=timezone.utc).isoformat() if r[0] else None,
                "lat": r[1],
                "lon": r[2],
                "accuracy": r[3],
                "provider": r[4],
                "battery_pct": r[5],
                "speed": r[6],
            })
        return JSONResponse(result)
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=LISTEN_PORT)

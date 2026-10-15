#!/usr/bin/env python3
"""Sengled local replacement server.

Single-file, stdlib-only replacement for Sengled's shut-down cloud:
  - HTTP bootstrap endpoints the bulb calls after pairing
    (/life2/device/accessCloud.json, /jbalancer/new/bimqtt)
  - Embedded MQTT broker on TLS 8883 tuned for ESP8266 bulbs
    (TLS 1.2, weak-cipher tolerant, x-amzn-mqtt-ca ALPN)
  - REST control API for your own app (/api/...)
  - UDP 9080 control fallback and LAN discovery

Run:  python3 sengled_server.py --certs ./certs
Requires certs from setup_certs.sh (ca.crt, server.crt, server.key).
"""

import argparse
import json
import os
import socket
import ssl
import struct
import sys
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

STATE_VERSION = 1
SERVER_VERSION = "sengled-local 1.8.0 (authoritative power state)"
DEFAULT_HTTP_PORT = 57542
DEFAULT_MQTT_PORT = 8883
BULB_UDP_PORT = 9080


def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def get_lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if not ip.startswith("127."):
            return ip
    except OSError:
        pass
    try:
        return socket.gethostbyname(socket.gethostname())
    except OSError:
        return "127.0.0.1"


def encode_varint(n):
    out = b""
    while True:
        byte = n % 128
        n //= 128
        if n > 0:
            byte |= 0x80
        out += struct.pack("!B", byte)
        if n == 0:
            return out


def topic_matches(pattern, topic):
    p = pattern.split("/")
    t = topic.split("/")
    for i, seg in enumerate(p):
        if seg == "#":
            return True
        if i >= len(t):
            return False
        if seg == "+":
            continue
        if seg != t[i]:
            return False
    return len(p) == len(t)


class BulbStore:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.bulbs = {}
        self._load()

    def _load(self):
        try:
            with open(self.path) as f:
                data = json.load(f)
            if isinstance(data, dict):
                self.bulbs = data.get("bulbs", {})
        except (OSError, ValueError):
            self.bulbs = {}

    def save(self):
        with self.lock:
            try:
                with open(self.path, "w") as f:
                    json.dump({"version": STATE_VERSION, "bulbs": self.bulbs}, f, indent=2)
            except OSError as e:
                log(f"state save failed: {e}")

    def update(self, mac, **fields):
        with self.lock:
            entry = self.bulbs.setdefault(mac, {"mac": mac})
            entry.update(fields)
            entry["last_seen"] = int(time.time() * 1000)

    def get(self, mac):
        with self.lock:
            return self.bulbs.get(mac)

    def all(self):
        with self.lock:
            return dict(self.bulbs)


class RoomsStore:
    def __init__(self, path, bulb_store):
        self.path = path
        self.bulbs = bulb_store
        self.lock = threading.Lock()
        self.rooms = []
        self._load()

    def _load(self):
        try:
            with open(self.path) as f:
                data = json.load(f)
            if isinstance(data, list):
                self.rooms = [r for r in data if isinstance(r, dict) and isinstance(r.get("name"), str)]
                for r in self.rooms:
                    r.setdefault("bulbs", [])
        except (OSError, ValueError):
            self.rooms = []

    def save(self):
        with self.lock:
            try:
                with open(self.path, "w") as f:
                    json.dump(self.rooms, f, indent=2)
            except OSError as e:
                log(f"rooms save failed: {e}")

    def create(self, name):
        with self.lock:
            name = name.strip()
            if not name:
                return {"error": "empty name"}
            if any(r["name"] == name for r in self.rooms):
                return {"error": "room exists"}
            self.rooms.append({"name": name, "bulbs": []})
        self.save()
        return {"created": name}

    def delete(self, name):
        with self.lock:
            before = len(self.rooms)
            self.rooms = [r for r in self.rooms if r["name"] != name]
        if len(self.rooms) == before:
            return {"error": "no such room"}
        self.save()
        return {"deleted": name}

    def assign(self, name, mac):
        with self.lock:
            room = next((r for r in self.rooms if r["name"] == name), None)
            if room is None:
                return {"error": "no such room"}
            for r in self.rooms:
                if mac in r["bulbs"]:
                    r["bulbs"].remove(mac)
            if mac not in room["bulbs"]:
                room["bulbs"].append(mac)
        self.save()
        return {"assigned": mac, "room": name}

    def macs_of(self, name):
        with self.lock:
            for r in self.rooms:
                if r["name"] == name:
                    return list(r["bulbs"])
        return []

    def snapshot(self):
        with self.lock:
            rooms = [{"name": r["name"], "bulbs": list(r["bulbs"])} for r in self.rooms]
        assigned = set()
        for r in rooms:
            assigned.update(r["bulbs"])
        unassigned = [mac for mac in self.bulbs.all() if mac != "pending" and mac not in assigned]
        return {"rooms": rooms, "unassigned": unassigned}


class BrokerCore:
    def __init__(self):
        self.lock = threading.Lock()
        self.clients = []
        self.retained = {}
        self.local_watchers = []

    def register(self, conn):
        with self.lock:
            self.clients.append(conn)

    def unregister(self, conn):
        with self.lock:
            if conn in self.clients:
                self.clients.remove(conn)

    def publish(self, topic, payload, retain=False, exclude=None):
        data = payload if isinstance(payload, bytes) else payload.encode("utf-8")
        if retain:
            with self.lock:
                if data:
                    self.retained[topic] = data
                else:
                    self.retained.pop(topic, None)
        with self.lock:
            targets = list(self.clients)
            watchers = list(self.local_watchers)
        for conn in targets:
            if conn is exclude:
                continue
            for sub_filter, _qos in list(conn.subscriptions):
                if topic_matches(sub_filter, topic):
                    try:
                        conn.send_publish(topic, data, qos=0)
                    except Exception as e:
                        log(f"dropping dead client {conn.addr}: {e}")
                        conn.alive = False
                        self.unregister(conn)
                    break
        for watcher, watch_filter in watchers:
            if topic_matches(watch_filter, topic):
                try:
                    watcher(topic, data)
                except Exception as e:
                    log(f"watcher error: {e}")


def read_frame(sock):
    header = sock.recv(1)
    if not header:
        return None, None
    byte0 = header[0]
    mult = 1
    length = 0
    while True:
        chunk = sock.recv(1)
        if not chunk:
            return None, None
        digit = chunk[0]
        length += (digit & 0x7F) * mult
        mult *= 128
        if not digit & 0x80:
            break
    body = b""
    while len(body) < length:
        chunk = sock.recv(length - len(body))
        if not chunk:
            return None, None
        body += chunk
    return byte0, body


def parse_string(body, offset):
    (slen,) = struct.unpack_from("!H", body, offset)
    val = body[offset + 2 : offset + 2 + slen]
    return val, offset + 2 + slen


class MQTTClientConn(threading.Thread):
    def __init__(self, broker, sock, addr, verbose=False):
        super().__init__(daemon=True)
        self.broker = broker
        self.sock = sock
        self.addr = addr
        self.verbose = verbose
        self.subscriptions = []
        self.send_lock = threading.Lock()
        self.alive = True

    def run(self):
        self.broker.register(self)
        try:
            while self.alive:
                byte0, body = read_frame(self.sock)
                if byte0 is None:
                    break
                ptype = byte0 >> 4
                if ptype == 1:
                    self._handle_connect(body)
                elif ptype == 3:
                    self._handle_publish(byte0, body)
                elif ptype == 4:
                    pass
                elif ptype == 8:
                    self._handle_subscribe(body)
                elif ptype == 10:
                    self._send_raw(b"\xd0\x00")
                elif ptype == 14:
                    break
        except (OSError, ssl.SSLError, ValueError) as e:
            if self.verbose:
                log(f"client {self.addr} error: {e}")
        finally:
            self.broker.unregister(self)
            try:
                self.sock.close()
            except OSError:
                pass

    def _handle_connect(self, body):
        try:
            client_id = body[2 : 2 + struct.unpack_from("!H", body, 0)[0]].decode("utf-8", "replace")
        except (struct.error, IndexError):
            client_id = "?"
        if self.verbose:
            log(f"MQTT CONNECT from {self.addr[0]} id={client_id!r}")
        self._send_raw(b"\x20\x02\x00\x00")

    def _handle_publish(self, byte0, body):
        qos = (byte0 >> 1) & 0x03
        try:
            topic_bytes, offset = parse_string(body, 0)
            topic = topic_bytes.decode("utf-8", "replace")
            if qos > 0:
                (pid,) = struct.unpack_from("!H", body, offset)
                offset += 2
            payload = body[offset:]
            self.broker.publish(topic, payload, retain=bool(byte0 & 0x01), exclude=self)
            if self.verbose:
                log(f"MQTT PUBLISH {topic} ({len(payload)}B) from {self.addr[0]}")
            if qos > 0:
                self._send_raw(struct.pack("!BBH", 0x40, 0x02, pid))
        except (struct.error, IndexError):
            pass

    def _handle_subscribe(self, body):
        try:
            (pid,) = struct.unpack_from("!H", body, 0)
            offset = 2
            grants = []
            while offset < len(body):
                topic_bytes, offset = parse_string(body, offset)
                qos = body[offset]
                offset += 1
                filt = topic_bytes.decode("utf-8", "replace")
                self.subscriptions.append((filt, qos))
                grants.append(min(qos, 1))
                if self.verbose:
                    log(f"MQTT SUBSCRIBE {filt} from {self.addr[0]}")
            reply = struct.pack("!BBH", 0x90, 2 + len(grants), pid) + bytes(grants)
            self._send_raw(reply)
            for filt, _ in self.subscriptions:
                for rtopic, rdata in list(self.broker.retained.items()):
                    if topic_matches(filt, rtopic):
                        self.send_publish(rtopic, rdata, 0, retain=True)
        except (struct.error, IndexError):
            pass

    def send_publish(self, topic, payload, qos=0, retain=False):
        topic_bytes = topic.encode("utf-8")
        body = struct.pack("!H", len(topic_bytes)) + topic_bytes + payload
        byte0 = 0x30 | (qos << 1) | (0x01 if retain else 0x00)
        self._send_raw(struct.pack("!B", byte0) + encode_varint(len(body)) + body)

    def _send_raw(self, data):
        with self.send_lock:
            self.sock.sendall(data)


class MQTTBroker(threading.Thread):
    def __init__(self, core, certs_dir, port, verbose=False):
        super().__init__(daemon=True)
        self.core = core
        self.port = port
        self.verbose = verbose
        self.certfile = os.path.join(certs_dir, "server.crt")
        self.keyfile = os.path.join(certs_dir, "server.key")
        self.ready = threading.Event()
        self.error = None

    def _ssl_context(self):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(self.certfile, self.keyfile)
        ctx.verify_mode = ssl.CERT_NONE
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.maximum_version = ssl.TLSVersion.TLSv1_2
        try:
            ctx.set_ciphers(
                "DEFAULT:@SECLEVEL=0:"
                "ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384:"
                "ECDHE-RSA-AES128-SHA256:ECDHE-RSA-AES256-SHA384:"
                "AES128-GCM-SHA256:AES256-GCM-SHA384:"
                "AES128-SHA256:AES256-SHA256:AES128-SHA:AES256-SHA"
            )
        except ssl.SSLError:
            try:
                ctx.set_ciphers("DEFAULT@SECLEVEL=0")
            except ssl.SSLError:
                pass
        try:
            ctx.set_ecdh_curve("prime256v1")
        except Exception:
            pass
        try:
            ctx.set_alpn_protocols(["x-amzn-mqtt-ca", "mqtt"])
        except (NotImplementedError, AttributeError):
            pass
        return ctx

    def run(self):
        try:
            ctx = self._ssl_context()
            lsock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            lsock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            lsock.bind(("0.0.0.0", self.port))
            lsock.listen(8)
        except OSError as e:
            self.error = f"MQTT broker bind failed on port {self.port}: {e}"
            self.ready.set()
            return
        self.ready.set()
        while True:
            try:
                csock, addr = lsock.accept()
                try:
                    tls_sock = ctx.wrap_socket(csock, server_side=True)
                    # Appliances may vanish mid-TCP; bound the WRITE so a wedged
                    # subscriber can never block publishes for other clients.
                    tls_sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDTIMEO,
                                        struct.pack("ll", 2, 0))
                except (ssl.SSLError, OSError) as e:
                    if self.verbose:
                        log(f"TLS handshake failed from {addr[0]}: {e}")
                    try:
                        csock.close()
                    except OSError:
                        pass
                    continue
                MQTTClientConn(self.core, tls_sock, addr, self.verbose).start()
            except OSError as e:
                log(f"MQTT accept error: {e}")
                time.sleep(0.5)


class ControlAPI:
    """Queued, paced, last-value-wins bulb control.

    Sliders generate floods; ESP8266 bulbs choke. REST handlers only record
    the LATEST value per (mac, action); a worker publishes each with minimum
    spacing, so intermediate values are never queued against the bulb.
    """

    MIN_GAP_PER_MAC = 0.2
    MIN_GAP_PER_KEY = 0.5

    def __init__(self, core, store, verbose=False):
        self.core = core
        self.store = store
        self.verbose = verbose
        self.pending = {}
        self.lock = threading.Lock()
        self.wake = threading.Event()
        self.executed_mac = {}
        self.executed_key = {}
        self.executed_val = {}
        self.last_brightness = {}
        self.confirm_token = {}
        self.token_lock = threading.Lock()
        self.power_intended = {}
        self.recent_exec = {} 
        self.query_mac = None
        self.query_event = threading.Event()
        threading.Thread(target=self._worker, daemon=True).start()

    def query_bulb(self, mac, timeout=2.5):
        """Actively ask the bulb for its full state. Returns store attrs after the
        attempt (MQTT status query awaits the bulb's own reply; UDP brightness
        probe adds a deterministic extra channel)."""
        self.query_mac = mac
        self.query_event.clear()
        ts = int(time.time() * 1000)
        self.core.publish(f"wifielement/{mac}/update",
                          json.dumps([{"dn": mac, "type": "status", "value": "", "time": ts}]),
                          retain=False)
        entry = self.store.get(mac) or {}
        udp_br = None
        ip = entry.get("ip")
        if ip:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.settimeout(1.0)
                s.sendto(json.dumps({"func": "get_device_brightness", "param": {}}).encode("utf-8"),
                         (ip, BULB_UDP_PORT))
                data, _ = s.recvfrom(4096)
                s.close()
                resp = json.loads(data.decode("utf-8"))
                udp_br = resp.get("result", {}).get("brightness")
            except (OSError, ValueError) as e:
                if self.verbose:
                    log(f"UDP brightness query failed ({ip}): {e}")
        self.query_event.wait(timeout)
        self.query_mac = None
        entry = self.store.get(mac) or {}
        attrs = dict(entry.get("attributes") or {})
        if udp_br is not None:
            attrs["brightness"] = str(udp_br)
        if attrs:
            self.store.update(mac, attributes=attrs)
            self.store.save()
        return attrs

    def request(self, mac, action, value, gradient=None, use_udp=False):
        key = (mac, action)
        with self.lock:
            self.pending[key] = {
                "mac": mac, "action": action, "value": value,
                "gradient": gradient, "use_udp": use_udp, "ts": time.time(),
            }
        self.wake.set()
        return {"mac": mac, "action": action, "value": value, "queued": True}

    def mqtt_command(self, mac, cmd_type, value, gradient=None):
        cmd = {"dn": mac, "type": cmd_type, "value": str(value), "time": int(time.time() * 1000)}
        if gradient is not None:
            cmd["gradientTime"] = int(gradient)
        self.core.publish(f"wifielement/{mac}/update", json.dumps([cmd]), retain=False)

    def publish_commands(self, mac, cmds, retain=False):
        for c in cmds:
            c.setdefault("dn", mac)
            c.setdefault("time", int(time.time() * 1000))
            c["value"] = str(c["value"])
        self.core.publish(f"wifielement/{mac}/update", json.dumps(cmds), retain=retain)

    def udp_command(self, ip, func, param):
        payload = json.dumps({"func": func, "param": param}).encode("utf-8")
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.sendto(payload, (ip, BULB_UDP_PORT))
            s.close()
            return {"sent": True}
        except OSError as e:
            if self.verbose:
                log(f"UDP send failed ({ip}): {e}")
            return None

    def discover(self, timeout=3.0):
        found = {}
        req = json.dumps({"func": "search_devices", "param": {}}).encode("utf-8")
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(timeout)
        try:
            s.sendto(req, ("255.255.255.255", BULB_UDP_PORT))
            while True:
                try:
                    data, addr = s.recvfrom(4096)
                except socket.timeout:
                    break
                try:
                    resp = json.loads(data.decode("utf-8"))
                except ValueError:
                    continue
                mac = resp.get("mac") or resp.get("payload", {}).get("mac")
                if mac:
                    found[mac] = {"ip": addr[0], "response": resp}
        finally:
            s.close()
        return found

    def _worker(self):
        while True:
            self.wake.wait()
            self.wake.clear()
            while True:
                with self.lock:
                    if not self.pending:
                        break
                    key, item = next(iter(self.pending.items()))
                    del self.pending[key]
                try:
                    self._execute(key, item)
                except Exception as e:
                    log(f"control execute error {key}: {e}")

    def _execute(self, key, item, attempt=0):
        mac, action = key
        mac = item["mac"]
        action = item["action"]
        value = item["value"]
        entry = self.store.get(mac) or {}
        now = time.time()
        gap = 0.0
        last_any = self.executed_mac.get(mac, 0)
        last_key = self.executed_key.get(key, 0)
        gap = max(self.MIN_GAP_PER_MAC - (now - last_any),
                  self.MIN_GAP_PER_KEY - (now - last_key), 0.0)
        if gap > 0:
            time.sleep(gap)
        duplicate = (self.executed_val.get(key) == str(value)
                     and time.time() - self.executed_key.get(key, 0) < 0.15)
        if not duplicate:
            if action == "power":
                self.power_intended[mac] = "1" if value else "0"
                self.mqtt_command(mac, "switch", "1" if value else "0", item["gradient"])
            elif action == "brightness":
                self.mqtt_command(mac, "brightness", int(value), item["gradient"])
                self.last_brightness[mac] = int(value)
            elif action == "color":
                r, g, b = value
                self.mqtt_command(mac, "color", f"{int(r)}:{int(g)}:{int(b)}", item["gradient"])
            elif action == "colortemp":
                br = self.last_brightness.get(mac)
                if br is None:
                    br_attr = (entry.get("attributes") or {}).get("brightness")
                    try:
                        br = int(br_attr)
                    except (TypeError, ValueError):
                        br = None
                cmds = [{"type": "colorTemperature", "value": int(value)}]
                ts = int(time.time() * 1000)
                if br is not None:
                    cmds = [{"type": "colorTemperature", "value": int(value), "time": ts},
                            {"type": "brightness", "value": int(br), "time": ts}]
                self.publish_commands(mac, cmds)
            if item.get("use_udp"):
                ip = entry.get("ip")
                if ip:
                    funcs = {"power": "set_device_switch", "brightness": "set_device_brightness",
                             "color": "set_device_color", "colortemp": "set_device_colortemp"}
                    params = {"power": {"switch": 1 if value else 0},
                              "brightness": {"brightness": int(value)},
                              "color": {"red": int(value[0]), "green": int(value[1]), "blue": int(value[2])},
                              "colortemp": {"colorTemperature": int(value)}}
                    if action in funcs:
                        self.udp_command(ip, funcs[action], params[action])
        self.executed_val[key] = str(value)
        self.executed_mac[mac] = time.time()
        self.executed_key[key] = time.time()
        with self.token_lock:
            self.confirm_token[key] = self.confirm_token.get(key, 0) + 1
            token = self.confirm_token[key]
        threading.Thread(target=self._confirm_async,
                         args=(key, dict(item), token), daemon=True).start()

    def _confirm_async(self, key, item, token):
        """Non-blocking closed loop: wait for the bulb echo, one UDP fallback,
        one MQTT re-publish — for stale/missing echoes only."""
        mac, action = key
        value = item["value"]
        time.sleep(0.35)
        with self.token_lock:
            if self.confirm_token.get(key) != token:
                return
        if self._echo_matches(mac, action, value):
            return
        ip = (self.store.get(mac) or {}).get("ip")
        if ip:
            funcs = {"power": ("set_device_switch", lambda: {"switch": 1 if value else 0}),
                     "brightness": ("set_device_brightness", lambda: {"brightness": int(value)}),
                     "colortemp": ("set_device_colortemp", lambda: {"colorTemperature": int(value)}),
                     "color": ("set_device_color", lambda: {"red": int(value[0]), "green": int(value[1]), "blue": int(value[2])})}
            f = funcs.get(action)
            if f:
                self.udp_command(ip, f[0], f[1]())
                time.sleep(0.5)
                with self.token_lock:
                    if self.confirm_token.get(key) != token:
                        return
                if self._echo_matches(mac, action, value):
                    if self.verbose:
                        log(f"UDP fallback confirmed {mac} {action}")
                    return
        if self.verbose:
            log(f"echo NOT confirmed for {mac} {action} - re-publishing once via MQTT")
        try:
            self._execute(key, item, attempt=1)
        except Exception as e:
            log(f"re-publish failed {mac} {action}: {e}")

    def _echo_matches(self, mac, action, value):
        attr = self.ATTR_FOR_ACTION.get(action)
        if attr is None:
            return True
        got = ((self.store.get(mac) or {}).get("attributes") or {}).get(attr)
        if got is None:
            return False
        want = str(value if action != "power" else ("1" if value else "0"))
        if action == "color":
            want = f"{int(value[0])}:{int(value[1])}:{int(value[2])}"
        elif action in ("brightness", "colortemp"):
            want = str(int(value))
        return str(got) == want

    ATTR_FOR_ACTION = {"power": "switch", "brightness": "brightness",
                       "colortemp": "colorTemperature", "color": "color"}

    def _recent_count(self, mac):
        now = time.time()
        lst = [t for t in self.recent_exec.get(mac, []) if now - t < 2.0]
        self.recent_exec[mac] = lst
        return len(lst)

    def _await_echo(self, mac, action, value, attempt=0, timeout=None):
        """Wait for the bulb's status echo for this command; re-publish once
        if the echo never matches (protects against the ~20% drop we measured)."""
        attr = self.ATTR_FOR_ACTION.get(action)
        if attr is None:
            return
        if timeout is None:
            # fast interaction (drag/streams) gets a short window; deliberate
            # single shots get a longer one
            timeout = 0.4 if self._recent_count(mac) >= 3 else 1.0
        want = str(value if action != "power" else ("1" if value else "0"))
        if action == "color":
            want = f"{int(value[0])}:{int(value[1])}:{int(value[2])}"
        elif action in ("brightness", "colortemp"):
            want = str(int(value))
        deadline = time.time() + timeout
        while time.time() < deadline:
            attrs = (self.store.get(mac) or {}).get("attributes") or {}
            got = attrs.get(attr)
            if got is not None and str(got) == want:
                if self.verbose:
                    log(f"echo confirmed {mac} {action}={want}")
                return
            time.sleep(0.2)
        if self.verbose:
            log(f"echo NOT confirmed for {mac} {action}={want} — re-publishing")
        self.executed_mac[mac] = 0.0
        self.executed_key[(mac, action)] = 0.0
        try:
            self._execute((mac, action), {"mac": mac, "action": action, "value": value,
                                          "gradient": None, "use_udp": False, "ts": 0.0})
        except Exception as e:
            log(f"re-publish failed for {mac} {action}: {e}")


class EventHub:
    def __init__(self):
        self.version = 1
        self.cond = threading.Condition()

    def bump(self, mac=None):
        with self.cond:
            self.version += 1
            self.cond.notify_all()

    def wait(self, since, timeout=25.0):
        with self.cond:
            if self.version > since:
                return self.version
            self.cond.wait(timeout)
            return self.version

def status_watcher(store, api=None, hub=None):
    last_save = [0.0]

    def watch(topic, payload):
        parts = topic.split("/")
        if len(parts) != 3 or parts[0] != "wifielement" or parts[2] != "status":
            return
        mac = parts[1]
        try:
            data = json.loads(payload.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return
        attrs = {}
        if isinstance(data, list):
            for item in data:
                if isinstance(item, dict) and "type" in item and "value" in item:
                    attrs[item["type"]] = item["value"]
        elif isinstance(data, dict):
            attrs = data
        store.update(mac, attributes=attrs)
        if api is not None and api.query_mac == mac:
            api.query_event.set()
        if hub is not None:
            hub.bump(mac)
        now = time.time()
        if now - last_save[0] > 2.0:
            last_save[0] = now
            store.save()
        log(f"status from {mac}: {attrs}")

    return watch


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "sengled-local/1.0"

    def log_message(self, fmt, *args):
        pass

    def _json(self, data, code=200):
        payload = json.dumps(data).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        return self.rfile.read(length) if length > 0 else b""

    def _client_ip(self):
        return self.client_address[0]

    def _augmented_bulbs(self):
        """Bulb snapshot with the server's authoritative power intent merged in
        wherever the bulb hasn't echoed its switch state (multi-client truth)."""
        api = self.server.api
        snapshot = json.loads(json.dumps(self.server.store.all()))
        for mac, info in snapshot.items():
            if mac == "pending":
                continue
            attrs = info.setdefault("attributes", {})
            if "switch" not in attrs and mac in api.power_intended:
                attrs["switch"] = api.power_intended[mac]
        return snapshot

    def do_GET(self):
        path = self.path.split("?")[0]
        api = self.server.api
        store = self.server.store
        rooms = self.server.rooms
        cfg = self.server.cfg

        if path == "/life2/device/accessCloud.json":
            self._json({"messageCode": "200", "info": "OK", "description": "正常", "success": True})
            if self._client_ip() not in ("127.0.0.1", "::1"):
                log(f"bulb bootstrap: accessCloud.json hit from {self._client_ip()}")
            return

        if path == "/jbalancer/new/bimqtt":
            self._json({"protocal": "mqtt", "host": cfg["lan_ip"], "port": cfg["mqtt_port"]})
            if self._client_ip() not in ("127.0.0.1", "::1"):
                log(f"bulb bootstrap: bimqtt hit from {self._client_ip()}")
            return

        if path == "/status":
            self._json({"lan_ip": cfg["lan_ip"], "mqtt_port": cfg["mqtt_port"], "bulbs": store.all()})
            return

        if path == "/api/health":
            self._json({"ok": True, "lan_ip": cfg["lan_ip"]})
            return

        if path == "/api/events":
            import urllib.parse as _up
            try:
                since = int(_up.parse_qs(_up.urlparse(self.path).query).get("since", ["1"])[0])
            except ValueError:
                since = 1
            hub = self.server.hub
            version = hub.wait(since, 25.0)
            changed = version > since
            self._json({
                "version": version,
                "changed": changed,
                "bulbs": self._augmented_bulbs() if changed else {},
            })
            return

        if path == "/api/version":
            self._json({
                "version": SERVER_VERSION,
                "endpoints": [
                    "/jbalancer/new/bimqtt",
                    "/life2/device/accessCloud.json",
                    "/status",
                    "/api/health", "/api/version", "/api/bulbs", "/api/rooms",
                    "/api/discover",
                    "/api/bulbs/<mac>/power|brightness|color|colortemp|raw|name|update|register",
                    "/api/rooms/<name>/assign",
                ],
                "bulbs_known": len(store.all()),
                "rooms": len(self.server.rooms.snapshot()["rooms"]),
                "uptime_s": int(time.time() - self.server.started_at),
            })
            return

        if path == "/api/bulbs":
            self._json({"bulbs": self._augmented_bulbs()})
            return

        if path.startswith("/api/discover"):
            self._json({"found": api.discover()})
            return

        if path == "/api/rooms":
            self._json(rooms.snapshot())
            return

        self._json({"error": "not found"}, 404)

    def do_POST(self):
        path = self.path.split("?")[0]
        api = self.server.api
        store = self.server.store
        rooms = self.server.rooms
        body = self._read_body()

        if path == "/api/refresh":
            result = {}
            for mac in list(store.all().keys()):
                if mac == "pending":
                    continue
                result[mac] = api.query_bulb(mac)
            self._json({"bulbs": self._augmented_bulbs(), "queried": result})
            return

        if path == "/life2/device/accessCloud.json":
            self._json({"messageCode": "200", "info": "OK", "description": "正常", "success": True})
            ip = self._client_ip()
            if ip not in ("127.0.0.1", "::1"):
                log(f"bulb bootstrap: accessCloud.json POST from {ip} — bulb is on the LAN at {ip}")
                mac_hint = None
                try:
                    req = json.loads(body.decode("utf-8"))
                    mac_hint = req.get("mac") or req.get("deviceUuid")
                except (ValueError, UnicodeDecodeError):
                    pass
                if mac_hint:
                    store.update(mac_hint, ip=ip)
                else:
                    store.update("pending", ip=ip)
                store.save()
            return

        if path == "/jbalancer/new/bimqtt":
            self.do_GET()
            return

        parts = [p for p in path.split("/") if p]
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "bulbs" and parts[3] not in ("name", "update"):
            mac, action = parts[2], parts[3]
            try:
                req = json.loads(body.decode("utf-8")) if body else {}
            except ValueError:
                req = {}
            if action == "power":
                value = bool(req.get("on", req.get("value")))
            elif action in ("brightness", "colortemp"):
                value = req.get("value", req.get("kelvin"))
                if value is None:
                    self._json({"error": "value required"}, 400)
                    return
            elif action == "color":
                value = (req.get("r", 0), req.get("g", 0), req.get("b", 0))
            elif action == "raw":
                ctype = req.get("type")
                if not ctype:
                    self._json({"error": "type required"}, 400)
                    return
                api.mqtt_command(mac, ctype, req.get("value", ""))
                self._json({"mac": mac, "action": "raw", "type": ctype})
                return
            else:
                self._json({"error": f"unknown action {action}"}, 404)
                return
            result = api.request(mac, action, value, gradient=req.get("gradient"))
            if result is None:
                self._json({"error": "unknown action"}, 404)
            else:
                self._json(result)
            return

        if path == "/api/bulbs/register":
            try:
                req = json.loads(body.decode("utf-8"))
            except ValueError:
                self._json({"error": "invalid json"}, 400)
                return
            mac = req.get("mac")
            if not mac:
                self._json({"error": "mac required"}, 400)
                return
            store.update(mac, ip=req.get("ip"))
            store.save()
            self._json({"registered": mac})
            return

        if path == "/api/rooms":
            try:
                req = json.loads(body.decode("utf-8"))
            except ValueError:
                self._json({"error": "invalid json"}, 400)
                return
            self._json(rooms.create(str(req.get("name", ""))))
            return

        seg = [p for p in path.split("/") if p]
        if len(seg) == 4 and seg[0] == "api" and seg[1] == "rooms" and seg[3] == "power":
            name = unquote(seg[2])
            try:
                req = json.loads(body.decode("utf-8"))
            except ValueError:
                req = {}
            on = bool(req.get("on", False))
            macs = rooms.macs_of(name)
            for m in macs:
                api.request(m, "power", on)
            self._json({"room": name, "on": on, "bulbs": macs})
            return

        if len(seg) == 4 and seg[0] == "api" and seg[1] == "bulbs" and seg[3] == "update":
            mac = unquote(seg[2])
            try:
                req = json.loads(body.decode("utf-8"))
            except ValueError:
                self._json({"error": "invalid json"}, 400)
                return
            applied = []
            if "power" in req:
                api.request(mac, "power", bool(req["power"]))
                applied.append("power")
            if "brightness" in req:
                api.request(mac, "brightness", int(req["brightness"]))
                applied.append("brightness")
            if "color" in req:
                c = req["color"]
                if isinstance(c, dict):
                    api.request(mac, "color", (int(c.get("r", 0)), int(c.get("g", 0)), int(c.get("b", 0))))
                    applied.append("color")
            if "colortemp" in req:
                api.request(mac, "colortemp", int(req["colortemp"]))
                applied.append("colortemp")
            if not applied:
                self._json({"error": "nothing to update"}, 400)
                return
            self._json({"mac": mac, "batched": applied})
            return

        if len(seg) == 4 and seg[0] == "api" and seg[1] == "rooms" and seg[3] == "assign":
            name = unquote(seg[2])
            try:
                req = json.loads(body.decode("utf-8"))
            except ValueError:
                self._json({"error": "invalid json"}, 400)
                return
            mac = req.get("mac")
            if not mac:
                self._json({"error": "mac required"}, 400)
                return
            result = rooms.assign(name, mac)
            code = 200 if "error" not in result else 404
            self._json(result, code)
            return

        if len(seg) == 4 and seg[0] == "api" and seg[1] == "bulbs" and seg[3] == "name":
            mac = unquote(seg[2])
            try:
                req = json.loads(body.decode("utf-8"))
            except ValueError:
                self._json({"error": "invalid json"}, 400)
                return
            name = str(req.get("name", "")).strip()
            if not name:
                self._json({"error": "name required"}, 400)
                return
            store.update(mac, name=name)
            store.save()
            self._json({"named": mac, "name": name})
            return

        self._json({"error": "not found"}, 404)

    def do_DELETE(self):
        path = self.path.split("?")[0]
        rooms = self.server.rooms
        seg = [p for p in path.split("/") if p]
        if len(seg) == 3 and seg[0] == "api" and seg[1] == "rooms":
            self._json(rooms.delete(unquote(seg[2])))
            return
        self._json({"error": "not found"}, 404)


def main():
    parser = argparse.ArgumentParser(description="Sengled local replacement server")
    parser.add_argument("--certs", default="./certs", help="directory with ca.crt/server.crt/server.key")
    parser.add_argument("--http-port", type=int, default=DEFAULT_HTTP_PORT)
    parser.add_argument("--mqtt-port", type=int, default=DEFAULT_MQTT_PORT)
    parser.add_argument("--lan-ip", default=None, help="LAN IP advertised to bulbs (default: autodetect)")
    parser.add_argument("--state-file", default="bulbs.json")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    certs_dir = os.path.abspath(args.certs)
    for f in ("server.crt", "server.key"):
        if not os.path.isfile(os.path.join(certs_dir, f)):
            print(f"missing {f} in {certs_dir} — run ./setup_certs.sh <lan-ip> first", file=sys.stderr)
            sys.exit(1)

    lan_ip = args.lan_ip or get_lan_ip()
    cfg = {"lan_ip": lan_ip, "mqtt_port": args.mqtt_port}

    store = BulbStore(args.state_file)
    rooms_path = os.path.join(os.path.dirname(os.path.abspath(args.state_file)), "rooms.json")
    rooms = RoomsStore(rooms_path, store)
    core = BrokerCore()
    api = ControlAPI(core, store, verbose=args.verbose)
    hub = EventHub()
    core.local_watchers.append((status_watcher(store, api, hub), "wifielement/+/status"))

    broker = MQTTBroker(core, certs_dir, args.mqtt_port, verbose=args.verbose)
    broker.start()
    broker.ready.wait(5)
    if broker.error:
        print(broker.error, file=sys.stderr)
        sys.exit(1)

    class Server(ThreadingHTTPServer):
        daemon_threads = True

    httpd = Server(("0.0.0.0", args.http_port), Handler)
    httpd.api = api
    httpd.store = store
    httpd.rooms = rooms
    httpd.hub = hub
    httpd.cfg = cfg
    httpd.started_at = time.time()

    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    print("Sengled local server running")
    print(f"  LAN IP (advertised to bulbs): {lan_ip}")
    print(f"  MQTT broker (TLS):            0.0.0.0:{args.mqtt_port}")
    print(f"  HTTP bootstrap + REST API:    0.0.0.0:{args.http_port}")
    print(f"  State file:                   {os.path.abspath(args.state_file)}")
    print()
    print(f"  Pair the bulb with:  python3 pair_bulb.py --server-host {lan_ip}")
    print(f"  REST examples:")
    print(f"    curl http://{lan_ip}:{args.http_port}/api/bulbs")
    print(f"    curl -X POST http://{lan_ip}:{args.http_port}/api/bulbs/<MAC>/power -d '{{\"on\": true}}'")
    print(f"    curl -X POST http://{lan_ip}:{args.http_port}/api/bulbs/<MAC>/brightness -d '{{\"value\": 50}}'")
    print(f"    curl -X POST http://{lan_ip}:{args.http_port}/api/bulbs/<MAC>/color -d '{{\"r\":255,\"g\":0,\"b\":0}}'")

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        log("shutting down")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Pair a factory-reset Sengled Wi-Fi bulb to your local replacement server.

Run this ON a device connected to the bulb's access point (bulb AP at
192.168.8.1) — a laptop or Android with Termux both work. Python 3 only,
no dependencies.

Before you start:
  1. sengled_server.py must be running on --server-host (the rig).
  2. Connect this device to the bulb's Wi-Fi AP network.
  3. Run:  python3 pair_bulb.py --server-host <rig-LAN-IP>

Flow (per SengledTools, verified on W31 bulbs):
  startConfigRequest -> [scan -> pick network] -> setParamsRequest
  (RC4+base64 encrypted, carries Wi-Fi creds + your server URLs)
  -> endConfigRequest. The bulb then joins your home Wi-Fi and calls
  the server's bootstrap endpoints on its own.
"""

import argparse
import base64
import json
import socket
import sys
import time
from getpass import getpass

BULB_IP = "192.168.8.1"
BULB_PORT = 9080
KEY_STR = "MTlCaWppbmdTaGFuZ2hhaVdpU2VuZ2xlZEZpMjBBQUJBU0U2NA=="


def rc4(data: bytes, key: bytes) -> bytes:
    s = list(range(256))
    j = 0
    for i in range(256):
        j = (j + s[i] + key[i % len(key)]) % 256
        s[i], s[j] = s[j], s[i]
    out = bytearray()
    i = j = 0
    for byte in data:
        i = (i + 1) % 256
        j = (j + s[i]) % 256
        s[i], s[j] = s[j], s[i]
        out.append(byte ^ s[(s[i] + s[j]) % 256])
    return bytes(out)


def encrypt_payload(obj) -> str:
    text = obj if isinstance(obj, str) else json.dumps(obj)
    return base64.b64encode(rc4(text.encode("utf-8"), KEY_STR.encode("utf-8"))).decode("utf-8")


def decrypt_payload(text: str):
    return json.loads(rc4(base64.b64decode(text), KEY_STR.encode("utf-8")).decode("utf-8"))


def udp_roundtrip(sock, payload, timeout=5.0) -> str:
    sock.settimeout(timeout)
    wire = payload if isinstance(payload, str) else json.dumps(payload)
    sock.sendto(wire.encode("utf-8"), (BULB_IP, BULB_PORT))
    data, _ = sock.recvfrom(4096)
    return data.decode("utf-8")


def parse_response(text):
    try:
        return json.loads(text)
    except ValueError:
        return decrypt_payload(text)


def main():
    parser = argparse.ArgumentParser(description="Pair a Sengled bulb to your local server")
    parser.add_argument("--server-host", required=True,
                        help="LAN IP of the machine running sengled_server.py")
    parser.add_argument("--http-port", type=int, default=57542)
    parser.add_argument("--user-id", default="618")
    parser.add_argument("--tz", default=None, help="time zone name (default: system)")
    args = parser.parse_args()

    tz = args.tz or time.tzname[0] or "UTC"
    http_base = f"http://{args.server_host}:{args.http_port}"
    app_server_domain = f"{http_base}/life2/device/accessCloud.json"
    jbalancer_domain = f"{http_base}/jbalancer/new/bimqtt"

    print(f"server URLs:\n  {app_server_domain}\n  {jbalancer_domain}")
    print(f"connect this device to the bulb's Wi-Fi AP, then press Enter...")
    input()

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        print("[1/5] handshake...")
        req = {"name": "startConfigRequest", "totalStep": 1, "curStep": 1, "payload": {"protocol": 1}}
        resp = parse_response(udp_roundtrip(sock, req))
        mac = resp.get("payload", {}).get("mac")
        if not mac or len(mac) != 17:
            print(f"handshake failed: {resp}", file=sys.stderr)
            sys.exit(1)
        print(f"      bulb MAC: {mac}")
        time.sleep(1)

        print("[2/5] scanning Wi-Fi networks via the bulb...")
        scan = {"name": "scanWifiRequest", "totalStep": 1, "curStep": 1, "payload": {}}
        sock.sendto(json.dumps(scan).encode("utf-8"), (BULB_IP, BULB_PORT))
        time.sleep(5)
        ap_req = {"name": "getAPListRequest", "totalStep": 1, "curStep": 1, "payload": {}}
        resp = parse_response(udp_roundtrip(sock, ap_req))
        routers = resp.get("payload", {}).get("routers", [])
        if not routers:
            print("no networks found — rescan? (empty = give up)", file=sys.stderr)
            sys.exit(1)

        print("[3/5] pick your home network:")
        for i, r in enumerate(routers):
            ssid = r.get("ssid", "")
            signal = int(r.get("signal", 0) or 0)
            bars = "▂▄▆█"[: max(1, min(signal, 4))]
            print(f"  [{i + 1}] {ssid}  {bars}")
        while True:
            try:
                choice = int(input("network #> ").strip())
            except ValueError:
                continue
            if 1 <= choice <= len(routers):
                break
        ssid = routers[choice - 1].get("ssid", "")
        password = getpass(f"password for '{ssid}'> ")

        ascii_ok = all(ord(c) < 128 for c in ssid)
        router_info = {"ssid": ssid, "password": password} if ascii_ok else {
            "ssid": "", "bssid": routers[choice - 1].get("bssid", "").upper(), "password": password
        }

        print("[4/5] sending encrypted config (Wi-Fi + your server URLs)...")
        params = {
            "name": "setParamsRequest",
            "totalStep": 1,
            "curStep": 1,
            "payload": {
                "userID": args.user_id,
                "appServerDomain": app_server_domain,
                "jbalancerDomain": jbalancer_domain,
                "timeZone": tz,
                "routerInfo": router_info,
            },
        }
        blob = encrypt_payload(params)
        try:
            raw = udp_roundtrip(sock, blob, timeout=8.0)
            ack = parse_response(raw)
            result = ack.get("payload", {}).get("result")
            if result is not True:
                print(f"bulb rejected config: {ack}", file=sys.stderr)
                sys.exit(1)
        except socket.timeout:
            print("      (no ack — common; bulb is joining your Wi-Fi)")

        print("[5/5] endConfig...")
        end_req = {"name": "endConfigRequest", "totalStep": 1, "curStep": 1, "payload": {}}
        try:
            udp_roundtrip(sock, end_req, timeout=2.0)
        except socket.timeout:
            pass

    print(f"DONE. Bulb {mac} is joining your Wi-Fi.")
    print(f"Watch the server log — within ~30s you should see:")
    print(f"  'accessCloud.json ... from <bulb-ip>'  then  'bimqtt ... from <bulb-ip>'")
    print(f"Then register it:  curl -X POST http://{args.server_host}:57542/api/bulbs/register "
          f"-d '{{\"mac\": \"{mac}\"}}'")


if __name__ == "__main__":
    main()

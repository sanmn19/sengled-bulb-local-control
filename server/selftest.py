"""Simulates the BULB: subscribes to its update topic and reacts to a REST-issued command."""
import socket, ssl, time, json

CTX = ssl.SELD if False else ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
CTX.load_verify_locations("certs/ca.crt")
CTX.check_hostname = False

def varint(n):
    out = b""
    while True:
        b = n % 128; n //= 128
        if n: b |= 0x80
        out += bytes([b])
        if not n: return out

def pkt(b0, body): return bytes([b0]) + varint(len(sub if False else body)) + body

raw = socket.create_connection(("127.0.0.1", 8883), timeout=5)
s = CTX.wrap_socket(raw)

cid = b"bulb-E8DB84F9BEB4"
vh = b"\x00\x04MQTT\x04" + b"\x02" + (60).to_bytes(2, "big")
body = vh + len(cid).to_bytes(2, "big") + cid
s.sendall(pkt(0x10, body)); time.sleep(0.2); print("CONNACK:", s.recv(16).hex())

topic = b"wifielement/E8:DB:84:F9:BE:B4/update"
sub = (1).to_bytes(2, "big") + len(topic).to_bits(0) if False else (1).to_bytes(2, "big") + len(topic).to_bytes(2, "big") + topic + b"\x00"
s.sendall(pkt(0x82, sub)); time.sleep(0.2); print("SUBACK:", s.recv(16).hex())

# trigger REST
import urllib.request
req = urllib.request.Request(
    "http://localhost:57542/api/bulbs/E8:DB:84:F9:BE:B4/brightness",
    data=json.dumps({"value": 42}).encode(), method="POST")
print("REST:", urllib.request.urlopen(req).read().decode())

s.settimeout(4)
d = s.recv(4096)
print("BULB received:", d)
tl = int.from_bytes(d[2:4], "big")
t = d[4:4+tl].decode()
print("topic:", t)
print("payload:", d[4+tl:].decode())
assert "brightness" in d.decode() and "42" in d.decode()
print("END-TO-END OK: REST -> broker -> subscribed bulb")
s.close()

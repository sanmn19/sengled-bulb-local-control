# Sengled Bulb Local Control

A fully local, self-hosted replacement server + Android client for Sengled
Wi-Fi bulbs, built after Sengled's cloud backend shut down. No cloud, no
accounts: your bulbs talk straight to a server you run on your own LAN.

## What's here

```
server/  — drop-in replacement cloud (Python 3 standard library only)
  sengled_server.py   HTTP bootstrap + MQTT/TLS broker + REST control API +
                      UDP fallback + rooms API + event feed
  pair_bulb.py        standalone pairing tool (run from Termux or a laptop)
  selftest.py         end-to-end bridge verification
  gen-certs.sh        CA + broker TLS certificate generator
client/  — Android client (theme matches the official app, own logo)
  SengledLocal/       Gradle project: bulb list grouped by rooms, per-bulb
                      power/brightness/color/color-temperature, in-app pairing
                      (automatic AP switch + manual fallback), room toggles,
                      offline cache, event-driven status sync
```

## How it works (see PROTOCOL.md for the full protocol)

- The bulb is configured during pairing with the URLs of your own server —
  it then calls `accessCloud.json` + `jbalancer/new/bimqtt` and connects to
  your MQTT broker on TLS 8883.
- Control commands are published to `wifielement/<MAC>/update`; bulb state
  echoes arrive on `wifielement/<MAC>/status` — the server verifies every
  command closed-loop and retries over UDP on missing echoes.
- The app talks REST to the server over LAN or Tailscale (host failover built
  in) and gets instant state updates via server-sent long-poll events.

## Quick start

1. `cd server && ./gen-certs.sh <your-LAN-IP> certs`
2. `python3 sengled_server.py --certs certs`
3. Pair the bulb (factory reset first: flip the wall switch OFF-ON 5 times
   quickly) with `python3 pair_bulb.py --server-host <your-LAN-IP>` from any
   device on the bulb's hotspot — or pair from the app once installed.
4. Build the client in Android Studio and set the server address in Settings.

## Notes

- **No license granted** — personal use for now; check local law/ ToS before
  any redistribution.
- The repo intentionally excludes extracted assets from the official Sengled
  APK (logo substituted with a project-drawn vector) and all personal
  configuration (server addresses, MACs, names).
- Your own data files (`bulbs.json`, `rooms.json`, `certs/`) are gitignored.

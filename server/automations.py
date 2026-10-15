"""Automation subsystem: upcoming-friendly triggers -> bulb actions.

Triggers:
  daily    {"time": "HH:MM"}                       — every day (server timezone)
  sunrise  {"offsetMinutes": -30}                  — computed per date (season-aware)
  sunset   {"offsetMinutes": 45}
  interval {"minutes": 30}                        — every N minutes

Actions: list of:
  {"macs": ["<MAC>", ...], "action": "power"|"brightness"|"colortemp"|"color",
   "mode": "set"|"offset",
   "value": 60 | [r,g,b] | {"dr":..,"dg":..,"db":..} for color offsets}

Relative actions resolve against the bulb's best-known state at FIRE TIME.
Location (for sunrise/sunset) lives in a private location.json; nothing
private is stored in code.
"""
import json
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone

try:
    from zoneinfo import ZoneInfo
except ImportError:
    ZoneInfo = None

from sun import sun_times_utc


class BadAutomation(Exception):
    pass


class AutomationsStore:
    def __init__(self, path, location_path):
        self.path = path
        self.location_path = location_path
        self.lock = threading.Lock()
        self.items = []
        self.location = {}
        self._load()

    def _load(self):
        try:
            with open(self.path) as f:
                data = json.load(f)
            if isinstance(data, dict):
                for e in data.get("automations", []):
                    if isinstance(e, dict) and e.get("id"):
                        e.setdefault("enabled", True)
                        e.setdefault("state", {})
                        self.items.append(e)
        except (OSError, ValueError):
            self.items = []
        try:
            with open(self.location_path) as f:
                loc = json.load(f)
            if isinstance(loc, dict):
                self.location = loc
        except (OSError, ValueError):
            self.location = {}

    def save(self):
        with self.lock:
            try:
                with open(self.path, "w") as f:
                    json.dump({"automations": self.items}, f, indent=2)
            except OSError as e:
                print(f"[automations] save failed: {e}")
            try:
                with open(self.location_path, "w") as f:
                    json.dump(self.location, f, indent=2)
            except OSError:
                pass

    def snapshot(self):
        with self.lock:
            items = json.loads(json.dumps(self.items))
        return {"automations": items, "location": dict(self.location)}

    def _validate_trigger(self, trigger):
        t = dict(trigger or {})
        kind = t.get("type")
        if kind not in ("daily", "sunrise", "sunset", "interval"):
            raise BadAutomation("trigger.type must be daily|sunrise|sunset|interval")
        if kind == "daily":
            hh, mm = t.get("time", "").split(":")
            if not (0 <= int(hh) <= 23 and 0 <= int(mm) <= 59):
                raise BadAutomation("daily time must be HH:MM")
            t["time"] = f"{int(hh):02d}:{int(mm):02d}"
        elif kind == "interval":
            m = int(t.get("minutes", 0))
            if m < 1:
                raise BadAutomation("interval minutes must be >= 1")
            t["minutes"] = m
        else:
            off = int(t.get("offsetMinutes", 0))
            t["offsetMinutes"] = max(-180, min(180, off))
        t["type"] = kind
        return t

    ACTIONS = ("power", "brightness", "colortemp", "color")
    MODES = ("set", "offset")
    OFFSET_ACTIONS = ("brightness", "colortemp", "color")

    def _validate_actions(self, actions):
        if not isinstance(actions, list) or not actions:
            raise BadAutomation("actions must be a non-empty list")
        out = []
        for a in actions:
            macs = a.get("macs")
            if not isinstance(macs, list) or not macs:
                raise BadAutomation("each action needs macs: [..]")
            if a.get("action") not in self.ACTIONS:
                raise BadAutomation(f"action must be one of {self.ACTIONS}")
            mode = a.get("mode", "set")
            if mode not in self.MODES:
                raise BadAutomation("mode must be set|offset")
            if mode == "offset" and a.get("action") not in self.OFFSET_ACTIONS:
                raise BadAutomation(f"offset mode not available for {a.get('action')}")
            value = a.get("value")
            if a["action"] in ("brightness", "colortemp") and not isinstance(value, (int, float)):
                raise BadAutomation("numeric value required")
            if mode == "offset":
                value = int(value) if isinstance(value, (int, float)) else value
                if a["action"] == "color" and not (isinstance(value, dict) and set(value.keys()) <= {"dr", "dg", "db"}):
                    raise BadAutomation("color offset needs {dr,dg,db}")
            elif a["action"] == "color" and not (isinstance(value, dict) and all(k in value for k in ("r", "g", "b"))):
                raise BadAutomation("color set needs {r,g,b}")
            out.append({"macs": [str(m) for m in macs], "action": a["action"],
                        "mode": mode, "value": value})
        return out

    def create(self, label, trigger, actions):
        entry = {
            "id": uuid.uuid4().hex[:8],
            "label": str(label or "")[:60] or "Automation",
            "enabled": True,
            "trigger": self._validate_trigger(trigger),
            "actions": self._validate_actions(actions),
            "state": {},
        }
        with self.lock:
            self.items.append(entry)
        self.save()
        return entry

    def delete(self, automation_id):
        with self.lock:
            before = len(self.items)
            self.items = [e for e in self.items if e.get("id") != automation_id]
        if len(self.items) == before:
            raise BadAutomation("no such automation")
        self.save()
        return {"deleted": automation_id}

    def set_enabled(self, automation_id, on):
        with self.lock:
            for e in self.items:
                if e.get("id") == automation_id:
                    e["enabled"] = bool(on)
                    self.save()
                    return {"id": automation_id, "enabled": bool(on)}
        raise BadAutomation("no such automation")

    def set_location(self, lat, lon, tz=None):
        self.location = {"lat": float(lat), "lon": float(lon)}
        if tz:
            self.location["tz"] = str(tz)
        self.save()
        return dict(self.location)


def _local_now(location):
    tzname = (location or {}).get("tz")
    tz = None
    if ZoneInfo is not None and tzname:
        try:
            tz = ZoneInfo(tzname)
        except Exception:
            tz = None
    return datetime.now(tz) if tz else datetime.now()


def resolve_offset(control, mac, action, mode, value):
    """Resolve a relative action against the bulb's best-known current state."""
    if mode != "offset":
        return value
    attrs = (control.store.get(mac) or {}).get("attributes") or {}
    if action == "brightness":
        base = control.last_brightness.get(mac)
        if base is None:
            try:
                base = int(attrs.get("brightness"))
            except (TypeError, ValueError):
                base = 100
        return max(0, min(100, int(base) + int(value)))
    if action == "colortemp":
        base = control.last_colortemp.get(mac)
        if base is None:
            try:
                base = int(attrs.get("colorTemperature"))
            except (TypeError, ValueError):
                base = 40
        return max(0, min(100, int(base) + int(value)))
    if action == "color":
        base = control.last_color.get(mac)
        if base is None:
            raw = attrs.get("color")
            try:
                base = [int(x) for x in str(raw).split(":")]
            except (TypeError, ValueError, AttributeError):
                base = [255, 255, 255]
        base = (base + [255, 255, 255])[:3]
        target = value or {}
        out = []
        for i, dv in enumerate((target.get("dr", 0), target.get("dg", 0), target.get("db", 0))):
            out.append(max(0, min(255, int(base[i]) + int(dv))))
        return out
    return value


class AutomationRunner(threading.Thread):
    TICK_S = 30

    def __init__(self, store_, control_api):
        super().__init__(daemon=True, name="sengled-automations")
        self.store = store_
        self.control = control_api
        self.cached_since = None
        self.cached_event = None

    def stop(self):
        self.alive = False

    def run(self):
        self.alive = True
        while self.alive:
            try:
                self.tick()
            except Exception as e:
                print(f"[automations] tick error: {e}")
            time.sleep(self.TICK_S)

    def _sun_event_hours_utc(self, kind, day):
        loc = self.store.location or {}
        lat = loc.get("lat")
        lon = loc.get("lon")
        if lat is None or lon is None:
            return None
        rise, set_ = sun_times_utc(day.year, day.month, day.day, float(lat), float(lon))
        return rise if kind == "sunrise" else set_

    def _trigger_due(self, entry, local_now, today):
        trig = entry["trigger"]
        kind = trig["type"]
        state = entry.get("state") or {}
        last_day = state.get("last_day")
        if kind in ("daily", "sunrise", "sunset"):
            if last_day == today.isoformat():
                return False
            if kind == "daily":
                hh, mm = trig["time"].split(":")
                target = local_now.replace(hour=int(hh), minute=int(mm), second=0, microsecond=0)
            else:
                hours_utc = self._sun_event_hours_utc(kind, today)
                if hours_utc is None:
                    return False  # no location or polar day
                tzname = (self.store.location or {}).get("tz")
                tz = None
                if ZoneInfo is not None and tzname:
                    try:
                        tz = ZoneInfo(tzname)
                    except Exception:
                        tz = None
                base_day = local_now.date()
                day_utc = datetime(base_day.year, base_day.month, base_day.day, tzinfo=timezone.utc)
                event_utc = day_utc + timedelta(hours=hours_utc)
                target = event_utc + timedelta(minutes=int(trig.get("offsetMinutes", 0)))
                if tz:
                    target = target.astimezone(tz)
                target = target.replace(second=0, microsecond=0)
            return local_now >= target
        if kind == "interval":
            last = state.get("last_fired", 0)
            return (time.time() - float(last)) >= int(trig.get("minutes", 60)) * 60
        return False

    def tick(self):
        now_dt = _local_now(self.store.location)
        today = now_dt.date()
        with self.store.lock:
            entries = list(self.store.items)
        for entry in entries:
            if not entry.get("enabled"):
                continue
            try:
                if self._trigger_due(entry, now_dt, today):
                    self.fire(entry, now_dt, today)
            except Exception as e:
                print(f"[automations] evaluate {entry.get('id')} failed: {e}")

    def fire(self, entry, now_dt=None, today=None):
        if now_dt is None:
            now_dt = _local_now(self.store.location)
            today = now_dt.date()
        for act in entry.get("actions", []):
            for mac in act.get("macs", []):
                value = resolve_offset(self.control, mac, act["action"], act["mode"], act.get("value"))
                self.control.request(mac, act["action"], value)
        entry.setdefault("state", {})
        entry["state"]["last_day"] = today.isoformat()
        entry["state"]["last_fired"] = time.time()
        self.store.save()
        return True

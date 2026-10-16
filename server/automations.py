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
        seen_actions = set()
        for a in actions:
            if a.get("action") in seen_actions:
                raise BadAutomation(f"conflicting duplicate action'{ a.get('action') }'")
            if (a.get("action") == "color" and "colortemp" in seen_actions) or \
                    (a.get("action") == "colortemp" and "color" in seen_actions):
                raise BadAutomation("color and white temperature conflict in one automation")
            seen_actions.add(a.get("action"))
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
            if a["action"] in ("brightness", "colortemp"):
                if not isinstance(value, (int, float)):
                    raise BadAutomation("numeric value required")
                if mode == "offset":
                    if not (-100 <= int(value) <= 100):
                        raise BadAutomation(f"{a['action']} offset must be -100..100")
                elif not (0 <= int(value) <= 100):
                    raise BadAutomation(f"{a['action']} set must be 0..100")
            if mode == "offset":
                value = int(value) if isinstance(value, (int, float)) else value
                if a["action"] == "color":
                    if not (isinstance(value, dict) and set(value.keys()) <= {"dr", "dg", "db"}):
                        raise BadAutomation("color offset needs {dr,dg,db}")
                    for k in ("dr", "dg", "db"):
                        v = int(value.get(k, 0))
                        if v < -255 or v > 255:
                            raise BadAutomation("color delta channels must be within -255..255")
            elif a["action"] == "color" and not (isinstance(value, dict) and all(k in value for k in ("r", "g", "b"))):
                raise BadAutomation("color set needs {r,g,b}")
            out.append({"macs": [str(m) for m in macs], "action": a["action"],
                        "mode": mode, "value": value})
        return out

    def create(self, label, trigger, actions):
        with self.lock:
            existing = len(self.items)
        nice_label = str(label or "").strip()[:60]
        if not nice_label:
            nice_label = f"Automation {existing + 1}"
        entry = {
            "id": uuid.uuid4().hex[:8],
            "label": nice_label,
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

    def _sun_event_local(self, kind, day):
        """Local naive datetime for the sun event on LOCAL day `day`.

        The UTC-hour value from the almanac is anchored ambiguously: an
        evening event for a western longitude lands in the NEXT UTC day,
        an eastern one stays on the same UTC day. Try both candidates and
        accept the one whose local calendar date matches `day`."""
        hours_utc = self._sun_hours(kind, day)
        if hours_utc is None:
            return None
        tzname = (self.location or {}).get("tz")
        tz = None
        if ZoneInfo is not None and tzname:
            try:
                tz = ZoneInfo(tzname)
            except Exception:
                tz = None
        day_utc0 = datetime(day.year, day.month, day.day, tzinfo=timezone.utc)
        fallback = None
        for add in (0, 24):
            t = day_utc0 + timedelta(hours=hours_utc + add)
            t_local = t.astimezone(tz) if tz else t
            naive = t_local.replace(tzinfo=None)
            if naive.date() == day:
                return naive
            if fallback is None:
                fallback = naive
        return fallback

    def next_run_minutes(self, entry, now_local):
        """Minutes until the next trigger (sun events compute per-date)."""
        trig = entry.get("trigger") or {}
        kind = trig.get("type")
        if kind == "daily":
            hh, mm = [int(x) for x in trig.get("time", "00:00").split(":")]
            target = now_local.replace(hour=hh, minute=mm, second=0, microsecond=0)
            if target <= now_local:
                target += timedelta(days=1)
            return int((target - now_local).total_seconds() // 60)
        if kind in ("sunrise", "sunset"):
            for shift in (0, 1):
                day = (now_local + timedelta(days=shift)).date()
                ev = self._sun_event_local(kind, day)
                if ev is None:
                    return None
                target = ev + timedelta(minutes=int(trig.get("offsetMinutes", 0)))
                if target > now_local:
                    return int((target - now_local).total_seconds() // 60)
            return None
        if kind == "interval":
            state = entry.get("state") or {}
            last = state.get("last_fired", 0)
            if not last:
                return 0  # fires on the next ticker pass
            period = int(trig.get("minutes", 60)) * 60
            remaining = float(last) + period - time.time()
            return max(0, int(remaining // 60))
        return None

    def _sun_hours(self, kind, day):
        loc = self.location or {}
        lat = loc.get("lat")
        lon = loc.get("lon")
        if lat is None or lon is None:
            return None
        rise, set_ = sun_times_utc(day.year, day.month, day.day, float(lat), float(lon))
        return rise if kind == "sunrise" else set_

    def _utc_hours_to_local(self, hours_utc, day):
        tzname = (self.location or {}).get("tz")
        tz = None
        if ZoneInfo is not None and tzname:
            try:
                tz = ZoneInfo(tzname)
            except Exception:
                tz = None
        day_utc = datetime(day.year, day.month, day.day, tzinfo=timezone.utc)
        t = day_utc + timedelta(hours=hours_utc)
        return t.astimezone(tz).replace(tzinfo=None) if tz else t.replace(tzinfo=None)

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
    return datetime.now(tz).replace(tzinfo=None) if tz else datetime.now()


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
                event_local = self._sun_event_local(kind, today)
                if event_local is None:
                    return False  # no location or polar day
                target = event_local + timedelta(minutes=int(trig.get("offsetMinutes", 0)))
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

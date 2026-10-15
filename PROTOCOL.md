# Sengled Protocol Reference

Reverse-engineered from `Sengled+Home_2.8.3_APKPure.apk` (SHA-256
`edcc869481d64b475c900c3b016c772c0c7c2016dcd47aa5df217d1269a8f6a9`).

**Evidence provenance:** all app-side findings below are static observations from
`assets/index.android.bundle` (React Native JS bundle, minified) and
`classes*.dex` strings, extracted to `evidence/apk-extracted/`. They are
**not** REA Evidence (no REA MCP/JADX provider was available) and have not been
verified by runtime capture yet. Bulb-side findings are corroborated by
`reference/SengledTools` (built from a decompiled app) and
`reference/ha-sengledapi`.

## Architecture

```
App ──HTTPS JSON──> ucenter (auth) ─┐
App ──HTTPS JSON──> life2   (server info, wifi device list, rooms, scenes)
App ──HTTPS JSON──> element (zigbee/hub device details)
App ──MQTT/WS─────> inception (status subscription)   ← addr from getServerInfo
Bulb <──MQTT/TLS 8883──> jbalancer-issued broker      ← addr from /jbalancer/new/bimqtt
App ──UDP 9080───> Bulb (pairing, LAN config, LAN control)
```

## 1. App → Cloud (HTTPS, JSON)

Default base URLs (bundle constants, `WebUrl` module):

| Service | Default base URL |
|---|---|
| ucenter | `https://ucenter.cloud.sengled.com:443/user/` |
| life | `https://life2.cloud.sengled.com:443/life2/` |
| element | `https://elements.cloud.sengled.com:443/zigbee/` |
| oauth2 | `https://us-openapi.cloud.sengled.com:443/oauth2/` |

Region variants exist (`cn-`, `jp-`, `us-` prefixed hosts, plus
`us1-*-test` / `jx1-*-dev/test` environments).

### Endpoint catalog (full list from bundle)

- Auth: `app/customer/AuthenCross.json`, `app/customer/v3/AuthenCross.json`
  (also `?vtype=1` encoded variant), `app/customer/register.json`,
  `app/customer/getVerificationCode.json`,
  `app/customer/checkVerificationCode.json`,
  `app/customer/sendAccountVerifyRequest.json`,
  `app/customer/updateUserPassword.json`,
  `app/customer/validateEmail.json`,
  `app/customer/verifyRandomActivationCode.json`,
  `app/customer/setAccountPromotionStatus.json`
- Customer: `customer/getUserInfo.json`, `customer/getUserInfoV2.json`,
  `customer/logout.json`, `customer/modifyPassword.json`,
  `customer/modifyUserInfo.json`, `customer/uploadUserProfile.json`,
  `customer/checkAccount.json`, `customer/deleteCustomerInfo.json`,
  `cloud/deleteAccount.json`
- Server: `server/getServerInfo.json`,
  `server/setPushNotificationOnOff.json`,
  `server/getPushNotificationOnOffList.json`
- Device (life2): `device/list.json` (WiFi devices; ha-sengledapi),
  `device/getDeviceDetails.json`, `device/ble/list.json`,
  `device/addDevicesSuccess.json`, `device/deleteDevicesSuccess.json`,
  `device/deleteDevicesSuccessNew.json`, `device/addHubAndDevicesSuccess.json`,
  `device/applyAddDevices.json`, `device/gatewayDelFromUser.json`,
  `device/getGatewayIsOnline.json`, `device/configSwitch.json`,
  `device/getSwitchConfig.json`, `device/resetSwitch.json`,
  `device/getAttributesForCostDown.json`,
  `device/getMnCode.json`, `device/queryTvPodsUrl.json`,
  `device/setPar38*.json` (4 endpoints), `device/audio/sync/modifyPreferencesAudioTheme.json`
- Gateway: `gateway/getToken.json`, `gateway/getGatewayAddDeviceType.json`,
  `gateway/addHubAndDevicesSuccess*.json`, `gateway/gatewayDelFromUser.json`,
  `gateway/updateAllHubs.json`, `gateway/minUsageVersionCheck.json`,
  `gateway/upgradeCheck.json`, `gateway/updateHub.json`
- Rooms / scenes / schedules: `room/*`, `room/v2/*`, `scene/*`,
  `schedule/v2/list.json`, `schedule/setScheduleOnoff.json`,
  `schedule/updateSetting.json`
- Firmware: `update/getUserFirmwareUpdateInfos.json`,
  `update/updateFirmwareByHub.json`, `openapi/getFirmwareInfos.json`,
  `hotUpdate/androidPackageDescription.txt`
- Misc: `aws/api/setTimeZone.json`, `aws/api/accountLinkFromAlexa`,
  `grayscaleupgrade/markGrayscaleUpgrade.json`, `transfer/isNeed.json`,
  `transfer/removalRoomInfo.json`

### Login

`POST {ucenter}/app/customer/v3/AuthenCross.json` — payload
(observed in ha-sengledapi, matches bundle error-handling paths):

```json
{
  "uuid": "<random device id>",
  "user": "<email>",
  "pwd": "<password>",
  "osType": "android",
  "productCode": "life",
  "appCode": "life"
}
```

Response contains `jsessionId` (session token, sent on subsequent requests).
Error `messageCode` 2 = wrong password / lock, 3 = account not exist.

### getServerInfo (the pivot endpoint)

`POST {life}/server/getServerInfo.json` → `result` fields consumed by the app:

- `jbalancerAddr` — URL handed to **bulbs** during pairing (and Matter setup);
  bulb POSTs to it to learn its MQTT broker
- `inceptionAddr` — MQTT-over-WebSocket URL the **app** connects to for status
- `appServerAddr` — base the app appends `device/accessCloud.json` to for bulb pairing
- `storeInAppOnOff`, `bleNotSupportFlag`, `opsMgtUrl`

### Device state model

`POST {element}/device/getDeviceDetails.json` →
`deviceInfos[].lampInfos[].attributes` with keys `onOff`, `brightness`,
`colorTemperature`, `color`, `isOnline`/`online`. The app caches this and
patches attributes locally on control.

## 2. App status channel (MQTT over WebSocket — "inception")

Bundle embeds **Paho.MQTT.Client** (WebSocket transport). App connects to
`serverInfo.inceptionAddr` with client id `<sessionId>@lifeApp` and subscribes:

- `<username-lowercase>/<deviceAddress>/status` — per-device status pushes
- `ucenter/<sessionId>/action`
- `customer/<userId>/<SLConstants.RATE_IOT>`

The app **publishes nothing** over MQTT; control is HTTPS or LAN UDP.

## 3. Bulb pairing (AP mode, UDP 9080)

Sequence (bundle + `reference/SengledTools/docs/references/WIFI_PAIRING_REFERENCE.md`):

1. Bulb in AP mode at `192.168.8.1`, UDP port `9080`
2. `{"name":"startConfigRequest",...}` handshake → bulb replies with MAC
3. Optional `scanWifiRequest` / `getAPListRequest`
4. `setParamsRequest` (encrypted when `protocol != 0`):

```json
{
  "name": "setParamsRequest",
  "totalStep": 1,
  "curStep": 1,
  "payload": {
    "userID": "<userId>",
    "appServerDomain": "<appServerAddr>device/accessCloud.json",
    "jbalancerDomain": "<jbalancerAddr>",
    "timeZone": "<tz>",
    "routerInfo": { "ssid": "...", "bssid": "...", "password": "..." }
  }
}
```

5. Bulb joins home Wi-Fi, then POSTs to `appServerDomain`
   (`/life2/device/accessCloud.json`, expects `{success:true,messageCode:200}`)
   and `jbalancerDomain` (`/jbalancer/new/bimqtt`, expects broker address),
   then connects MQTT TLS 8883.

### Payload crypto

Key literal in the bundle (CryptoJS, AES with this key material, base64 in/out):

```
MTlCaWppbmdTaGFuZ2hhaVdpU2VuZ2xlZEZpMjBBQUJBU0U2NA==
```

(base64 of `19BijingShanghaiWiSengledFi20AABASE64`.) SengledTools implements
the same as RC4 — both observed; exact cipher per protocol version still to be
verified against the real bulb.

### Repointing an already-paired bulb (no reset needed)

UDP 9080, port of the bulb's LAN IP:

```json
{"func":"setConfigParams","type":1,"data":{
  "jbalancerDomain":"<new jbalancer URL>","timeZone":"...",
  "routerInfo":{"ssid":"...","bssid":"...","password":"..."}}}
```

and for Matter-capable devices `{"name":"setIotConfigRequest","payload":{
"jbalancerDomain":"...","timeZone":"..."}}` sent as UDP data to port 9080.

### UDP LAN control

JSON on port 9080 (SengledTools reference): `search_devices` (broadcast),
`set_device_switch`, `set_device_brightness`, `set_device_color`,
`set_device_colortemp`, etc.

## 4. TLS pinning

No `sha256/...` pins and no pinning provider found in the bundle or dex
strings. Manifest requests standard permissions only (INTERNET, Wi-Fi, BT,
location). Cleartext is used by the bulb setup endpoints (HTTP URLs in
`appServerDomain`). **Inference:** repointing should not require defeating
pinning; treat as unverified until a capture or live pair confirms.

## 5. Repointing the app (no APK patch needed)

`WebUrl._getEnvironment()` reads AsyncStorage key `environment` =
`{"ucenter":...,"element":...,"life":...,"oauth":...}` and uses it on startup
if present; otherwise falls back to the defaults above. A hidden dev
environment switcher (triggered by login `dev.ios@sengled.com`) also writes
this key. So the app can be pointed at a local server by writing that
AsyncStorage entry (or DNS-redirecting the four default domains).

## Open questions

- ~~Exact cipher (AES vs RC4) used by `Utils.encryptData` for the real bulb's
  pairing protocol version~~ — RC4 (per SengledTools implementation) confirmed
  working against the real bulb; app-side pairing substrate verified.
- Whether `getServerInfo` must return all fields for app startup to proceed.
- Exact JSON of `accessCloud.json` request body sent by the bulb.

## 7. Live-bulb findings (2026-10-14, user's bulb 00:11:22:33:44:01)

Empirical, from the bulb's own status publishes on `wifielement/<mac>/status`
after control commands (observed via the local bridge):

- `colorMode: "1"` — RGB color mode (after `color` commands);
  `color` echoed as `"r:g:b"`.
- `colorMode: "2"` — white/color-temperature mode (after `colorTemperature`
  commands). The bulb **echoes the exact value sent** — it expects a
  **0–100 warmth percent**, not Kelvin: sending `2700` is not echoed and does
  not switch modes; sending `20` echoes `colorTemperature: "20"`.
- `effectStatus: "0"` present in white mode.
- Kelvin↔percent mapping direction still unverified visually (0 = ? warm).

## 6. Local bridge notes (added after implementation)

### 6.1 Endpoint contract used by the local bridge (parity with SengledTools + static evidence)

| Endpoint | Method | Exact JSON (minified, as sent) |
|---|---|---|
| `/jbalancer/new/bimqtt` | GET/POST | `{"protocal":"mqtt","host":"<LAN IP>","port":8883}`  (spelling "protocal" is authentic) |
| `/life2/device/accessCloud.json` | GET/POST | `{"messageCode":"200","info":"OK","description":"…","success":true}` |

### 6.2 MQTT command types observed (per MQTT_COMMANDS_REFERENCE + `Utils.encryptData` notes)

| type | value encoding | notes |
|---|---|---|
| `switch` | `"1"` / `"0"` | on/off |
| `brightness` | `"0"` .. `"100"` | |
| `colorTemperature` | `"58"` or kelvin `"2700"` | unit differs by model |
| `color` | `"r:g:b"` | e.g. `"255:100:0"` |
| `update` | firmware URL | OTA; careful |


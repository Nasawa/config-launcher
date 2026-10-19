# Dynamic widgets: AI-assisted dashboard cards

**Status:** draft for review (Christopher / Claw / Pragma / Sprite).  
**Opened with:** product reframe from Christopher (via Grok Bot), screenshots in `/workspace/cl-input/` (not committed), Pragma UX [#116](https://github.com/Nasawa/config-launcher/pull/116) (`docs/ux.md` §17), schema answers Q-A1–Q-A8 on that PR.  
**Related:** [docs/research/widgets.md](../research/widgets.md) (updated by this PR where superseded), [docs/design/title-bar.md](title-bar.md), elements container (#112, merged), Stage 1 cell reservation.

## 0. Product reframe

Config Launcher's end vision is: **make your home screen into an AI-assisted dashboard.**

Apps, folders, and direct user control stay. Widgets come in two kinds; **(b) is the core**:

| Kind | Who renders | Who composes | Data |
|---|---|---|---|
| **(a) Hosted AppWidget** | Third-party app (HA companion, KWGT, …) via `AppWidgetHost` | That app's UI | That app's own network/storage |
| **(b) Dynamic config widget** | **This launcher** (Compose) | An AI (or human) writing `launcher.json` | Local data files and/or opt-in HTTPS fetch the launcher runs **on the widget's behalf** |

### Reference inputs (research only)

1. **Outer Shell (target).** `claw-outer-shell-widget.jpg` — Claw's POC card: title "Anigeek LLC", `as of 08:38`, refresh affordance, latest-post tile, status chips (`up · 96 ms`, `119/120 up`), unhealthy-monitors list. Full-width on a 5-column home grid.
2. **KWGT (anti-example).** `kwgt-handmade-widget.jpg` — hand-built absolute layout; **sizing broke on a tablet**. Absolute px/sp inside the card is forbidden.
3. **iappyx (anti-example).** `claw-iappyx-review.jpg` — every widget got a full JS bridge (SMS, contacts, SSH, ~40 permissions) with **no per-widget capability list**. We must not repeat that: **no scripts, no code execution, no shared bridge.**

## 1. Declarative component vocabulary (no scripts, ever)

Dynamic widgets are type `card` (name TBD at schema time; UX §17 says `card`) in `elements[]`.

### Non-goals (hard)

- No JavaScript, Lua, expressions-as-code, WebView, HTML, or downloadable script.
- No `window.*` bridge, reflection, or "plugin APK".
- No coordinates (`x`/`y`/dp/px/%) **inside** a widget (UX §17.8). Placement is only the element's grid `page,x,y,w,h`.

### Layout (flow only)

Containers: `column`, `row` (children may have `weight`), `tile` (inset sub-surface).

Responsive: up to 3 `layouts` keyed by `"WxH"` (minimum span). Launcher picks the **largest that fits**; else smallest + drop by `priority` (0–9, low first) + ellipsis. Optional `showIn` size-class filters. Optional `minSpan: { "w", "h" }` (amber outline if undersized; still shows — §16.5).

### Primitives (v1)

| type | Role |
|---|---|
| `text` | Template string; `role` caption/body/title; `maxLines` |
| `metric` | Label + value (+ unit) + optional `status` dot |
| `list` | Rows from a data **array**; leading dot/icon, primary, trailing |
| `icon` | Named Material symbol or installed app icon (no bitmap-with-text) |
| `progress` | 0–1 bar (or ring at 1×1) |
| `button` | Label; runs a declared **Open** action only in v1 |

**Card chrome (automatic):** title + `as of` from data `updatedAt`; `stale` after `staleAfter`.

### Templating and binding

- Reuse title-bar token grammar where useful (`{time:…}`, `{date:…}`, `{page}`, …).
- Data: `{data:path}` with a **strict JSON-path subset** (dot keys + `[index]`). Nothing is evaluated.
- Missing path → `—` (never throw). Lists require arrays.

## 2. Cell-relative sizing

- Grid placement stays Stage 1 (`page,x,y,w,h`); skipped grid elements **still reserve cells** (#112).
- Inside the card, tokens scale from cell size: `k = clamp(cell/80, 0.85, 1.5)` (UX §17.8). Text tiers by span class, not by "phone vs tablet" device id.
- Same `launcher.json` on a tablet: larger cells → larger `k`, more `list auto` rows, next `layouts` entry when span grows. No second layout language.

## 3. Data model

### Local (v1 of dynamic widgets)

- **One file per widget:** `.configlauncher/widgets-data/<elementId>.json`.
- Why not one shared blob: prevents cross-widget reads (confused deputy), matches `data.file` capability target 1:1, simpler consent.
- Size cap (proposal): 256 KiB per file; UTF-8 JSON only.
- Agent writes → phone polls / reload (same spirit as `launcher.json`; exact interval later).
- Root may include `updatedAt` (ISO-8601). Element may set `staleAfter` (ISO-8601 duration).

### Opt-in HTTPS fetch (stage after local)

- Capability `net.fetch` with **exact origin** pins (`https://host[:port]`).
- Launcher fetches; widget never sees a socket.
- **No cookies**, no shared `CookieJar`, no HTTP cache keying across element ids.
- Caps: response bytes, timeout, min refresh interval; redirects must stay on the pinned origin.
- **Secrets:** never in synced files. Device Keystore / encrypted prefs, entered on phone, scoped to `(elementId, origin)`. Agents declare that a secret slot exists; user fills it.

### What stays out of the synced folder

Approvals, widget instance bindings (AppWidget ids), Keystore secrets, last-good render cache.

## 4. Capability and consent model

Normative kinds (Archimedes; supersede §17.11 sample names):

| kind | targets | UX tier | v1? |
|---|---|---|---|
| `data.file` | path under `.configlauncher/widgets-data/` (no `..`) | Show | yes |
| `ui.openApp` | package name(s) | Open | yes |
| `ui.openUrl` | hostname(s) | Open | yes |
| `net.fetch` | exact HTTPS origin(s) | Internet | later stage |
| `phone.*` | — | Phone data | **not in v1** |
| Act kinds | — | Act | **not in v1** (buttons = Open only) |

**Never offered** (absent from schema entirely): SMS, calls, contacts, messages, mic, camera, location, VPN/tunnels, run code.

### Fingerprint and prompts

- Fingerprint = canonical sorted **capabilities + targets only** (not layout/style/`author`/span).
- File load **never** opens a dialog (Pragma §17.2). Broadened → dashed consent tile `Needs your OK` / `Needs your OK again`, content not rendered, cells kept.
- Proper **subset** of approved → silent. Return to exact approved fingerprint → silent re-enable.
- Same-tier data-source change (path already allowed) → info dot + `Changed` list, stays live.
- Approvals **device-local**, keyed by `elementId + capabilityFingerprint`.
- Optional `author` string (≤40): quoted in UI (`says "Claw"`), **never** trusted for auto-approve.

### `status.json`

```json
"consent": {
  "w-anigeek": {
    "state": "needsApproval" | "needsApprovalAgain" | "off" | "allowed",
    "warning": "optional §15.1-style sentence"
  }
}
```

Enforcement: runtime can only perform APIs listed in the **approved** manifest for that id. Unknown `capabilities[].kind` → **validation error**. Undeclared use is impossible by construction.

### Hosted AppWidgets

**Outside this model** (Pragma A5 / Q-A8). Android bind/configure only; review list shows them as "Allowed by Android".

## 5. INTERNET and ARCHITECTURE §1

Today §1: **No INTERNET** — launcher never fetches; security property against malicious config exfiltration.

**Proposal (needs Christopher):** add `INTERNET` to the APK for the fetch runtime only.

| Path | Needs our INTERNET? |
|---|---|
| Hosted AppWidgets (HA, KWGT) | No — those apps use their own |
| Dynamic widget + `data.file` only | No |
| Dynamic widget + approved `net.fetch` | Yes — launcher GET/HEAD to pinned origins |

Plain language for users/docs: *Config Launcher can use the network only for dashboard cards you explicitly allow, and only to the sites listed on that card. It still does not browse the web or sync your config for you.*

APK-level `INTERNET` is coarse (see §7). Capability approval is the real control plane; OS permission is the on/off master.

## 6. Threat model and mitigations

| Threat | Mitigation |
|---|---|
| Compromised agent / sync pushes hostile card | Consent tile before first render; never auto-approve; `author` not trusted |
| Exfiltration via fetch URL (path/query) | Origin pin; optional path allowlist later; show full URL in prompt; re-prompt on new origin |
| Tracking pixels / beacon `icon` URLs | v1: no arbitrary image URLs; icons = Material / installed app only |
| **SSRF to LAN** (homelab monitors) | Default **deny** RFC1918, link-local, metadata IPs for `net.fetch` unless user enables a stern "allow private origin" pin per origin; document that agent-written **local** JSON is the preferred path for LAN data |
| Cross-widget data read | One file per element id; `data.file` cannot name another id's file |
| Secret leakage across widgets | Keystore entries keyed by element id + origin |
| OpenURL phishing | Host allowlist in capability; tap targets must match |
| Refresh DoS against homelab | Min interval + jitter; cap concurrent fetches |
| iappyx-style over-privilege | Closed enum; never-offered absent; no script host |

## 7. Challenges / pushback on the speculative direction

Honest holes in "local files + opt-in URL fetch + per-widget manifest":

1. **APK `INTERNET` is all-or-nothing.** Once granted, a launcher bug or future capability mistake is higher stakes. Mitigation: keep fetch code tiny, origin-pin in one module, default deny private IPs, feature-flag fetch behind `requires`.
2. **Same origin, new path/query can still exfiltrate** (tokens in query). Mitigation: re-prompt on origin change only is not enough long-term — consider path prefix pins or forbidding query strings on secret-bearing fetches.
3. **LAN allow prompts will be tempting to click through** for homelab users. Prefer agent → local file for HA/Uptime Kuma data; treat private `net.fetch` as advanced.
4. **Shared refresh schedules** can still DDoS a Pi. Enforce global and per-origin rate limits, not only per-widget.
5. **Approvals don't sync** (correct for security) → every tablet re-consents (Pragma). Accept the friction.
6. **"Phone data" tier** without v1 kinds: keep the UX tier for later; don't invent battery/SMS "just in case."

## 8. HA Q-C5 revised

| Approach | Role |
|---|---|
| Host HA companion **AppWidgets** | Kind (a) — unchanged, no our INTERNET |
| Agent writes HA-shaped JSON into `widgets-data/<id>.json` | Kind (b) v1 — **recommended** for Outer Shell–class cards |
| `net.fetch` to HA URL + Keystore token | Kind (b) later — pinned origin, stern private-IP rules |

We still recommend **against** a built-in HA API client with broad rights. Claw (or any agent) owns the integration glue; the launcher owns safe display + consent.

## 9. Staged plan (schemaVersion 1 throughout)

| Stage | What | Feature level (proposal) |
|---|---|---|
| 0 | Elements container + Stage 1 reservation | 1 — **done (#112)** |
| 1 | Title bar type | 2 |
| 2 | `appWidget` host + tap-to-bind | 3 |
| 3 | `card` + local `data.file` + consent UX (§17) | 4 |
| 4 | Richer primitives if needed (parity with Outer Shell) | 4 |
| 5 | `net.fetch` + Keystore + INTERNET permission | 5 |
| 6 | Optional path pins, private-origin allow, phone.* | later |

**Hard gates:** Stage 0 before any skip of grid elements (met). Consent tile before any `card` renders data. Local data before fetch.

## 10. Open questions

### For Christopher / Claw

- **Q-C1:** Is adding `INTERNET` to the APK acceptable if gated by per-widget `net.fetch` approval?
- **Q-C2:** Ship dynamic widgets **local-data-only** first (stage 3), with fetch in a later release?
- **Q-C3:** Private LAN origins: default deny + explicit allow, or prefer "agent writes local JSON only" with no private fetch ever?
- **Q-C4:** Is `author` useful enough to keep, knowing it is only a quote?
- **Q-C5:** (revised HA) Confirm companion widgets + agent JSON is enough for v1 of (b).
- **Q-C6:** Any capability missing for Outer Shell besides `data.file` + `ui.openUrl`?

### For Pragma

See answers on [#116](https://github.com/Nasawa/config-launcher/pull/116#issuecomment-6270653131) (Q-A1–Q-A8). Please align §17.11 sample kinds to `data.file` / `ui.openApp` / `ui.openUrl` / `net.fetch` and `author` instead of `x-by`.

### For Sprite

- Codec + validate.py for `card`, capabilities enum, layouts, consent status fields.
- Consent store device-local; never write approvals into the folder.
- Fetch module isolation when stage 5 lands.

## 11. Worked example (normative kinds)

```json
{
  "id": "w-anigeek",
  "type": "card",
  "requires": 4,
  "page": "home", "x": 0, "y": 4, "w": 4, "h": 3,
  "minSpan": { "w": 3, "h": 2 },
  "title": "Anigeek LLC",
  "author": "Claw",
  "surface": "auto",
  "capabilities": [
    { "kind": "data.file", "path": "w-anigeek.json" },
    { "kind": "ui.openUrl", "hosts": ["anigeek.com", "blog.anigeek.com"] }
  ],
  "data": { "staleAfter": "PT30M" },
  "layouts": {
    "2x1": [
      { "type": "metric", "label": "Monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}" }
    ],
    "4x3": [
      { "type": "tile", "children": [
        { "type": "text", "role": "caption", "text": "Latest on blog.anigeek.com" },
        { "type": "text", "role": "body", "text": "{data:blog.title}", "maxLines": 2 }
      ], "tap": { "kind": "ui.openUrl", "host": "blog.anigeek.com", "url": "{data:blog.url}" } },
      { "type": "row", "children": [
        { "type": "metric", "label": "PicSpin site", "value": "{data:picspin.value}", "status": "{data:picspin.status}" },
        { "type": "metric", "label": "letmeaithatforyou", "value": "{data:lmaitfy.value}", "status": "{data:lmaitfy.status}", "priority": 4 },
        { "type": "metric", "label": "Homelab monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}" }
      ] },
      { "type": "list", "label": "Unhealthy monitors", "items": "{data:unhealthy}", "maxItems": "auto", "emptyText": "All monitors up" }
    ]
  }
}
```

Data file: `.configlauncher/widgets-data/w-anigeek.json` (agent-written). First load on a phone shows the consent tile for `ui.openUrl` only if taps are present; Show-only cards with just `data.file` go live with no prompt (Pragma Show tier).

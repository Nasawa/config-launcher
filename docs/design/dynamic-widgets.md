# Dynamic widgets: AI-assisted dashboard cards

**Status:** draft for review (Pragma / Sprite; product Qs from Christopher landed).  
**Christopher settled Q-C1–C5, Q-C7** (2026-10-19); **Q-C6 already decided**. PR stays draft — product answers only; Sprite schema work is not unblocked solely by this.  
**Q-A9 (Pragma #116 / Claw):** http in `hosts`/`origins`; approval ceiling = cumulative union. **Claw review folded in** (PR #117 comment at 313ab26): structural `data.file`, exact-host `hosts`, tap example, Q-C7/Q-C3 recommendations, `ui.reload` + relative time, narrowing ceiling model.

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
- No arbitrary remote images in v1. If images arrive later: no SVG, with byte and dimension caps.

### Layout (flow only)

Containers: `column`, `row` (children may have `weight`), `tile` (inset sub-surface).

Responsive: up to 3 `layouts` keyed by `"WxH"` with a **lowercase** `x` (e.g. `"4x3"`, not `"4X3"`; pattern `^[1-9][0-9]*x[1-9][0-9]*$`). Launcher picks the **largest that fits**; else smallest + drop by `priority` (0–9, low first) + ellipsis. Optional `showIn` size-class filters. Optional `minSpan: { "w", "h" }` (amber outline if undersized; still shows — §16.5).

### Primitives (v1)

| type | Role |
|---|---|
| `text` | Template string; `role` caption/body/title; `maxLines` |
| `metric` | Label + value (+ unit) + optional `status` dot |
| `list` | Rows from a data **array**; leading dot/icon, primary, trailing |
| `icon` | Named Material symbol or installed app icon (no bitmap-with-text) |
| `progress` | 0–1 bar (or ring at 1×1) |
| `button` | Label; runs a declared action (`ui.openUrl`, `ui.openApp`, or `ui.reload`) |

**Card chrome (automatic):** title + `as of` from data `updatedAt`; `stale` after `staleAfter`.

### Templating and binding

- Reuse title-bar token grammar where useful (`{time:…}`, `{date:…}`, `{page}`, …).
- **Relative time (cards):** token name `{relative}` (optional path `{relative:data.updatedAt}`; default = data file root `updatedAt`). Renders **`5 min ago` / `just now`** only — **no pattern** argument. Closed formatter, no scripts. Title bar stays absolute-only in v1.
- Data: `{data:path}` with a **strict JSON-path subset** (dot keys + `[index]`). Nothing is evaluated.
- Missing path → `—` (never throw). Lists require arrays.

## 2. Cell-relative sizing

- Grid placement stays Stage 1 (`page,x,y,w,h`); skipped grid elements **still reserve cells** (#112).
- Inside the card, tokens scale from cell size: `k = clamp(cell/80, 0.85, 1.5)` (UX §17.8). Text tiers by span class, not by "phone vs tablet" device id.
- Same `launcher.json` on a tablet: larger cells → larger `k`, more `list auto` rows, next `layouts` entry when span grows. No second layout language.

## 3. Data model

### Local (v1 of dynamic widgets)

- **One file per widget, structurally:** always `.configlauncher/widgets-data/<elementId>.json`. The path is **derived from the element `id`** — there is no free `path` field. Capability is `{ "kind": "data.file" }` only.
- Why: prevents cross-widget reads by construction (confused deputy); agents cannot point card A at card B's file.
- Size cap (proposal): 256 KiB per file; UTF-8 JSON only.
- Agent writes → phone polls / reload (same spirit as `launcher.json`; exact interval later).
- Root may include `updatedAt` (ISO-8601). Element may set `staleAfter` (ISO-8601 duration).

### Opt-in HTTPS fetch (stage after local)

- Capability `net.fetch` with **exact origin** pins (`https://host[:port]`).
- Launcher fetches; widget never sees a socket.
- **No cookies**, no shared `CookieJar`, no HTTP cache keying across element ids.
- Caps: response bytes, timeout, min refresh interval.
- **HTTP client:** one isolated client per element id (no shared cache, no cookies, fixed `User-Agent` naming Config Launcher).
- **Redirects:** follow only if the next hop stays on the **same pinned origin**; otherwise fail the fetch.
- **`net.fetch` request URLs are literals** in the element (fixed under a pinned origin). They must **not** be built from `{data:…}`.
- **`ui.openUrl` tap `url` MAY use `{data:…}`** (Outer Shell needs this for "latest post"). At tap time resolve the template, normalise the host (IDNA/punycode/lowercase), and **open only if** it **exactly** matches an approved `hosts` entry (no subdomain inference), scheme+host match an approved `hosts` entry (bare = https only; `http://…` entry required for cleartext), and there is no `userinfo`. Else snackbar + warning. Data must not open a host outside the allowlist.
- **Connect-time IP check:** on every connection, resolve and refuse RFC1918 / link-local / metadata addresses unless that exact origin was approved under the private-origin allow flow. **DNS rebinding:** re-check the connected IP on each request, not only at approval time.
- **Cleartext / LAN (decided — Q-C3 / Q-C7, Christopher 2026-10-19):** users own what they knowingly approve. `http://` is allowed when the user approves an `http://host` host entry or `http://…` origin by name — show `http://` in the prompt; **no silent https→http downgrade**. Disclosure / consent copy must say that **http is less secure**, that some local setups make https difficult for little gain, and that **the user is responsible for what they approve**. Self-signed: **TOFU certificate pinning per origin** (pin on first approve / explicit re-pin if the cert changes; stern copy about remembering the pin) — not disabling TLS validation. Private IPs still need an explicit approved origin; agent-written local JSON remains the simplest LAN path.
- **Secrets:** never in synced files. Device Keystore / encrypted prefs, entered on phone, scoped to `(elementId, origin)`. Agents declare that a secret slot exists; user fills it. **When the pinned origin set changes, delete secrets for origins that were removed** before any new fetch runs.

### What stays out of the synced folder

Approvals, widget instance bindings (AppWidget ids), Keystore secrets, last-good render cache.

## 4. Capability and consent model

Normative kinds (Archimedes; supersede §17.11 sample names):

| kind | targets | UX tier | v1? |
|---|---|---|---|
| `data.file` | *(none — file is always `widgets-data/<elementId>.json`)* | Show | yes |
| `ui.reload` | *(none)* — tap re-reads this widget's data file | Show | yes |
| `ui.openApp` | package name(s) | Open | yes |
| `ui.openUrl` | hostname(s) | Open (**prompts** — phishing; see note) | yes |
| `net.fetch` | exact HTTPS origin(s) | Internet | later stage |
| `phone.*` | — | Phone data | **not in v1** |
| Act kinds | — | Act | **not in v1** (buttons = Open only) |



### Capability field names (normative)

Prefer **kind-specific keys**, not a generic `targets` array (easier for schema and agents):

| kind | Fields |
|---|---|
| `data.file` | *(no fields)* — path derived from element `id` |
| `ui.reload` | *(no fields)* |
| `ui.openApp` | `packages` (string array) |
| `ui.openUrl` | `hosts` (bare hostname = https; or `http://host` / `https://host`) |
| `net.fetch` | `origins` (exact `https://host[:port]` or `http://host[:port]`) |

Fingerprint serializes `kind` + those fields (sorted). Pragma's illustrative `targets` is **not** the schema shape.


### `ui.openUrl.hosts` matching (exact) — Q-A9

Entries are either:

| Form in `hosts` | Allows |
|---|---|
| bare hostname (`blog.anigeek.com`) | **`https://` only** to that exact host |
| scheme-qualified (`http://nas.lan`, `https://nas.lan`) | that **scheme + host** only |

- Normalise before compare: Unicode → lowercase / **punycode** (IDNA). Bare entries have no port; scheme-qualified may include a non-default port as `http://host:8080` if we allow that later — v1: host only, default ports.
- **No implied subdomains:** `anigeek.com` ≠ `blog.anigeek.com`.
- Prompt shows the form that will be enforced, with **`http://` visible** when http was approved. **No silent https→http downgrade** (an https approval never opens http).
- Default for agents: prefer bare hostnames (https). Use `http://…` only when the user/agent knowingly needs cleartext (homelab).
- At tap time: resolve `{data:…}`, then require scheme+host to match an approved entry; else snackbar + `warn_tap_host` / `warn_tap_scheme`.

`net.fetch` `origins` are always full origins (`https://host[:port]` or `http://host[:port]`) and follow the same approve-by-name / no-downgrade rules.

**Tap actions** on a node: `{ "kind": "ui.openUrl", "url": "…" }` or `{ "kind": "ui.openApp", "package": "…" }`. The `url` / `package` must be allowed by a declared capability on the same element.

**Open-tier split (Pragma #116):** `ui.openApp` stays **silent** (installing an app is already a user act). `ui.openUrl` **always prompts** on first enable / host broaden, even though the UX sentence sits in the Open tier — agent-written links are a phishing risk. Christopher confirms via Pragma Q-D5. Show-tier `data.file` and `ui.reload` stay silent.
**Never offered** (absent from schema entirely): SMS, calls, contacts, messages, mic, camera, location, VPN/tunnels, run code.

### Fingerprint and prompts

- Fingerprint = canonical sorted **capabilities + targets only** (not layout/style/`author`/span).
- File load **never** opens a dialog (Pragma §17.2). Broadened → dashed consent tile `Needs your OK` / `Needs your OK again`, content not rendered, cells kept.
- See **Approval ceiling** below (union set; never shrinks).
- Cosmetic / layout / data-shape changes with capabilities unchanged → **stay live, no prompt, no info dot**. A quiet history entry only (Pragma §17 / Claw #3).

### Approval ceiling (Claw #3 / Q-A9) — one set per widget, never shrinks

The device stores one **approved capability set** per element id: a union of every `(kind, target)` line the user has ever approved for that widget on this phone.

- Approvals at **different times combine** (union). Approving `ui.openUrl`+`blog.anigeek.com` today and `data.file` tomorrow yields both in the ceiling.
- The stored set **never shrinks** on file edits (narrowing does not delete lines).
- **File is live with no prompt** iff every declared `(kind, target)` in the file is ∈ the stored set (file ⊆ ceiling).
- **Anything outside the ceiling** → prompt only for the **new** lines (`needsApproval` / `needsApprovalAgain`); on approve, union them into the ceiling.
- **Turn off** is a sticky device-local switch: the widget stays off and **keeps** the approval ceiling. **Allow** turns it back on with the same set (no re-prompt if the file is still ⊆ ceiling). Explicit revoke in Widget permissions is what clears the set.

**Exception — Phone data + Internet combo:** even if `phone.*` and `net.fetch` were each approved alone earlier, the first time a widget declares **both**, show the combination prompt once (Pragma §17.1). After that combo approval, the pair is in the ceiling.

This replaces a single “fingerprint equality / subset of last fingerprint” model: the ceiling is cumulative across prompts.

- Approvals **device-local**, keyed by `elementId + capabilityFingerprint`.
- Optional / **recommended** `author` string (≤40): quoted in UI (`says "Claw"`), **never** trusted for auto-approve (Q-C4). If omitted, **hide** the author chrome — do not crash and do not show empty quote UI.

### `status.json`

```json
"consent": {
  "w-anigeek": {
    "state": "needsApproval" | "needsApprovalAgain" | "off" | "allowed",
    "warning": "optional §15.1-style sentence"
  }
}
```

Enforcement: runtime can only perform APIs listed in the **approved** manifest for that id. Unknown `capabilities[].kind` on this launcher → **skip that element** with a warning (consent tile / "Update Config Launcher" or "Can't show", cells kept) — **not** a whole-file B2 reject. Writers of a new kind MUST bump the feature level and set `requires` on every element that uses it so older phones hit the requires-skip path cleanly. `validate.py` / CI at the current feature level may still treat unknown kinds as errors so agents fail fast on typos. Undeclared use at runtime is impossible by construction.

### Hosted AppWidgets

**Outside this model** (Pragma A5 / Q-A8). Android bind/configure only; review list shows them as "Allowed by Android".

## 5. INTERNET and ARCHITECTURE §1

Today §1: **No INTERNET** — launcher never fetches; security property against malicious config exfiltration.

**Decided (Q-C1, Christopher 2026-10-19):** add `INTERNET` to the APK for **stage 5** (fetch runtime) only. He assumes it is necessary for `net.fetch`. Real control remains per-widget `net.fetch` approval (origin pins, no cookies, private-IP deny by default). Isolate fetch behind a feature flag / `requires`.

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
| **DNS rebinding** (public name → LAN IP later) | Re-validate the connected peer IP on **every** connection, not only when the origin was approved |
| **Stale secret after origin edit** | Delete Keystore entries for origins removed from the capability before the next fetch |
| Cross-widget data read | File path derived from element id only — no `path` field to misuse |
| Secret leakage across widgets | Keystore entries keyed by element id + origin |
| OpenURL phishing | Host allowlist in capability; tap targets must match |
| Refresh DoS against homelab | Min interval + jitter; cap concurrent fetches |
| iappyx-style over-privilege | Closed enum; never-offered absent; no script host |

## 7. Challenges / pushback on the speculative direction

Honest holes in "local files + opt-in URL fetch + per-widget manifest":

1. **APK `INTERNET` is all-or-nothing.** Once granted, a launcher bug or future capability mistake is higher stakes. Mitigation: keep fetch code tiny, origin-pin in one module, default deny private IPs, feature-flag fetch behind `requires`.
2. **Path/query on taps:** `{data:…}` openUrl is host-pinned at tap time (see §3). Fetch URLs stay literal. Secret-bearing fetches should still avoid query strings; path prefix pins remain a later option.
3. **LAN allow prompts will be tempting to click through** for homelab users. Prefer agent → local file for HA/Uptime Kuma data; treat private `net.fetch` as advanced.
4. **Shared refresh schedules** can still DDoS a Pi. Enforce global and per-origin rate limits, not only per-widget.
5. **Approvals don't sync** (correct for security) → every tablet re-consents (Pragma). Accept the friction.
6. **"Phone data" tier** without v1 kinds: keep the UX tier for later; don't invent battery/SMS "just in case."
7. **System notifications need `POST_NOTIFICATIONS` on Android 13+.** Prefer launcher-only signals for v1 (B9 + ⋮ badge + consent tile). A system notification is optional later and needs its own permission prompt.
8. **Homelab cleartext / self-signed:** follow §3 (decided Q-C3/C7: `http://` visible in prompt; TOFU pins; disclosure that http is less secure and the user owns the choice). Still easy to click through — keep copy stern.
9. **`widgets-data/` git policy:** agents may want these files synced; they can contain semi-sensitive status text. Document in README whether to commit or gitignore (proposal: commit is OK if no secrets; never put tokens there).

## 8. HA Q-C5 (decided, Christopher 2026-10-19)

| Approach | Role |
|---|---|
| Host HA companion **AppWidgets** | Kind (a) — unchanged, no our INTERNET |
| Agent writes HA-shaped JSON into `widgets-data/<id>.json` | Kind (b) v1 — **recommended** for Outer Shell–class cards |
| `net.fetch` to HA URL + Keystore token | Kind (b) later / **advanced** — pinned origin, stern private-IP rules |

**Confirmed:** companion AppWidgets + agent-written local JSON are enough for v1 of kind (b). HA must **not** be tightly coupled to the launcher. Building your own HA-reading card (agent JSON or later `net.fetch`) is an advanced usage. Distant future: optional first-class widgets shipped in the app is OK as a far goal, not v1. Still **against** a built-in HA API client with broad rights. Claw (or any agent) owns the integration glue; the launcher owns safe display + consent.

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

**Hard gates:** Stage 0 before any skip of grid elements (met). Consent tile before any `card` renders data. Local data before fetch (**Q-C2 decided:** ship stage 3 local-data-only first; fetch is a later release).

## 10. Open questions

### For Christopher / Claw — product answers landed (2026-10-19)

- **Q-C1:** *(decided)* **Yes** — APK `INTERNET` for stage 5, gated by per-widget `net.fetch` approval; assumed necessary for fetch.
- **Q-C2:** *(decided)* **Yes** — ship dynamic widgets **local-data-only** first (stage 3); fetch in a later release.
- **Q-C3:** *(decided)* **Yes** — users own what they knowingly approve; explicit per-origin allow including private/`http://`; clear prompt labelling. Consent/disclosure copy must note that http is less secure, that some local setups make https difficult for little gain, and that the user is responsible for their approvals.
- **Q-C4:** *(decided)* **Keep** `author` as optional/recommended (cheap quote signal). If omitted, **hide** it — do not crash or show empty chrome.
- **Q-C5:** *(decided)* **Yes** — companion AppWidgets + agent-written local JSON are enough for v1 of (b). No tight HA coupling; own HA-reading widgets are advanced. Distant optional first-class in-app widgets OK as a far goal, not v1.
- **Q-C6:** *(decided)* Show-tier `ui.reload`; token `{relative}` → `5 min ago` / `just now`, no pattern. Pragma OK.
- **Q-C7:** *(decided)* Scheme-qualified `http://` in `hosts`/`origins` when approved by name; **TOFU** for self-signed (stern pin-on-approve / re-pin-on-change copy); no silent downgrade.

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
    { "kind": "data.file" },
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
      ], "tap": { "kind": "ui.openUrl", "url": "{data:blog.url}" } },
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

Data file: `.configlauncher/widgets-data/w-anigeek.json` (agent-written). First load prompts for `ui.openUrl` (phishing rule) when that capability is present; Show-only cards with just `data.file` go live with no prompt. The sample tap uses `{data:blog.url}` only (no redundant `host` field) — allowed when the resolved host **exactly** matches `hosts`.

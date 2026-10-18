# Research spike: config-defined Android launcher with two-way bindings
Date: 2026-10-18. Every link below came back from WebSearch or WebFetch during this spike. Facts not checked against a source are marked *(unverified)*.

## TL;DR
- **Nothing found does the whole job:** a declarative config file that drives the home screen, plus on-device edits that write back to the same file, plus a safe interface for an external agent, all within the normal launcher permissions.
- The closest one-way match is **OhmLauncher**: its UI is generated from JSON on /sdcard and hot-reloads when the file is saved. It is brand new (0 stars, created 2026-08), written in Flutter, and needs broad storage access. There is no evidence that edits write back to the file.
- **Recommendation: build from scratch** in Kotlin + Compose, using **Kvaesitso** (GPL-3, Compose, actively maintained) as reference code for LauncherApps, widgets and shortcuts. Forking Launcher3/Lawnchair would mean fighting a SQLite-favorites model that was never designed around a file.

## 1. Existing options
| Project | Config-defined UI? | Two-way sync? | Open source | Permissions (notable) | Maintenance | Link |
|---|---|---|---|---|---|---|
| OhmLauncher | **Yes.** JSON `desktops[]` tree (container/text/clock/apps-grid/battery/plugin), hot reload on save | Not documented | Yes (repo, license not stated) | Reads /sdcard/OhmLauncher/… (external storage) and LAN networking | New, 0 stars, created 2026-08-23 | https://github.com/avillagran/ohm-launcher |
| NovaaLauncher | No. Layout is saved internally as JSON (HomeDataManager) | Internal only | GPL-3 | Standard launcher set | Hobby project, 0 stars | https://github.com/AzliRynz/NovaaLauncher |
| Kvaesitso | No. Settings and widgets live in the app, built with Compose | No | GPL-3 | QUERY_ALL_PACKAGES, optional contacts/calendar | Active, v1.41.0 on F-Droid Sep 2026, ~4.8k stars | https://github.com/MM2-0/Kvaesitso/ · https://f-droid.org/packages/de.mm20.launcher2.release/ |
| KISS | No. Search-first, no layout to define | No | GPL-3 | QUERY_ALL_PACKAGES, contacts, phone. **Accessibility** only for "double tap to lock" | Active, v3.26.0 Jul 2026 | https://github.com/Neamar/KISS/ · https://f-droid.org/packages/fr.neamar.kiss/ |
| Olauncher | No. Minimal text list, settings in the app | No | GPL-3 | Minimal | Active (v6.9.x releases) | https://github.com/tanujnotes/olauncher |
| Lawnchair | No. Launcher3 SQLite plus a backup file (opaque, not meant to be edited) | No | Yes (GPL-3 *(unverified)*) | Standard launcher set, optional QuickSwitch/root | Active, `16-dev` branch | https://github.com/LawnchairLauncher/lawnchair/ |
| ARIA (Lawnchair fork) | LLM-driven cards; layout is not a config file | No | Yes ("Other" license) | Usage stats, LLM network access | New, 0 stars, 2026-03 | https://github.com/trek-boldly-go/aria-launcher |
| Mouture | AI proposes folders and layouts as **drafts the user approves** | N/A | No (Play Store) | On-device AI, "asks for a little more" | Commercial, current | https://mouture.app/ |
| Saint John | No. LLM chat plus a folder drawer | No | Yes | API keys, calendar, location | Small, 2025 | https://github.com/jonaylor89/SaintJohn |
| Tasker (+AutoLaunch) | Tasker Scenes plus XML export/import, so a UI can be defined in XML | Export only. The XML is not live-bound | No | Broad, often Accessibility, ADB-granted WRITE_SECURE_SETTINGS, etc. | Active | https://github.com/Taskomater/Tasker-XML-Info · https://joaoapps.com/autolaunch/ |
| Kustom KLWP/KLCK | Visual preset (.klwp zip) that you import, not text you edit live | No | No | Storage, optional others | Active | https://docs.kustom.rocks/docs/faq/faq_klwp/ |
| Shizuku | Not a launcher. It brokers system APIs through an ADB- or root-started process | – | Yes | ADB/root grant (powerful) | Active | https://github.com/RikkaApps/Shizuku/ |

Not verified this session: Niagara, Smart Launcher, Total Launcher and Nova import/export. These are all closed source. From prior knowledge, Nova/Smart/Total offer opaque backup files only, with nothing live or two-way *(unverified)*.

**Pattern:** the "AI organizes your phone" projects either act through chat or Accessibility (risky), or keep the AI on a propose-and-approve basis (Mouture). None of them expose the layout as an editable document.

## 2. Outer Loop / Outer Shell
These are real projects by Marcus Lewis (outergroup), but **they are not Android launchers**.
- **Outer Loop** (https://outerloop.sh/) is "a browser that runs apps over SSH and on localhost." It is macOS-only today. The Android version is listed as "coming soon."
- **Outer Shell** (https://outershell.org/, https://github.com/outergroup/outershell) is the default "home app" for Outer Loop: a remote-first graphical shell for Linux and macOS machines. A daemon (`outershelld`) owns an **app registry** over a local Unix socket API. A small CLI (`outerctl`) lets installers and apps update the registry. A JSON **app catalog** describes the installable apps. The native UI and the web UI hit the same `/api/*`, so both "see and control the same apps."
- Blog: https://probablymarcus.com/blocks/2026/05/10/like-a-web-view-but-native.html. It notes that phone app stores block loading external native code, so mobile versions would need a web view.

**Ideas worth borrowing:**
1. **The home screen as a client of a registry daemon.** The source of truth is a store with an API, and the UI is just one view of it.
2. **A tiny CLI or API for machines** (the outerctl analogue), next to the human UI. Both mutate the same state.
3. **A declarative JSON catalog.**
4. **README written for coding agents.** The repo explicitly includes context aimed at AI tools.

## 3. Permission and security model
**With just the launcher (HOME) role plus normal permissions you can do everything a home screen needs:**
- `LauncherApps` covers app lists across profiles (work/private space), launching, pinned/dynamic shortcuts (the default launcher gets shortcut host access), and package change callbacks.
- `QUERY_ALL_PACKAGES` gives full app visibility. Google lists it as allowed for limited categories, and launchers are a recognized use (https://developer.android.com/training/package-visibility/declaring). Alternatively, a `<queries>` entry with the MAIN/LAUNCHER intent covers launchable apps without it.
- `AppWidgetHost` hosts widgets. Binding needs a user-consent dialog (`ACTION_APPWIDGET_BIND`) unless the user granted it.
- Wallpaper, icon packs, folders, gestures and search are all fine.

**What needs more power, and why to avoid it:**
- **Accessibility:** screen lock-on-gesture, reading or controlling other apps. This is the "risky" model seen in AI agents. Lock is better done with Device Admin `lockNow` or simply skipped.
- **Device Admin:** lock screen (lockNow). Narrow, but it carries an alarming prompt.
- **Notification listener:** notification badges and counts. Optional, put behind a toggle.
- **Usage stats (PACKAGE_USAGE_STATS):** "most used" ordering for AI suggestions. Optional.
- **Shizuku / root:** uninstall or hide apps silently, change system settings. Out of scope.

**Safe options for external agent editing (ranked):**
1. **Recommended: a SAF folder the user picks** (e.g. one synced by Syncthing or a git client) holding `layout.yaml`. The launcher holds a persistable URI grant and watches the folder (ContentObserver/polling; FileObserver doesn't work on SAF URIs). It needs no storage permission, and the agent edits files on a PC, synced via git/Syncthing, which gives you **history and rollback for free**.
2. **ContentProvider with a `signature`-level (or `knownSigner`) permission**, for your own companion app or CLI. Strong isolation; on-device agents must be signed by you.
3. **Exported intent / AIDL with confirmation:** the agent sends a proposed patch, and the launcher shows a diff and Apply/Reject (Mouture-style "the model proposes, you decide").
4. **adb push** into app-specific storage for dev use.

**Always:** validate against a schema, apply atomically (write temp then rename), keep the last N versions, never execute config content (no scripts and no arbitrary intents unless they are allow-listed), and refuse unknown package names rather than launching them.

## 4. Verdict and recommended path
**Does something already do it all?** No. OhmLauncher shows that a hot-reload JSON home screen works, but it is one-way, very new, Flutter-based, and uses broad /sdcard access.

**Fork vs from scratch:** build from scratch, borrowing from Kvaesitso (GPL-3, so a fork or copied code inherits GPL; fine for a personal or OSS project). Launcher3-based projects (Lawnchair) store favorites in SQLite with complex grid logic, which makes a file-first source of truth awkward.

**Minimal architecture:**
- Kotlin + Jetpack Compose with a single HOME activity.
- `layout.yaml` (or JSON) with a versioned schema: `pages[] -> items[] {type: app|shortcut|widget|folder|text, id: package/activity or shortcut id, x, y, w, h}` plus `dock` and `drawer.rules` (e.g. tag-based auto-folders). Publish a JSON Schema so agents can validate.
- **Store:** parse into an immutable model held in a StateFlow. The UI renders the model.
- **Inbound binding:** watch the SAF folder or a debounced file, parse and validate, then replace the model. On error, keep the old model and show a banner.
- **Outbound binding:** a drag/edit produces a model mutation, which is serialized and written atomically. Preserve comments and key order with a round-trip-aware serializer (or use JSON to avoid YAML round-trip pain). Record a content hash to suppress echo reloads.
- **Conflicts:** last-writer-wins plus a version counter. Keep `.history/` snapshots, or let git handle it.
- **Reconciliation:** installed apps missing from the config go to the drawer or an "Unsorted" page, and config entries for uninstalled apps are greyed out, not deleted.
- Optional later: a signature-protected ContentProvider and an `outerctl`-style companion CLI over adb.

**MVP scope:** home pages with an app grid and dock, a drawer, folders, the YAML/JSON file in a SAF folder, live reload, write-back on drag/add/remove, and schema validation with an error banner. Leave out widgets (consent and sizing complexity), icon packs and AI in v1. Point an agent at the synced file instead.

**Risks:**
- YAML round-tripping loses comments and formatting. JSON5 or a canonical formatter may be wiser.
- Write races between the agent and on-device drag.
- SAF observation latency and quirks.
- Widget binding consent and OEM differences.
- Work profile and private space IDs in the config.
- Play policy on QUERY_ALL_PACKAGES (fine for a launcher; sideloading avoids it entirely).
- Process death and launcher restart performance on large configs.
- GPL obligations if you copy Kvaesitso code.

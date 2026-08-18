# Legal, Licensing & Responsible Use

## Distribution model

DataKhoj for Android is a **personal tool distributed as a sideloaded APK**.
It is not published on Google Play.

That is a deliberate architectural decision, and it has consequences worth
understanding rather than discovering later.

### Why not Play

Google Play's User Data policy restricts apps that collect or publish personal
contact information without the data subjects' authorisation, and Play's
review process has tightened substantially — Google reports 80,000+ developer
accounts banned and 255,000+ apps blocked from excessive data access in 2025.
A general-purpose scraper with contact-extraction capability is very difficult
to land there.

Sideloading removes that constraint. It also removes Play's automatic update
channel and its malware scanning, so **you** are the release engineer.

### What "unrestricted" does and doesn't mean

Unrestricted here means: *the tool does not second-guess you.* No allowlist of
approved sites, no artificial result caps, no telemetry, no feature gating.

It does **not** mean the law stops applying. The realistic constraints on a
personal data collector:

| Concern | Reality |
|---|---|
| **robots.txt** | Honoured by default, per job. You can disable it. Disabling is a considered choice, not an accident — the setting is explicit and logged. |
| **Terms of Service** | Scraping a site may breach its ToS. That is a contract matter between you and them. |
| **Copyright** | Downloading media you have no right to is infringement regardless of what tool you use. The app does not and cannot adjudicate this. |
| **Personal data** | Bulk-collecting identifiable people's emails/phones engages Bangladesh's Personal Data Protection Ordinance and, for EU data subjects, GDPR Art. 14 — which requires notifying people whose data you collected indirectly. Practically impossible at scale. |
| **Rate limiting** | Aggressive crawling can constitute a denial-of-service. Defaults are polite (1.5 s between requests, 3 concurrent). |
| **Torrents** | The app can *find* magnets and `.torrent` files. Content legality is entirely yours to determine. |

The honest summary: this is a power tool. It has no guard because you asked for
none, which puts the judgement on you rather than the software.

### Recommended posture

* Keep `respect_robots` on unless you have a specific reason
* Keep default delays for sites you do not own
* Do not redistribute scraped personal data
* Prefer official APIs where a provider offers one (`ProviderTrust.OFFICIAL_API`)

---

## Third-party licences

| Component | Licence | Use |
|---|---|---|
| Jsoup 1.17.2 | MIT | HTML parsing |
| OkHttp 4.12.0 | Apache 2.0 | HTTP client |
| kotlinx-coroutines | Apache 2.0 | Concurrency |
| org.json | Public Domain | JSON |
| AndroidX / Jetpack | Apache 2.0 | Compose, Room, WorkManager |
| Material Design 3 | Apache 2.0 | Design system |
| Material Symbols | Apache 2.0 | Icons |
| Roboto / Inter | Apache 2.0 / OFL | Typography |

All permit commercial and private use with attribution.

### Trademark boundaries

Apache 2.0 grants **no trademark rights** (§6). Accordingly this project does
not use:

* The Google name, logo, or the four-colour "G" mark
* **Product Sans** (proprietary — Google's own documentation states it is not
  offered under an open-source licence)
* Google's four-colour palette as brand identity (trade dress)
* The `com.google.*` namespace

DataKhoj uses Material Design *as designed to be used* — an open system for
third-party apps — with an independent identity: original jade/saffron palette,
original logo, open-licensed fonts. See `docs/DESIGN.md`.

## Project licence

**AGPL-3.0-or-later** © 2026 soobujmiah. See [`../LICENSE`](../LICENSE) and
[`../COPYRIGHT.md`](../COPYRIGHT.md).

Moved from MIT deliberately: MIT would let anyone close the source, rebrand,
and sell this. AGPL requires derivatives to stay open, including when deployed
as a network service (§13).

Trademarks ("DataKhoj", the logo, `dev.datakhoj.app`) are **not** licensed.
Forks must rename.

Provided **as is**, without warranty (§15–16). The author accepts no liability
for how the tool is used.

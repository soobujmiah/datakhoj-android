# SKB Knowledge Return Contract

**Status:** Active  
**Purpose:** Define how durable, cross-project knowledge from DataKhoj Android is returned to the owner's private SKB.

## 1. Knowledge continuity

DataKhoj Android remains authoritative for its own implementation state. When work is performed within the owner's SKB workflow and authenticated access is available, use relevant SKB context before substantive decisions and perform a knowledge-return review after substantive work.

## 2. Adaptive knowledge return

Do not use a fixed documentation template merely because this project is Android/Kotlin. Inspect the work and the existing SKB organization, then select the most appropriate destination, structure, document type, language, and level of detail.

Return durable information when materially useful across future work, including important architecture or implementation decisions, reusable engineering techniques, verified device/toolchain findings, significant bugs and fixes, limitations and rejected alternatives, security/privacy/licensing decisions, and meaningful project-state changes or cross-project relationships.

Do not return routine noise, temporary debug output, unsupported claims, or secrets, credentials, tokens, private keys, or session material.

## 3. Evidence and conflicts

Preserve provenance where practical: repository, branch, commit, file, command, test, device, or other evidence. Distinguish verified facts from observations, inference, recommendations, and unknown/stale information.

If new knowledge conflicts with existing SKB knowledge, do not silently overwrite it. Record the conflict or supersession relationship and use the strongest current evidence for current-state claims.

## 4. Security boundary

This contract is a protocol, not a credential grant.

- Never store SKB or GitHub credentials in this repository.
- A fork or clone must not inherit SKB write authority.
- Before an SKB write, verify authenticated identity and actual write permission.
- If access or authorization is unavailable, report the proposed knowledge return instead of performing an unauthorized write.

## 5. Authority boundary

This contract covers knowledge continuity only. It does not authorize unrelated code changes, publication, release, deployment, destructive actions, or credential operations. Explicit task restrictions take precedence.

## 6. Legacy mode

Legacy mode is intentionally deferred and is not part of the current implementation.

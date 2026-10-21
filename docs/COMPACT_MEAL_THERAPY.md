# Compact Meal And Therapy Tables

Source-only extension of the opt-in compact CHAT v3 adapter, retaining the shared
concise response policy. No host activation,
upload/schema change, model call, persistent native thread, phone or therapy action.

`MealTherapyEvents` represents every selected latest MEAL/THERAPY record inside
the requested half-open window. The authenticated bounded store still owns
identity, revision, tombstone, expiry, snapshot and receive-time selection.
Uploaded events alone do not establish complete device history or physical delivery.

The `compact-meal-therapy-v1` envelope declares its exact UTC window, millisecond
units and seven common columns once. Source/quality/context codebooks and payload
field tables avoid repeating JSON object names. Each row retains UUID/revision,
sequence, event offset, observation/receive lag, cohort and unrounded payload
values. Explicit null versus absent payload fields remains distinguishable.
No dose sums, interpolation, absorption estimate or causal meal-bolus link is added.

MEAL and THERAPY CARBS may describe the same food and cannot be summed without
independent linkage. Focus records and generic recent examples can repeat the
same UUID/revision; they are views, not additional events. Estimated GI and
approximate grams retain their original provenance. A DELIVERY_STATE observation
does not retrospectively confirm another recorded bolus.

An incremental byte bound aborts oversized event tables; final canonical encoding
checks the actual 64KiB section limit. The whole supplied-facts section and 96KiB
frame keep their prior limits. Saturation fails preparation without inference or
a partial receipt. A failed builder cannot return a successful partial table;
returned tables do not share mutable lists/context with later projections.

The glucose projector prepares original cohorts/continuity once per invocation
when trying 10/30/60-minute layouts. Each layout receives fresh moment accumulators,
so reuse cannot double supported duration or change original-resolution metrics.
This is request-local reuse, not a cross-device or persistent medical-data cache.

Tests cover exact round trips, all eight therapy payloads, optional nulls,
deterministic input order, >12 meals, device isolation, revision/deletion selection,
receipt binding, capacity refusal, output mutation and single cohort preparation.
Full-model token admission, complete typed user/profile/sensitivity/activity
coverage, host rollout and actual same-device/model acceptance remain separate.

## Synthetic Packet Evidence (2026-10-18)

One local run used minute-rate glucose, three MEAL records and three recorded
BOLUS events per day, plus hourly BASAL observations. All input is synthetic;
glucose includes minute extrema and there is no inference or real profile.
The pure packet includes nonglucose counts/recent examples and the exact event
table. The trusted store path adds normal conversation/selection metadata and
checks its existing five-second transaction budget without extending it.

| Days | CGM records | Meal/therapy events | Pure bytes | Store bytes | Pure/store seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| 3 | 4321 | 90 | 35541 | 36092 | 0.233 / 0.431 |
| 7 | 10081 | 210 | 69298 | 70194 | 0.511 / 1.041 |
| 14 | 20161 | 420 | 87963 | 89276 | 1.253 / 2.309 |

All selected events round-trip exactly; observed extrema and original supported
glucose duration survive. The 14-day layout uses 30-minute older background while
the latest 72 hours retain 10-minute bins. No event table is coarsened or summed.
Store preparation persists a receipt on success. Pure token status is UNVERIFIED;
the store measurement uses a synthetic byte counter, not a production tokenizer.

A separate 14-day case with five-minute BASAL records exceeds the current section
capacity and refuses. This intentionally does not establish support for arbitrarily
dense basal/SMB histories. No cap was raised and no events were silently omitted.
One local timing run is not a production SLA, matched speedup, clinical evaluation
or proof that a real model can interpret every retained field. Earlier runs under
heavy Mac load exceeded the bound; that refusal remains correct. Actual token
accounting and workload admission are still required before host activation.

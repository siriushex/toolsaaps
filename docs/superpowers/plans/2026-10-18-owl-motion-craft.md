# Owl Motion Craft Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing native owl feel expressive and polished through coordinated finite motion, without delaying information or changing app actions.

**Architecture:** Preserve reviewed native paths/palettes and fixed hit areas. Extend the deterministic pose with small body translation/rotation, coordinate gaze/head/wing follow-through and share one waiting choreography between header and chat. Existing draw-only finite clocks, urgent/reduced/power/lifecycle gates remain authoritative.

**Tech Stack:** Existing Kotlin/Compose Canvas, Robolectric native graphics, JUnit and local FFmpeg for a silent visual proof. No new dependency, image decoding in the header, audio generation or live device/server operation.

## Creative Brief

An attentive companion for a frequently used medical tool: warm, precise, calm.
Keep the owl's identity and immediate data presentation; expressive movement is
short context feedback, not a clinical result or request-progress percentage.

| Rule | Choice |
| --- | --- |
| Timing unit | 80ms; existing480..960ms route gestures,720ms header visit cap |
| Easing | Existing FastOutSlowIn asymmetric accent; no new spring dependency |
| Motion family | Anticipation, gentle arc, delayed head/wing settle |
| Intensity | <=8 model units vertical body travel, <=3.5 units horizontal, <=1.3deg body tilt |
| Hierarchy | Owl primary; three waiting marks secondary; text/values stay static |
| Waiting budget | Three diminishing2400ms attention phrases, then static indeterminate state |
| Safety/cost | No loop after7200ms, no new timers/listeners/clinical or network callbacks |

| Scene | Micro-story | Cue |
| --- | --- | --- |
| First overview | Small gather, lift and soft landing | LAUNCH,480ms |
| Return overview | Wing greeting, then head settles | GREET,840ms (header720ms) |
| Forecast | Eyes lead, head follows forward | GAZE,900ms (header720ms) |
| Analytics | Deliberate nod and blink | NOD,720ms |
| AI view | Attentive head tilt | LISTEN,960ms (header720ms) |
| AI waiting | Eyes follow staggered dots, diminishing motion, rest | WAITING,7200ms |
| Menu | Short glance toward the existing drawer | MENU,480ms |

## Tasks

- [x] Add RED-first policy tests for neutral endpoints, visible multi-part movement,
  shared waiting and finite duration before changing production choreography.
- [x] Implement pose/body rendering and deterministic shared waiting marks.
  Connect first overview launch without touching prior meal/onset modifications.
- [x] Update intentional legacy motion expectations; test bounds, smoothness,
  disable/replay/lifecycle/urgent behavior and fixed native target semantics.
- [x] Render actual Compose keyframes and a short silent motion reel. Inspect
  opening/action/rest and small/large artwork without clipping; encode/verify MP4.
- [x] Run focused native tests and full Android test/lint/compile/assemble on the
  combined local tree. Preserve prior dirty work and report skipped/warning counts.
- [ ] Update scoped docs, inspect/stage only this phase, publish existing branch
  and verify exact-head PR/CI. No APK publication/phone install or main merge.

## Fixed Boundaries

Colors/freshness and alert thresholds stay unchanged. Notifications remain static.
Motion never implies a safe meal time, delivered insulin, completed AI response,
acknowledged alarm or glucose improvement. Real busy state owns accessibility.
No treatment, data storage, API, profile or provider operation is added. Preview
labels belong to the visual proof only, not explanatory text added to the app.

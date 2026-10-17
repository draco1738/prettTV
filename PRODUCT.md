# Luma TV

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Android Views and Kotlin, extending the existing MIT-licensed Rivi 0.15 source. No runtime UI dependencies. Android TV only.

## Users

The owner of a TCL Google TV, using a remote from viewing distance.

## Product Purpose

A modern, attractive, lightweight TV home that launches streaming apps smoothly and retains useful show suggestions.

## Operating Context

User-confirmed brief: combine PLUI, Rivi, and the useful parts of stock Google TV; remove unwanted ads and tracking; retain recommendations. Connected device previously identified as TCL G08, Android 14, 32-bit ARM, 1920x1080 UI with a 3840x2160 60 Hz panel. ADB is connected; a signed native build is installed. The first installed C was rejected. The replacement was praised by the owner, who then requested dark app icons, real watch history, longer content rows, a finite row containing all included apps and local title hiding.

## Capabilities and Constraints

Preserve app launching, favorites, hiding and ordering apps, HDMI input access, system settings, and safe Home-button handling. Use available Android TV content-provider rows for recommendations and Continue Watching; provider coverage and permissions must be tested on this TCL. No universal streaming-account integration is assumed. No measured frame-rate claim until hardware testing. All sources, tools, caches and builds stay on E:.

## Brand Commitments

User supplied PLUI and Rivi as explicit visual references and asked for the good parts of stock Google TV. The requested feel is progressive, lightweight and attractive. Luma TV is a provisional local app label, not an approved commercial brand. PLUI code and artwork are analysis references only; build original implementation on Rivi's reusable MIT base.

## Evidence on Hand

Pinned Rivi source and official APK; official PLUI APK decompiled for static analysis; PLUI repository screenshots; previous TV identity and display snapshots. Actual 1920x1080 ADB captures and user photographs of the TV are available in .impeccable/review. The user requires the appearance to match the C mockup.

## Product Principles

- Show real installed apps and real provider content; never invent a watch history.
- Let the screen rest when nothing changes.
- Load artwork progressively, off the UI thread, with bounded caches.
- Keep Home, Back, settings and inputs usable with a remote.
- No analytics, developer registration, advertising identifiers or promotional catalog.

## Accessibility & Inclusion

D-pad navigation, visible focus, readable text from a couch, labels for accessibility, system font scaling and disabled-animation support.

Latest owner adjustments: subtle card-edge contrast, no top Luma wordmark or rail monogram, brand/credits only at the bottom. Live sports includes ESPN schedules and broadcast logos; the installed RBTV+ exposes no verified per-game link, so a game opens the chosen app with channel guidance. SportioTV and HD Streamz are uninstalled; RBTV+ remains. C stays selected on the TV.

# App-local readability checks

`python tests/sizing/verify_readability.py` runs five pure JVM dimension tests with cached E: tools. `compile` mode is an optional scoped source/API compile using the sibling checkout's generated R.jar read-only; a complete Gradle build is stronger integration evidence.

`AppReadabilitySizingAndroidChecks.run(this, ::test)` adds nine authored native checks. It is registered in the full stability runner and `readabilityOnly=true` selection. They cover Standard/invalid defaults, independent system font scale, idempotence, custom dialog exclusions, recycled multi-choice and single-choice state, utility Views, Inputs/app actions, session Undo focus and Search behavior. Compiling this suite does not execute it.

Integration uses `AppReadability.referenceScale(context)` for reference geometry, `dimensions(context).dp/sp` for native dp/sp screens, and `styleDialog(dialog)` only after show. Custom setView bodies are deliberately skipped; MainActivity ZIP input and license body multiply their SP values once, while reference-sized custom content keeps its existing helper. Do not mutate Resources, density or global fontScale, and do not apply nativeContent to already-reference-sized controls. SettingsBackupActivity's scale getter is app-local and rendering after a restore re-reads the saved size.

Both 100% Standard and 104% Larger retain existing A/B compositions. C Balanced Discover proportions are independently owned by the layout workstream. Real-TV fit/readability, clipping and focus require native/device verification of the current artifact.

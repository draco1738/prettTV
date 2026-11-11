# Startup reliability evidence boundaries

`python tests/startup-reliability/compile_checks.py --jvm-checks` performs an offline scoped Kotlin/Android-API compilation with E: cached tools and runs the **23 existing host-JVM** presentation-artwork regressions. Those are not 23 new native passes. Output is under `E:/Development/tcl-tv-tools/snapshots/readability-startup-reliability/scoped`.

`StartupReliabilityAndroidChecks.run(this, ::test)` is registered once per full/selected runner path. It invokes `StartupReliabilityTransportChecks` itself; do not register the companion again. Select with `startupReliabilityOnly=true`.

Exactly seven authored native checks cover:

1. Cold-cache full ContentRepository ordinary publication before latch-blocked optional artwork, with synthetic provider/catalog/sports/episode/team responses.
2. Empty one-shot artwork arming until later eligible catalog content arrives without another start.
3. Fixture Activity stop/start repository cancellation and attached-widget identity/focus.
4. Cleartext allowed only for exact IPv4 loopback `127.0.0.1` in the stability build.
5. Real Android HttpURLConnection slow headers against a watchdog-disabled control.
6. Real Android slow body against an ordinary socket-timeout control.
7. Native header cancellation and stale-hint suppression.

Keep the fixed 1,200 ms transport budget and 500 ms scheduling allowance; transport and total elapsed measurements have different scopes. Do not loosen criteria after a failure. The real native transport checks, including negative controls, require executing instrumentation; host success or APK assembly cannot establish their outcome.

No MainActivity repository/lifecycle integration, physical remote latency, physical sleep/wake or sustained TV soak is established by these fixtures. No production cleartext policy is changed. The fixture uses unique cache/files/preferences roots and closes its sockets/threads/lifecycle callbacks.

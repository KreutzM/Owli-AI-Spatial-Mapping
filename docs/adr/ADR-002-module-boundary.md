# ADR-002: Android app plus pure Kotlin mapping core

**Status:** Accepted

Use `app` for Android/ARCore integration and `mapping-core` for platform-independent algorithms. This modest two-module boundary enables deterministic JVM tests and later extraction/integration without prematurely creating many modules.

# Start here

This repository is already initialized and independently buildable. Use this checklist when contributing or starting an agent run.

1. Check out current `main`, then create a small thematic branch. Do not work directly on `main`.
2. Read `CHATGPT.md`, `AGENTS.md`, `.ai/repo-map.md`, and the issue that defines the work.
3. Install JDK 17 and Android SDK Platform API 36.1. The app compiles against API 36.1 while `targetSdk` remains API 36.
4. Verify the checked-in Gradle 8.13 wrapper JAR before building:

   ```text
   sha256sum gradle/wrapper/gradle-wrapper.jar
   ```

   Expected SHA-256:

   ```text
   81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
   ```

   `scripts/bootstrap-gradle-wrapper.sh` and its pinned checksum remain the trusted recovery path if the JAR is missing.
5. Confirm Gradle starts, then run the default verification:

   ```text
   ./gradlew --version
   python tools/check_architecture_guardrails.py
   ./gradlew :mapping-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace --no-daemon
   ```
6. Run the managed AVD smoke test when the issue touches Android bootstrap, permissions, lifecycle, manifest, or capability behavior:

   ```text
   ./gradlew pixel2Api35DebugAndroidTest \
     -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
     --stacktrace --no-daemon
   ```
7. Keep commits focused, open a Draft PR, and publish the `RUN REVIEW` handoff required by `AGENTS.md`.

The next feature step remains **ARCore session diagnostics**. Do not combine it with Depth, point-cloud, voxel, recording, or mapping work.

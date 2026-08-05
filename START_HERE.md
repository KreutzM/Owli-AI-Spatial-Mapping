# Start here after importing to GitHub

1. Create a private empty repository named `Owli-AI-Spatial-Mapping`.
2. Extract this ZIP into the repository root.
3. Commit all files to a bootstrap branch or directly to the initially empty `main`.
4. Ensure GitHub Actions are enabled.
5. Let `Quality` and `AVD Smoke` run.
6. After `Quality` succeeds, confirm that `WebAgent Repo Pack` publishes the stable artifact `webagent-repo-pack`.
7. Create the MVP tracking issue from `docs/ROADMAP.md`.
8. Begin the next child issue: **ARCore session diagnostics**. Do not start Depth or point-cloud work in the same PR.

The first Gradle run securely downloads and verifies the official Gradle 8.13 wrapper JAR. It is ignored by default; you may commit it after verification if your repository policy prefers the standard checked-in wrapper binary.

# ADR-005: CI-generated WebAgent repo packs

**Status:** Accepted

After successful main-branch quality CI, a separate workflow creates a stable-named `webagent-repo-pack` artifact containing a source ZIP, complete Git bundle, manifest, and SHA-256 checksums. This enables low-friction inspection and local work by web-based agents without weakening runtime repository review.

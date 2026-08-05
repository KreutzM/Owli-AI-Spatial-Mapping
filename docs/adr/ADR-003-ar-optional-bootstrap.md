# ADR-003: AR Optional during bootstrap

**Status:** Accepted

The bootstrap runs on normal devices and CI AVDs and exposes a safe unsupported state. ARCore runtime functionality is gated behind explicit capability/install/permission checks. A later decision may switch a dedicated research distribution to AR Required.

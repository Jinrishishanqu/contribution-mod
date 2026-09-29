---
name: fabric-mod-development
description: Develop and review this repository's Minecraft Fabric mod, especially version upgrades, registry data, tags, event hooks, Mixins, Gradle configuration, and server-only compatibility.
---

# Fabric Mod Development

Treat `design/README.md` as the design entrypoint and follow its links to the relevant feature specification. Preserve decisions already recorded there. Flag contradictions instead of silently inventing behavior.

For version-sensitive work, verify the exact Minecraft, Fabric Loader, Fabric API, Loom, Gradle, and Java versions against primary sources or the installed PCL2 instance before editing. Prefer official Minecraft and Fabric documentation, official source repositories, and generated registries over remembered identifiers.

Keep the implementation server-safe: common entrypoints and resources must not load client-only classes. Put optional client behavior behind a client entrypoint or a separate source set.

Model industry behavior through stable namespaced IDs and data-driven tags. Keep event capture, rule matching, aggregation, persistence, and scheduling in separate components. Event adapters report completed server-side actions; they do not contain economic or industry policy.

When Fabric API lacks a completion event, first confirm that gap for the pinned version. Use the narrowest practical Mixin, document its invariant, and add a focused test or runtime assertion where useful.

Before completing a change:

- validate every JSON resource;
- ensure all referenced tag and industry IDs resolve or are intentionally optional;
- run the Gradle build with Java 25;
- inspect failures rather than weakening checks;
- report any part that remains a scaffold rather than presenting it as implemented.

Do not copy third-party code unless its license is compatible and attribution requirements are satisfied. Record reused code and its source in the project documentation.

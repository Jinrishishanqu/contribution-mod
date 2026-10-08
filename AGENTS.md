# Repository workflow

- Use the Fabric development skill for mod work. Preserve vanilla-client and dedicated-server compatibility.
- Do not change stable industry IDs, published migrations or public API signatures as a style cleanup.
- Java uses four-space AOSP formatting: run `code/run-gradle.ps1 formatJava --offline`.
- Every implementation change must update affected design documents (except user-excluded `design/items` and `design/definitions` when applicable) and `documentation/source/catalog.json` as needed.
- Regenerate **both** `documentation/wiki.html` and `documentation/commands.xlsx` with `code/tools/update-documentation.ps1`. Never update only one artifact.
- `verifyDocumentation` checks all source/resource and scoped design fingerprints plus artifact hashes. Do not weaken or bypass it to make stale documentation pass.
- `run-gradle.ps1 build` refreshes documentation before build and installs successful outputs into the configured local game/server directories. Do not kill processes to release locked files.
- Keep existing user data, spreadsheet edits and original datapack assets. Report tested behavior separately from unmeasured runtime performance.

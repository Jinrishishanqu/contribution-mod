# Third-party dependencies

The built mod embeds the following runtime libraries. Their source code is not copied into this repository.

| Component | Version | License | Project |
| --- | --- | --- | --- |
| HikariCP | 7.1.0 | Apache License 2.0 | <https://github.com/brettwooldridge/HikariCP> |
| MySQL Connector/J | 26.7.0 | GNU GPLv2 with Universal FOSS Exception | <https://github.com/mysql/mysql-connector-j> |
| H2 Database Engine | 2.5.250 | Mozilla Public License 2.0 or Eclipse Public License 1.0 | <https://github.com/h2database/h2database> |
| Flyway Core | 13.5.0 | Apache License 2.0 | <https://github.com/flyway/flyway> |
| Flyway MySQL | 13.5.0 | Apache License 2.0 | <https://github.com/flyway/flyway> |

Fabric Loader, Fabric API, Minecraft-provided Gson and SLF4J are supplied by the runtime and are not re-bundled as additional logging or configuration implementations by this project.

## User-supplied cosmetic assets

The 0.1.4 cosmetic pack uses 22 PNGs supplied in this workspace's prototype datapack: Mark 6 (ironman), nano, and quantum armor. Three humanoid textures are also reused for baby humanoid equipment layers, giving 25 packaged PNGs. Paths and JSON definitions were rewritten under the contribution namespace; no external texture archive or third-party executable was downloaded.

The prototype attributes the nano and quantum designs to Industrial Craft 2. It did not include a separate license or original artist identification for these PNGs. They are used at the project owner's explicit request; this notice does not assert ownership or grant rights beyond the rights held by the project owner. Confirm original asset permissions before independently licensing or redistributing those images. Minecraft textures are referenced by ID, not copied into the cosmetic ZIP.
## Development tooling

google-java-format 1.37.0 (Google, Apache-2.0) is used only for source formatting and validation; it is not included in the mod JAR. Source: https://github.com/google/google-java-format ; license: https://github.com/google/google-java-format/blob/master/LICENSE .

The HTML/XLSX authoring workflow uses the configured @oai/artifact-tool development runtime. It is not distributed with the mod and is not required on Minecraft clients or servers.

## Wiki illustration snapshot

The standalone Wiki copies selected Minecraft textures and model definitions from the project owner's extracted `pack/assets/minecraft` directory, together with current contribution cosmetic assets. Minecraft assets remain the property of their original rights holders. `wiki/content/media.json` records original paths and SHA-256 values; `wiki/assets/images/source` preserves the selected originals, and `icons` contains derived static thumbnails. No external image archive or third-party rendering code is used. The prior cosmetic ZIP behavior is unchanged.

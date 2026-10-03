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

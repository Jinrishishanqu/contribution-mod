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

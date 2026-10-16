# ADR 0001: Eclipse baseline before EDT-specific API

Status: accepted for prototype (2026-10-16).

The first compiled artifact uses public Eclipse Platform APIs (commands, workbench, resources) and a pinned Eclipse 2024-06 baseline. This avoids baking credentials or unverifiable EDT API contracts into initial CI.

The official 1C-Company/dt-example-plugins repository provides a more extensive EDT target for 2026.2 that requires specific p2 artifacts and JDK 25. That target will be adopted only after testing targeted EDT releases and confirming credentials.

We choose Java 17 bundle bytecode for an initial cross-version baseline, Maven Tycho 4.0.13 for OSGi/p2 packaging, and GitHub Actions for independent CI. Until smoke tests pass, neither build success nor source code presence means a release is ready.

References:
- https://github.com/1C-Company/dt-example-plugins
- https://edt.1c.ru/dev/ru/docs/plugins/dev/target-platform/
- https://tycho.eclipseprojects.io/doc/main/PackagingTypes.html

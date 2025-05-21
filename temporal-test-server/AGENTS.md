# temporal-test-server Contributor Guide

This module contains an in-memory implementation of the Temporal service that is used for testing. It is typically consumed indirectly via `TestWorkflowEnvironment` from the **temporal-testing** module. The module can also be compiled into a standalone executable for use by tests written in other languages.

## Directory layout
- `src/main/java` – Java implementation of the test service. The main entry point is `io.temporal.testserver.TestServer`.
- `src/main/proto` – Protobuf definitions for the service plus a `Makefile` and `buf.yaml` for linting and code generation. Generated sources live under `build/generated`.
- `src/main/resources/META-INF/native-image` – Configuration used when building a GraalVM native image.
- `src/test/java` – Unit and functional tests using `SDKTestWorkflowRule`.
- `src/test/resources` – Test utilities such as logging configuration.

## Typical tasks
Run these commands from the repository root.

### Format and build
```bash
./gradlew --offline spotlessApply
./gradlew :temporal-test-server:test
./gradlew :temporal-test-server:build
```


## Notes for contributors
- Do not depend on this module directly from applications. Instead depend on `temporal-testing`.
- The code under `internal` packages is not part of the public API and may be changed freely.
- Java 8 compatibility is required.
- Follow repository wide guidelines in the root `AGENTS.md` for commit style and verification (`spotlessCheck`, running tests, etc.).
- Testing should be done with the workflow service stub in general not the WorkflowClient

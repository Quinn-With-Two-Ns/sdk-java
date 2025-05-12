# Contributor Quickstart Guide

## Repository Layout
- `temporal-sdk`: core SDK implementation.
- `temporal-testing`: utilities to help write workflow and activity tests.
- `temporal-test-server`: in-memory Temporal server for fast tests.
- `temporal-serviceclient`: gRPC client for communicating with the service.
- `temporal-shaded`: prepackaged version of the SDK with shaded dependencies.
- `temporal-spring-boot-autoconfigure`: Spring Boot auto configuration.
- `temporal-kotlin`: Kotlin DSL for the SDK.
- `temporal-opentracing`: OpenTracing interceptor integration.

## General Guidance
- Avoid changing public API signatures. Anything under an `internal` directory
  is not part of the public API and may change freely.
- The SDK code is written for Java 8.

## Building and Testing
1. Format the code before committing:
   ```bash
   ./gradlew --offline spotlessApply
   ```
2. Run the tests. This can take a long time so you may prefer to run individual tests.
   ```bash
   ./gradlew test
   ```
   To run only the core SDK tests or a single test:
   ```bash
   ./gradlew :temporal-sdk:test --offline --tests "io.temporal.workflow.*"
   ./gradlew :temporal-sdk:test --offline --tests "<package.ClassName>"
   ```
3. Build the project:
   ```bash
   ./gradlew clean build
   ```

## Tests
- All tests for this each package is located in `$PACKAGE_NAME/src/test/java/io/temporal`, where `$PACKAGE_NAME` is the name of the package
- Workflow API tests should rely on `SDKTestWorkflowRule` to create a worker and
  register workflows, activities, and nexus services.

## Commit Messages and Pull Requests
- Follow the [Chris Beams](http://chris.beams.io/posts/git-commit/) style for
  commit messages.
- Every pull request should answer:
  - **What changed?**
  - **Why?**
  - **Breaking changes?**
  - **Server PR** (if the change requires a coordinated server update)
- Comments should be complete sentences and end with a period.

## Review Checklist
- `./gradlew spotlessCheck` must pass.
- All tests from `./gradlew test` must succeed.
- Add new tests for any new feature or bug fix.
- Update documentation for user facing changes.

For more details see `CONTRIBUTING.md` in the repository root.

# Layout

The main directory of the repository contains the following folders:
- `temporal-sdk`: This folder contains the SDK code.
- `temporal-testing`: This folder contains the code for helping users test their workflows and activities.
- `temporal-test-server`: This folder contains the code for the time skipping test server. This is an alternate implementation of the Temporal server that supports time skipping for fast testing.
- `temporal-shaded`: This folder contains the code for the shaded Temporal SDK. This is a version of the SDK that is shaded to avoid dependency conflicts with other libraries.
- `temporal-serviceclient`: This folder contains the code for the Temporal service client. This is a client that can be used to communicate with the Temporal server.
- `temporal-sprintg-boot-autoconfigure`: This folder contains the code for the Spring Boot autoconfiguration. This is a library that can be used to automatically configure the Temporal SDK in a Spring Boot application.

# Guide

Generally we should not make any change to the signature of the public API. Classes or interfaces that in an `internal` folder are not considered public API.

# Language

The SDK should be written in Java using Java 8.

# Testing

To format code run:

```bash
./gradlew spotlessApply   
```

To test the SDK run:

```bash
./gradlew --offline temporal-sdk:test 
```

running all tests can take a long time
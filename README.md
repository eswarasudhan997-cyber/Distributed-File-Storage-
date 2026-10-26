# Distributed File Storage and Replication System

A Java 17+ interactive simulation of a distributed file-storage control plane. The current application models file metadata, deduplication, logical block allocation, replica placement, node health, repair/rebalance tasks, a bounded cache, undo operations, an audit log, and six rebuildable B-Tree indexes.

## Requirements

- JDK 17 or newer (`java` and `javac` on PATH).
- MySQL Server and MySQL Connector/J only if using MySQL mode or running the optional real-MySQL test.
- Windows users can use `run.bat` and `test.bat`. Other platforms can use the commands below.

## Build and run

From the repository root:

```bash
mkdir -p out
javac -encoding UTF-8 -d out src/main/java/BTree.java src/main/java/StorageSystem.java
java -cp out StorageSystem
```

On Windows, run `run.bat`. The program lets you select local durable state, MySQL durable state, or an in-memory session. Local/in-memory modes do not require external Java libraries.

## Run tests

```bash
mkdir -p out
javac -encoding UTF-8 -d out src/main/java/BTree.java src/main/java/StorageSystem.java src/test/java/StorageSystemTests.java src/test/java/StorageSystemMySqlTests.java
java -cp out StorageSystemTests
java -cp out StorageSystemMySqlTests
```

The core test suite reports 48 test groups, including 20,000 randomized B-Tree operations. The MySQL test prints `SKIPPED` unless its environment variables are configured; a real database run has not been certified by the ZIP-based integration process.

On Windows, `test.bat` performs the compilation and core suite and invokes the optional MySQL test.

## MySQL setup

1. Run `mysql-setup.sql` using your own MySQL account.
2. Download MySQL Connector/J from the official vendor and place the JAR under `lib/` (the JAR is intentionally not included).
3. Set `STORAGE_DB_PASSWORD` for normal application use, or enter the password when prompted.
4. Select MySQL mode and use `jdbc:mysql://localhost:3306/storage_system` unless your setup differs.

For the optional integration test, configure a dedicated empty test database and set `STORAGE_TEST_DB_URL`, `STORAGE_DB_USER`, and `STORAGE_DB_PASSWORD`. Do not point that test at a production or valuable database: the test intentionally writes records and leaves test data for inspection.

## Sample inputs

`sample-input.txt` and `edge-input.txt` contain interactive console inputs for demos. After compiling, replay on a Unix-like shell with:

```bash
java -cp out StorageSystem < sample-input.txt
java -cp out StorageSystem < edge-input.txt
```

PowerShell:

```powershell
Get-Content .\sample-input.txt | java -cp out StorageSystem
Get-Content .\edge-input.txt | java -cp out StorageSystem
```

## Architecture notes and known alignment gaps

The abstract document is the intended specification. This integrated package preserves the existing implementation and tests; it does not silently claim that all parts of the abstract are complete. The current MySQL adapter persists a serialized application-state snapshot in one `storage_app_state` row rather than separate normalized `files`, `blocks`, `replicas`, `nodes`, and `audit_log` tables. Also, the application's supporting queues/stacks currently use Java collection implementations rather than separate custom `PriorityQueue`, `Stack`, and `Queue` classes. These are explicit follow-up items if strict conformance to the abstract is required. See `docs/INTEGRATION_AUDIT.md`.

## Collaboration

Use a separate feature branch and pull request per contributor. Each person must commit their own actual changes with their own Git identity. A contribution ZIP or a contribution-summary document is not proof of Git authorship. See `docs/CONTRIBUTIONS.md`.

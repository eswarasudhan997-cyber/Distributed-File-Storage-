# Integration Audit

## Inputs audited

- `Eswara_BTree_Contribution.zip` — separately supplied B-Tree contribution package; its Java sources and contribution note match files already retained in the integrated project.
- `Distributed-File-Storage--main.zip` — selected as the main source of truth because it contains a separate reusable `src/main/java/BTree.java` and application/tests already written to use it.
- `Lohit_Persistance_Contribution.zip`
- `Sabari_Persistence_Contribution (1).zip`
- `SriHari_Integration_Testing_CI_Contribution.zip`

## Decisions

1. Preserved `BTree.java`, `StorageSystem.java`, and `StorageSystemTests.java` from the main archive. They compile together and keep the generic B-Tree as a standalone implementation rather than duplicate it as both `BTree` and `StorageSystem.BTree`.
2. Added `StorageSystemMySqlTests.java`, `mysql-setup.sql`, `TEST_CASES.md`, `sample-input.txt`, and `edge-input.txt` from the persistence contribution package.
3. Kept the distinct Eswara, Sabari and Sri Hari contribution notes, added a team contribution plan, and updated the root README for the actual `src/main/java` and `src/test/java` layout.
4. Replaced the minimal original CI script with one that compiles the selected standalone B-Tree, application, core tests, and optional MySQL test. The workflow runs the core tests; a real database test is not provisioned automatically.
5. Did not carry forward `StorageSystem.jar`: compiled output is reproducible from the selected sources and could otherwise be stale or inconsistent. MySQL Connector/J is not bundled; it belongs in the local `lib/` directory and is ignored by Git.

## Eswara contribution package comparison

- `src/main/java/BTree.java`, `src/main/java/StorageSystem.java`, `src/test/java/StorageSystemTests.java`, and `docs/ESWARA_B_TREE_CONTRIBUTION.md` in `Eswara_BTree_Contribution.zip` are byte-for-byte identical to the corresponding files already in this integrated project. No replacement of these Java files was necessary.
- The submitted `.github/workflows/java-tests.yml` and `test.bat` are older/minimal variants. The integrated project retains the more comprehensive versions that also compile and invoke the optional MySQL test, while preserving the core test run.
- This archive confirms which files Eswara's contribution package contains, but ZIP contents alone do not establish the Git commit author or prove that the changes were pushed from Eswara's account.

## Duplicate and conflict findings

- The two supplied persistence ZIPs have identical SHA-256 hashes for every corresponding file inspected. The archive named `Lohit_Persistance_Contribution.zip` even contains a top-level directory named `Sabari_Persistence_Contribution`.
- The Java `StorageSystem.java`, `StorageSystemTests.java`, `StorageSystemMySqlTests.java`, SQL setup, readme and test-case files in the Sabari and Sri Hari archives match byte-for-byte. Their CI workflow files and contribution notes differ.
- The contribution `StorageSystem.java` embeds a nested `StorageSystem.BTree`, while the main archive supplies a separate `BTree.java`. Combining the embedded and standalone variants without choosing one would duplicate the B-Tree implementation and break test references. This integration chose the standalone variant and the matching main core tests.
- ZIP files do not include the original Git commit graph. They cannot prove who authored or pushed a file; use actual per-person commits and reviewed pull requests for that evidence.

## Validation run for this integration

- Compiled the selected `BTree.java`, `StorageSystem.java`, and core tests with `javac`.
- Ran the core test suite: all 48 test groups passed, including 20,000 randomized B-Tree operations.
- Compiled the optional MySQL test successfully.
- Invoked the optional MySQL test without configured environment variables; it reported `SKIPPED`, as expected. A live MySQL server and Connector/J were not available for this audit, so a real database integration test is not claimed as passed.

## Gaps against `Abstract_Document_v2.docx`

1. **MySQL persistence model:** the supplied implementation uses a versioned serialized snapshot in the single `storage_app_state` InnoDB row. The abstract describes durable facts in `files`, `blocks`, `replicas`, `nodes`, and `audit_log` tables. Strict schema-level compliance remains outstanding.
2. **Supporting data structures:** the application uses Java's built-in `PriorityQueue` and `ArrayDeque` for tasks, undo, and audit buffering. The abstract describes four implemented classes — generic B-Tree, Priority Queue, Stack, and Queue. Custom implementations for the three supporting structures are not separately present in these sources.
3. **Lohit contribution evidence:** the supplied archive does not show a distinct replication implementation compared with Sabari's archive. Obtain Lohit's actual branch/repository or have Lohit make and push the genuine assigned replication changes before marking that contribution complete.
4. **Live MySQL validation:** requires a dedicated empty test database, Connector/J, and the documented environment variables; it has not been run here.

These gaps are reported rather than silently repaired in this integration so that the delivered source remains traceable to the provided files.

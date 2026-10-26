# Team Contribution Plan and Integration Status

This file separates assigned responsibilities from work that can be verified in the supplied ZIP contents. Update it with real branch and pull-request links after each contributor pushes their own changes.

| Contributor | Planned responsibility | Branch | Current evidence in supplied files |
|---|---|---|---|
| H. Eswara Sudhan | Generic B-Tree and multi-index behavior | `feature/btree-multi-index` | `Eswara_BTree_Contribution.zip` was compared: `BTree.java`, `StorageSystem.java`, `StorageSystemTests.java`, and the contribution note match the corresponding files already retained in the integrated project. Actual authorship/push history must be verified through GitHub commits or a pull request. |
| Lohit Kumar R E | Replication, failure recovery, repair and rebalancing | `feature/replication-repair` | The provided Lohit archive is byte-for-byte identical to the provided Sabari persistence archive for its listed files; a distinct Lohit implementation cannot be identified from these ZIPs. |
| Sabari | Persistence and MySQL integration | `feature/mysql-persistence` | Archive contains the persistence contribution note, database setup SQL, and optional MySQL tests. Its Java application source matches the Sri Hari archive. |
| Sri Hari A | Integration/regression testing, docs and CI | `test/docs-ci` | Archive contains a CI workflow and contribution documentation; the Java application and test source match the Sabari archive. |

## Working rules

1. Each contributor checks out the shared repository and works on their assigned branch.
2. Commit only changes actually made by that contributor, with the contributor's own configured Git identity.
3. Open a pull request against `main`, describe changed files and tests run, and request a review.
4. Integrate shared-file changes only after coordinating with the file owner.
5. Replace this plan's status descriptions with actual commit and pull-request URLs after the corresponding work is pushed and merged.

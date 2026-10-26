# Test cases and edge cases

Validated with Java 17.0.19 on 26 October 2026. The default application is interactive; these tests are separate and optional.

## Automated test matrix

Run `StorageSystemTests` or double-click `test.bat`. All 48 groups below passed. Tests include assertions on outcomes, allocation accounting, six-index consistency and rollback; they do not merely print example results.

| ID | Scenario / input | Expected result |
|---|---|---|
| T01 | 4 nodes of 100 bytes; upload text `hello` | 3 distinct replicas, 5 bytes each, 15 bytes total |
| T02 | Upload identical text under another name; undo duplicate | Same canonical ID, no extra space; undo preserves original |
| T03 | Mark one replica down; download | Select a healthy replica, return original text |
| T04 | Delete unknown ID 999 | False / harmless missing-file message |
| T05 | Fail one of 3 replicas with a fourth node available; run tasks | Restore 3 healthy replicas; release retired logical allocation |
| T06 | Download then delete a file | Remove file/dedup/cache/task references and release all space |
| T07 | Delete then undo | Restore original ID and content, 3 healthy placements and accounting |
| T08 | Upload empty text | 3 distinct zero-byte placements; no capacity consumed |
| T09 | Upload, repair, then undo upload | Remove current replicas, including replacement placements |
| T10 | Metadata size -1 | Reject before mutation |
| T11 | Empty/whitespace or null filename | Reject |
| T12 | Blank hash or `bad` hash | Reject; require 64 hexadecimal characters |
| T13 | Insert 101 descending keys into degree-2 B-Tree | Every lookup correct; balanced valid tree after splits |
| T14 | Ranges [20,25], [25,20], [120,125] | 6 entries, empty, empty |
| T15 | Delete 100 keys while checking tree after every deletion | Correct old values; borrowing/merging/root shrinking; empty final tree |
| T16 | Search/remove absent key | Null and unchanged size |
| T17 | Only 2 eligible nodes, or third node too small | Reject; no ID consumed, allocation, record, undo entry or task created |
| T18 | All replicas down after caching a download | Reject download, including cache hit |
| T19 | Change health of unknown node | Reject |
| T20 | Reuse existing node ID | Reject; retain original node |
| T21 | Capacity 0 or -1; blank node ID or region | Reject |
| T22 | B-Tree degree 1 or extremely large | Reject; supported degree is 2..1024 |
| T23 | Undo/task processing in empty state | Safe empty-state result |
| T24 | Add N1, N2, N3 | Audit preserves this order |
| T25 | Repair priority 1, verify priority 5, equal-priority sequences | Lower priority number first; ties FIFO by sequence |
| T26 | Fail node with no available replacement | Keep old placement, remain 2/3 healthy, log UNDER_REPLICATED |
| T27 | 3 equally loaded nodes then add an empty fourth node | Queued rebalance actually migrates replicas; squared load decreases; total bytes unchanged |
| T28 | Known hash with inconsistent size; known text `hello` | Reject inconsistent upload; calculated SHA-256 equals known digest |
| T29 | All source replicas down while spare healthy nodes exist | No fabricated replicas; repair resumes after a source returns |
| T30 | Delete, fail node, attempt undo, recover node, retry | Failed undo is atomic and remains pending; retry succeeds |
| T31 | Free 30 bytes and separate 40 bytes; request contiguous 60 | Reject despite 70 total free; after freeing middle extent, coalesce and accept 100-byte exact fit |
| T32 | Cache size 2; access A,B,A,C; undo last download | B evicted; undo restores prior cache; cache size 0 disables caching |
| T33 | Advance check time past timeout; repeat health check | Nodes down; duplicate repair tasks not enqueued; heartbeat restores node |
| T34 | Four nodes across three regions; then one-region cluster | Prefer three different regions when possible; fallback still gives distinct nodes |
| T35 | Files F1..F12, query F2..F10 | 9 results in numeric order, not lexical order |
| T36 | Zero timeout, NaN/out-of-range threshold, negative cache size | Reject configuration |
| T37 | Save, delete, construct fresh application from same saved state, undo | Restore durable history, file, content and allocation |
| T38 | Real disk store; concurrent opener; close/reopen | Atomic saves, exclusive lock, successful reopen and reconstruction |
| T39 | Inject persistence failure during upload | Roll back file/ID/capacity/undo/revision; block further writes until reload |
| T40 | Corrupted state bytes | Reject; never silently reset to empty |
| T41 | Capacity and file size Long.MAX_VALUE | Allocate and free without per-node arithmetic overflow |
| T42 | Seeded 20,000 randomized B-Tree operations, degrees 2..5 | Match TreeMap search/remove/range/entries/size after each operation; structural invariants hold |
| T43 | Unicode node IDs and Euro-sign text | Include all node IDs; UTF-8 size is 3 bytes |
| T44 | Existing metadata-only record then text upload using that hash | Reject unverified conversion; never pretend unknown bytes were checked |
| T45 | Download missing file | Clear missing-file exception/message |
| T46 | Fail, replace, recover old node, delete file | No ghost space usage remains |
| T47 | Seeded 150 mixed application operations | Invariants hold after successful and rejected operations |
| T48 | Replace B-Tree key; null key/value | Replacement keeps size; null rejected |

## Interactive console checks

Both supplied transcript files were passed through the actual `StorageSystem.main` input loop.

### sample-input.txt

Expected and verified:

- Node details come entirely from the input stream.
- `notes.txt` containing `hello` produces F1, 5 bytes, N1/N2/N3 placements.
- `copy.txt` with the same content returns F1.
- Two downloads show MISS then HIT.
- Failing N1 and running all tasks produces 3/3 healthy replicas on N2/N3/N4.
- Delete removes F1; undo restores it.
- Index display shows primary=1, dedup=1, free-block=4, node=4, load=4, cache=1.
- Audit shows repair before lower-priority verification.
- Exit succeeds.

### edge-input.txt

Expected and verified:

- `abc` for a menu number: re-prompt for a whole number.
- `99` as a menu choice: reject without terminating.
- `not-a-number` for capacity: re-prompt.
- Capacity `0`: reject.
- Duplicate N1: reject.
- Upload with only N1 present: reject without a partial file.
- Delete F999: harmless missing-file message.
- Empty undo and task queue: informative messages.
- Unknown node health update: reject.
- B-Tree keys 20 and 25: inclusive range returns both; removing 20 returns its value; subsequent search returns null.

Additional console behavior: EOF during a prompt exits cleanly; an unfinished operation has not been committed. Extremely large numeric input is rejected rather than overflowing into a menu choice.

## MySQL integration checks: separate, not executed here

`StorageSystemMySqlTests.java` is compiled and ready to run with Connector/J and an empty dedicated test database. It checks durable upload/delete/undo/content, reopening through independent connections, revision-conflict rejection, local rollback and absence of a stale write in committed state.

No credentials or JDBC driver were supplied, so **do not describe real MySQL integration as tested**. The standalone suite verifies persistence-failure rollback using an injected failing store; that is not a substitute for a real database outage/commit test. Kill-process and power-loss recovery are also not claimed as tested.

## Edge-case semantics to explain

1. Metadata-only mode cannot detect same-size forged hash/content pairs because no bytes are provided. Text mode computes the hash rather than accepting one from the user.
2. Three recorded placements do not necessarily mean three healthy replicas. Health is evaluated separately.
3. An all-source outage cannot be repaired by merely finding empty destination nodes.
4. Replica count is fixed at three; the application never places two replicas of a file on one node.
5. Free-space fragmentation is meaningful because each replica needs a contiguous extent.
6. Delete undo needs currently available space and healthy nodes. It retains a blocked undo request for a later retry.
7. Rebalance can legitimately perform zero migrations when the cluster is balanced or every candidate violates capacity, replica uniqueness, region diversity or load-improvement rules.
8. Download undo restores access/cache metadata; it cannot retract text already displayed to the user.
9. Each database/local persistence operation saves all related records, audit and undo state together. A persistence error makes the session read-only until restart so an uncertain commit cannot be overwritten.
10. Test fixtures are intentionally repeatable; the normal application accepts user-entered values and does not automatically run these fixtures.

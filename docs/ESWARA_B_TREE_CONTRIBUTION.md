# H. Eswara Sudhan — B-Tree and Multi-Index Contribution

Branch: `feature/btree-multi-index`

## Scope
- Extract the generic B-Tree into `src/main/java/BTree.java` so it can be tested and reused independently.
- Preserve insertion, value replacement, lookup, deletion (borrow/merge), inclusive range query, ordered traversal, first/last entry, size, and structural validation.
- Update `StorageSystem.java` to use the standalone `BTree` implementation.
- Update core tests and build commands to compile the new source file.

## Files changed
- Added `src/main/java/BTree.java`
- Modified `src/main/java/StorageSystem.java`
- Modified `src/test/java/StorageSystemTests.java`
- Modified `.github/workflows/java-tests.yml`
- Modified `test.bat`

## Validate before pushing
```bash
mkdir -p out
javac -encoding UTF-8 -d out src/main/java/BTree.java src/main/java/StorageSystem.java src/test/java/StorageSystemTests.java
java -cp out StorageSystemTests
```

Do not push directly to `main`. Commit this contribution on `feature/btree-multi-index` and open a pull request into `main`.

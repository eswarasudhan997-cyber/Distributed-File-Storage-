# \# Sri Hari — Integration, Regression Testing, Documentation, and CI

#

# \*\*Project:\*\* Distributed File Storage and Replication System using a Multi-Index B-Tree

# \*\*Suggested branch:\*\* `test/docs-ci`

#

# \## Scope of Contribution

#

# Sri Hari's assigned area is to verify that the project modules work together, protect existing behavior through regression tests, document how to build and run the project, prepare demonstration instructions, and automate testing with GitHub Actions.

#

# \## Contribution Areas

#

# \*\*Integration Testing:\*\* Run the main application and verify that the storage and indexing features work together correctly.

#

# \*\*Regression Testing:\*\* Rerun existing tests after changes to detect accidental breakage and ensure that previously working functionality remains intact.

#

# \*\*Documentation:\*\* Maintain setup, usage, and testing instructions.

#

# \*\*Demo Instructions:\*\* Provide clear steps for building, running, and demonstrating the application.

#

# \*\*GitHub Actions CI:\*\* Automate compilation and testing during pushes and pull requests.

#

# \## Relevant Files in This Package

#

# \* `src/main/java/StorageSystem.java` — Main application source used for integration checks.

# \* `src/test/java/StorageSystemTests.java` — Core regression tests.

# \* `README.md` — Project setup and usage documentation.

# \* `TEST\_CASES.md` — Documented test scenarios.

# \* `test.bat` — Windows test runner.

# \* `.github/workflows/java-tests.yml` — GitHub Actions workflow for automated Java tests.

# \* `docs/SRIHARI\_INTEGRATION\_TESTING\_CI\_CONTRIBUTION.md` — This contribution summary.

#

# These files support integration and regression testing. Their inclusion does not imply that every listed source file was originally authored by Sri Hari.

#

# \## How to Run the Tests

#

# Install JDK 17 or newer. Open Command Prompt in the project root directory, where `test.bat` is located.

#

# Run the complete core test suite:

#

# ```bat

# test.bat

# ```

#

# The test script compiles the Java source and executes the core regression tests. The MySQL integration test is separate and requires a configured test database, the appropriate database environment variables, and the MySQL Connector/J driver.

#

# \### Alternative: Manual Compilation

#

# From the project root directory, run:

#

# ```bat

# if not exist out mkdir out

# javac -encoding UTF-8 -d out src/main/java/BTree.java src/main/java/StorageSystem.java src/test/java/StorageSystemTests.java

# java -cp out StorageSystemTests

# ```

#

# The `out` directory contains generated class files and should not be committed to Git.

#

# \## Suggested Git Workflow

#

# Clone the shared repository if you have not already done so:

#

# ```bash

# git clone https://github.com/eswarasudhan997-cyber/Distributed-File-Storage-.git

# cd Distributed-File-Storage-

# ```

#

# Configure Git with your own name and the email associated with your GitHub account:

#

# ```bash

# git config --global user.name "Sri Hari A"

# git config --global user.email "YOUR\_GITHUB\_EMAIL"

# ```

#

# Replace `YOUR\_GITHUB\_EMAIL` with the email associated with your GitHub account. Do not use another contributor's identity.

#

# Create and switch to the assigned branch:

#

# ```bash

# git switch main

# git pull --ff-only origin main

# git switch -c test/docs-ci

# ```

#

# If the branch already exists locally, use:

#

# ```bash

# git switch test/docs-ci

# ```

#

# Review the changes and run the tests before committing. Stage only the files you actually changed.

#

# For example:

#

# ```bash

# git status

# git add README.md TEST\_CASES.md test.bat .github/workflows/java-tests.yml

# git commit -m "test: improve integration testing and CI documentation"

# git push -u origin test/docs-ci

# ```

#

# Adjust the staging command to include only the files you actually changed. Do not commit another contributor's implementation as your own.

#

# After pushing, open a pull request from `test/docs-ci` into `main`. Describe the changes, report the tests you ran, and address any review feedback before the changes are merged.

#

# \## Contribution Note

#

# This package provides the existing project source and tests as a basis for integration and regression testing, together with documentation and a GitHub Actions workflow. It describes Sri Hari's assigned responsibilities; it does not claim that every included source file was personally authored by Sri Hari.

#

# The final contribution record should reflect the work actually completed by each team member. Confirm the division of responsibilities with the team before submitting the pull request.

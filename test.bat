@echo off
setlocal
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out src\main\java\BTree.java src\main\java\StorageSystem.java src\test\java\StorageSystemTests.java src\test\java\StorageSystemMySqlTests.java
if errorlevel 1 goto failed
java -cp "out;lib/*" StorageSystemTests
if errorlevel 1 goto failed
echo.
echo Optional MySQL integration test follows. Without STORAGE_TEST_DB_URL, STORAGE_DB_USER,
echo STORAGE_DB_PASSWORD, it will report SKIPPED. A Connector/J jar is also required in lib\.
java -cp "out;lib/*" StorageSystemMySqlTests
if errorlevel 1 goto failed
exit /b 0
:failed
echo A compilation or test error occurred. See the output above.
exit /b 1

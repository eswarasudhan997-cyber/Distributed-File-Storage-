@echo off
if not exist out mkdir out
javac -encoding UTF-8 -d out src\main\java\BTree.java src\main\java\StorageSystem.java src\test\java\StorageSystemTests.java
if errorlevel 1 exit /b 1
java -cp out StorageSystemTests

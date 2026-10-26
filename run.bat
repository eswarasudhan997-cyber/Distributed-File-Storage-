@echo off
setlocal
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out src\main\java\BTree.java src\main\java\StorageSystem.java
if errorlevel 1 goto failed
java -cp "out;lib/*" StorageSystem
exit /b %errorlevel%
:failed
echo Compilation failed. Install JDK 17 or newer and ensure javac is on PATH.
exit /b 1

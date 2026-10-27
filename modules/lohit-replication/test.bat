@echo off
setlocal
call mvn test
if errorlevel 1 exit /b %ERRORLEVEL%
if not exist target\test-classes mkdir target\test-classes
javac -encoding UTF-8 -cp target\classes -d target\test-classes src\test\java\com\team\storage\replication\ReplicationPolicySelfTest.java
if errorlevel 1 exit /b %ERRORLEVEL%
java -cp target\classes;target\test-classes com.team.storage.replication.ReplicationPolicySelfTest
exit /b %ERRORLEVEL%

@echo off
setlocal
if "%DB_URL%"=="" set "DB_URL=jdbc:mysql://localhost:3306/distributed_storage?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
if "%DB_USER%"=="" set "DB_USER=root"
if "%DB_PASSWORD%"=="" (
  echo Set DB_PASSWORD in this terminal before running. Do not store the password in source code.
  exit /b 1
)
call mvn -q compile exec:java
exit /b %ERRORLEVEL%

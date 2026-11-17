@echo off
setlocal
set "RDCHAT_GRADLE_VERSION=9.4.1"
set "RDCHAT_GRADLE_SHA=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "RDCHAT_GRADLE_CACHE=%GRADLE_USER_HOME%\rdchat-bootstrap"
set "RDCHAT_GRADLE_HOME=%RDCHAT_GRADLE_CACHE%\gradle-%RDCHAT_GRADLE_VERSION%"
if not exist "%RDCHAT_GRADLE_HOME%\bin\gradle.bat" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; $version='9.4.1'; $expected='2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb'; $cache=Join-Path $env:GRADLE_USER_HOME 'rdchat-bootstrap'; $zip=Join-Path $cache ('gradle-'+$version+'-bin.zip'); $home=Join-Path $cache ('gradle-'+$version); New-Item -ItemType Directory -Force -Path $cache | Out-Null; Invoke-WebRequest -Uri ('https://services.gradle.org/distributions/gradle-'+$version+'-bin.zip') -OutFile $zip; $actual=(Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant(); if ($actual -ne $expected) { Remove-Item $zip -Force; throw 'Checksum SHA-256 do Gradle inválido.' }; Expand-Archive -LiteralPath $zip -DestinationPath $cache -Force; Remove-Item $zip -Force; if (!(Test-Path (Join-Path $home 'bin\gradle.bat'))) { throw 'Gradle não foi instalado corretamente.' }"
  if errorlevel 1 exit /b 1
)
call "%RDCHAT_GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%

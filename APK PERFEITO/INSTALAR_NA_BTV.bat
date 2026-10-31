@echo off
chcp 65001 >nul
echo ========================================================
echo     Instalador FreeMyTV - Versao Estavel Perfeita
echo ========================================================
echo Conectando na BTV (192.168.1.6:5188)...

set ADB="C:\Users\luxpi\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v5.0.1\adb.exe"

%ADB% connect 192.168.1.6:5188
echo.
echo Instalando APK no dispositivo...
%ADB% -s 192.168.1.6:5188 install -r "%~dp0FreeMyTV_PERFEITO_ESTAVEL.apk"
echo.
echo Iniciando FreeMyTV...
%ADB% -s 192.168.1.6:5188 shell "am start -n io.freemytv/.HomeActivity"
echo.
echo ========================================================
echo   Instalacao concluida com sucesso!
echo ========================================================
pause

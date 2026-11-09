@echo off
rem Cai bridge tu chay an khi dang nhap Windows (can Node.js va adb trong PATH)
set S=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\TVRemoteBridge.vbs
> "%S%" echo CreateObject("Wscript.Shell").Run "cmd /c cd /d ""%~dp0bridge"" ^&^& node server.js",0,False
wscript "%S%"
echo Da cai: bridge dang chay va se tu chay moi lan dang nhap Windows.
pause

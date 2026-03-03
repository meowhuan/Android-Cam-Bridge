#Requires -Version 7.0
Set-Location "$PSScriptRoot\..\windows\gui\Acb.Gui"
$exe = Join-Path $PWD "bin\Release\net10.0-windows10.0.19041.0\Acb.Gui.exe"
if (Test-Path $exe) {
    Start-Process -FilePath $exe | Out-Null
} else {
    dotnet build -c Release
    if ($LASTEXITCODE -ne 0) {
        throw "GUI build failed."
    }
    Start-Process -FilePath $exe | Out-Null
}

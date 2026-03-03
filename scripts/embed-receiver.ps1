#Requires -Version 7.0
param(
    [string]$ReceiverExe = "F:\droidcam\build\windows\receiver\Release\acb-receiver.exe",
    [string]$OutputFile = "F:\droidcam\windows\gui\Acb.Gui\Generated\BundledReceiverData.g.cs",
    [int]$ChunkSize = 8000
)

if (!(Test-Path $ReceiverExe)) {
    throw "Receiver binary not found: $ReceiverExe"
}

$bytes = [System.IO.File]::ReadAllBytes($ReceiverExe)
$b64 = [Convert]::ToBase64String($bytes)
$outDir = Split-Path -Parent $OutputFile
[System.IO.Directory]::CreateDirectory($outDir) | Out-Null

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("using System;")
[void]$sb.AppendLine("using System.Text;")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("namespace Acb.Gui.Services;")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("internal static partial class BundledReceiverData")
[void]$sb.AppendLine("{")
[void]$sb.AppendLine("    private static partial byte[] GetEmbeddedBytes()")
[void]$sb.AppendLine("    {")
[void]$sb.AppendLine("        var sb = new StringBuilder($($b64.Length));")

for ($i = 0; $i -lt $b64.Length; $i += $ChunkSize) {
    $len = [Math]::Min($ChunkSize, $b64.Length - $i)
    $chunk = $b64.Substring($i, $len)
    [void]$sb.AppendLine("        sb.Append(""$chunk"");")
}

[void]$sb.AppendLine("        return Convert.FromBase64String(sb.ToString());")
[void]$sb.AppendLine("    }")
[void]$sb.AppendLine("}")

[System.IO.File]::WriteAllText($OutputFile, $sb.ToString(), [System.Text.Encoding]::UTF8)
Write-Host "Embedded receiver generated: $OutputFile"

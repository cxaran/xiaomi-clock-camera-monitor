[CmdletBinding()]
param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../X04G.Installer.psm1') -Force
if (-not $OutputDirectory) { $OutputDirectory = Join-Path ([IO.Path]::GetTempPath()) ('x04g-runner-' + [Guid]::NewGuid().ToString('N')) }
$null = [IO.Directory]::CreateDirectory($OutputDirectory)
$probe = Join-Path $OutputDirectory 'argument probe.exe'
$code = @'
using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Runtime.Serialization.Json;
public static class ArgumentProbe {
 public static void Main(string[] args) {
  Console.InputEncoding = new UTF8Encoding(false);
  Console.OutputEncoding = new UTF8Encoding(false);
  if (args.Length == 1 && args[0] == "--hang") { Thread.Sleep(30000); return; }
  using (var memory = new MemoryStream()) {
   new DataContractJsonSerializer(typeof(string[])).WriteObject(memory, args);
   Console.WriteLine(Encoding.UTF8.GetString(memory.ToArray()));
  }
  Console.WriteLine(Convert.ToBase64String(Encoding.UTF8.GetBytes(Console.In.ReadToEnd())));
  Console.WriteLine(Environment.GetEnvironmentVariable("X04G_RUNNER_PROBE"));
 }
}
'@
# Build a genuine .NET Framework console EXE with Windows PowerShell 5.1,
# even when the test itself runs under PowerShell 7. No ADB/USB calls occur.
$source = Join-Path $OutputDirectory 'probe.cs'
[IO.File]::WriteAllText($source, $code, (New-Object Text.UTF8Encoding($false)))
$build = @'
param($Source, $Target)
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition ([IO.File]::ReadAllText($Source)) -OutputAssembly $Target -OutputType ConsoleApplication -ReferencedAssemblies System.Runtime.Serialization,System.Xml
'@
$buildScript = Join-Path $OutputDirectory 'build-probe.ps1'
[IO.File]::WriteAllText($buildScript, $build, (New-Object Text.UTF8Encoding($true)))
& "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File $buildScript -Source $source -Target $probe
if ($LASTEXITCODE -ne 0 -or -not [IO.File]::Exists($probe)) { throw 'Runner probe compilation failed' }
$values = @('', 'simple', 'folder with spaces', 'quote"inside', 'trailing\', 'C:\folder with spaces\', 'a\\"b', 'cámara')
$inputText = "primera línea`nsegunda: áéíóú`n"
$result = Invoke-X04GProcess -FilePath $probe -Arguments $values -InputText $inputText -TimeoutSeconds 15 -Environment @{X04G_RUNNER_PROBE='fixture'}
if ($result.TimedOut -or $result.ExitCode -ne 0) { throw 'Runner probe failed' }
$lines = $result.StdOut -split '\r?\n'
$observed = $lines[0] | ConvertFrom-Json
if ($observed.Count -ne $values.Count) { throw 'Argument count changed' }
for ($i=0; $i -lt $values.Count; $i++) { if ($observed[$i] -cne $values[$i]) { throw ('Argument changed at index ' + $i) } }
$decoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($lines[1]))
if ($decoded -cne $inputText -or $lines[2] -cne 'fixture') { throw 'UTF8 stdin/environment changed' }
$watch = [Diagnostics.Stopwatch]::StartNew()
$timeout = Invoke-X04GProcess -FilePath $probe -Arguments @('--hang') -InputText '' -TimeoutSeconds 1 -Environment @{}
if (-not $timeout.TimedOut -or $timeout.ExitCode -ne -1 -or $watch.Elapsed.TotalSeconds -gt 12) { throw 'Child timeout was not bounded' }
$bounds = $false
try { $null = Invoke-X04GProcess -FilePath $probe -Arguments @() -InputText ('x' * 33000) -TimeoutSeconds 1 -Environment @{} }
catch { $bounds = $_.Exception.Message -eq 'X04G_PROCESS_BOUNDS' }
if (-not $bounds) { throw 'Unbounded stdin accepted' }
[pscustomobject]@{pass=$true;tests=3;arguments=$values.Count;utf8=$true;timeoutBounded=$true;powerShell=$PSVersionTable.PSVersion.ToString();deviceAccessed=$false} | ConvertTo-Json -Compress

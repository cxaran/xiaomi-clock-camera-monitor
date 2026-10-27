Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$script:ModuleId = 'x04g_home_bootstrap_direct01'
$script:Utf8 = New-Object System.Text.UTF8Encoding($false)

function Stop-X04G { param([string]$Code) throw ('X04G_' + $Code) }
function Assert-X04G { param([bool]$Condition, [string]$Code) if (-not $Condition) { Stop-X04G $Code } }
function Get-X04GSha256 {
    param([string]$Path)
    $s = [System.IO.File]::OpenRead($Path)
    $h = [System.Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($h.ComputeHash($s))).Replace('-', '').ToLowerInvariant() }
    finally { $h.Dispose(); $s.Dispose() }
}
function Get-X04GBytesSha256 {
    param([byte[]]$Bytes)
    $h = [System.Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($h.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $h.Dispose() }
}
function Test-X04GManifest {
    [CmdletBinding()] param([object]$Manifest)
    Assert-X04G ($null -ne $Manifest -and $Manifest.schema -eq 'x04g.installer.v1') 'MANIFEST_SCHEMA'
    Assert-X04G ($Manifest.app.package -eq 'org.x04g.home' -and
        $Manifest.module.id -eq $script:ModuleId) 'PACKAGE_IDENTITY'
    Assert-X04G ($Manifest.app.versionName -eq '0.1.0-rc.2' -and [long]$Manifest.app.versionCode -ge 3 -and
        $Manifest.module.version -eq '0.1.0-rc.2' -and [long]$Manifest.module.versionCode -eq 4) 'RELEASE_VERSION'
    foreach ($item in @($Manifest.app, $Manifest.module)) {
        Assert-X04G ([string]$item.sha256 -cmatch '^[0-9a-f]{64}$') 'MANIFEST_HASH'
        Assert-X04G ([long]$item.bytes -gt 0 -and [long]$item.bytes -le 8388608) 'ARTIFACT_SIZE'
        Assert-X04G ([string]$item.path -cmatch '^[A-Za-z0-9_./-]+$' -and
            -not ([string]$item.path).StartsWith('/') -and ([string]$item.path -notmatch '(^|/)\.\.(/|$)')) 'ARTIFACT_PATH'
    }
    Assert-X04G ([string]$Manifest.app.signerSha256 -cmatch '^[0-9a-f]{64}$') 'SIGNER_HASH'
    Assert-X04G ($Manifest.oem.package -eq 'com.google.assistant.launcher' -and
        $Manifest.oem.process -eq 'com.google.android.apps.mediashell' -and
        $Manifest.oem.activePath -eq '/product/app/com.google.assistant.launcher/app.apk' -and
        [long]$Manifest.oem.versionCode -eq 111706 -and
        $Manifest.oem.versionName -eq '11.51.6.416171605' -and
        $Manifest.oem.sha256 -eq '1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21') 'OEM_MANIFEST'
    $true
}
function Resolve-X04GPackageFile {
    param([string]$Root, [string]$Relative)
    Assert-X04G (-not [IO.Path]::IsPathRooted($Relative) -and $Relative -notmatch '(^|[\\/])\.\.([\\/]|$)') 'PACKAGE_PATH'
    $base = [IO.Path]::GetFullPath($Root).TrimEnd([char[]]'\/') + [IO.Path]::DirectorySeparatorChar
    $path = [IO.Path]::GetFullPath((Join-Path $base $Relative))
    Assert-X04G ($path.StartsWith($base, [StringComparison]::OrdinalIgnoreCase) -and [IO.File]::Exists($path)) 'PACKAGE_FILE'
    $node = Get-Item -LiteralPath $path
    Assert-X04G (($node.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) 'PACKAGE_LINK'
    $path
}
function Test-X04GArtifactPair {
    param([object]$Manifest, [string]$Root)
    $null = Test-X04GManifest $Manifest
    $apk = Resolve-X04GPackageFile $Root $Manifest.app.path
    $module = Resolve-X04GPackageFile $Root $Manifest.module.path
    foreach ($pair in @(@($apk, $Manifest.app), @($module, $Manifest.module))) {
        Assert-X04G ((Get-Item -LiteralPath $pair[0]).Length -eq [long]$pair[1].bytes -and
            (Get-X04GSha256 $pair[0]) -eq $pair[1].sha256) 'ARTIFACT_HASH'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($module)
    try {
        Assert-X04G ($archive.Entries.Count -eq 13) 'ZIP_COUNT'
        $seen = @{}
        $allowedEntries = @('disable', 'skip_mount', 'payload.dex', 'zygisk/armeabi-v7a.so', 'module.prop', 'provenance.json',
            'customize.sh', 'META-INF/com/google/android/update-binary', 'META-INF/com/google/android/updater-script',
            'licenses/project-MIT.txt', 'licenses/magisk-installer-GPL-3.0.txt', 'licenses/zygisk-ISC.txt', 'licenses/nayuki-qr-MIT.txt')
        foreach ($entry in $archive.Entries) {
            Assert-X04G (-not $seen.ContainsKey($entry.FullName) -and $entry.FullName -notmatch '(^/|\\|(^|/)\.\.(/|$))' -and
                $entry.Length -le 4194304 -and $allowedEntries -ccontains $entry.FullName -and
                ((([long]$entry.ExternalAttributes -shr 16) -band 61440) -in @(0, 32768))) 'ZIP_ENTRY'
            $seen[$entry.FullName] = $true
        }
        Assert-X04G ($seen.ContainsKey('disable') -and $seen.ContainsKey('skip_mount') -and
            -not $seen.ContainsKey('uid.pin') -and $archive.GetEntry('disable').Length -eq 0 -and
            $archive.GetEntry('skip_mount').Length -eq 0) 'ZIP_DISABLED_CONTRACT'
        $readEntry = {
            param($name)
            $entry = $archive.GetEntry($name)
            Assert-X04G ($null -ne $entry -and $entry.Length -le 4194304) 'ZIP_REQUIRED_ENTRY'
            $s = $entry.Open(); $m = New-Object IO.MemoryStream
            try { $s.CopyTo($m); ,$m.ToArray() } finally { $m.Dispose(); $s.Dispose() }
        }
        $provenance = $script:Utf8.GetString((& $readEntry 'provenance.json')) | ConvertFrom-Json
        Assert-X04G ($provenance.apk_sha256 -eq $Manifest.app.sha256 -and
            [long]$provenance.apk_bytes -eq [long]$Manifest.app.bytes -and
            $provenance.apk_signer_sha256 -eq $Manifest.app.signerSha256 -and
            $provenance.apk_package -eq $Manifest.app.package -and
            [long]$provenance.apk_version_code -eq [long]$Manifest.app.versionCode -and
            $provenance.apk_version_name -eq $Manifest.app.versionName -and
            $provenance.initially_disabled -eq $true -and $provenance.uid_pin_included -eq $false -and
            $provenance.stock_launcher_sha256 -eq $Manifest.oem.sha256) 'ZIP_APK_PIN_MISMATCH'
        $payload = & $readEntry 'payload.dex'; $native = & $readEntry 'zygisk/armeabi-v7a.so'
        Assert-X04G ((Get-X04GBytesSha256 $payload) -eq $provenance.payload_sha256 -and
            (Get-X04GBytesSha256 $native) -eq $provenance.native_sha256) 'ZIP_PAYLOAD_HASH'
        Assert-X04G ($native.Length -ge 52 -and $native[0] -eq 127 -and $native[1] -eq 69 -and
            $native[2] -eq 76 -and $native[3] -eq 70 -and $native[4] -eq 1 -and $native[5] -eq 1 -and
            [BitConverter]::ToUInt16($native, 18) -eq 40) 'ZIP_ARM32'
        $prop = $script:Utf8.GetString((& $readEntry 'module.prop'))
        Assert-X04G ($prop -match ('(?m)^id=' + [regex]::Escape($Manifest.module.id) + '\r?$') -and
            $prop -match ('(?m)^versionCode=' + [regex]::Escape([string]$Manifest.module.versionCode) + '\r?$') -and
            $prop -match ('(?m)^version=' + [regex]::Escape($Manifest.module.version) + '\r?$')) 'ZIP_MODULE_VERSION'
        [pscustomobject]@{ Apk = $apk; Module = $module; PayloadSha = $provenance.payload_sha256; NativeSha = $provenance.native_sha256 }
    } finally { $archive.Dispose() }
}
function Select-X04GUsbDevice {
    [CmdletBinding()] param([string]$DeviceText, [string[]]$UsbSerials, [string]$Serial)
    $devices = @()
    foreach ($line in ($DeviceText -split '\r?\n')) {
        if (-not $line.Trim() -or $line -match '^List of devices attached') { continue }
        Assert-X04G ($line -match '^(\S+)\s+(device|unauthorized|offline|recovery|sideload|bootloader)(?:\s|$)') 'ADB_INVENTORY_FORMAT'
        $devices += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
    }
    if ($Serial) {
        Assert-X04G ($Serial -cmatch '^[A-Za-z0-9._-]{1,80}$' -and $Serial -notmatch '^emulator-') 'SERIAL_NOT_USB'
        $selected = @($devices | Where-Object { $_.Serial -ceq $Serial })
    } else {
        Assert-X04G ($devices.Count -eq 1) 'SELECT_ONE_USB_DEVICE'
        $selected = @($devices)
    }
    Assert-X04G ($selected.Count -eq 1 -and $selected[0].Serial -cmatch '^[A-Za-z0-9._-]{1,80}$' -and
        $selected[0].Serial -notmatch '^emulator-' -and $UsbSerials -ccontains $selected[0].Serial) 'SERIAL_NOT_USB'
    Assert-X04G ($selected[0].State -eq 'device') 'ADB_NOT_AUTHENTICATED'
    $selected[0].Serial
}
function Get-X04GUsbInventory {
    $result = @()
    foreach ($node in @(Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -match '^USB\\VID_0E8D&PID_201D&MI_01\\' })) {
        $service = (Get-PnpDeviceProperty -InstanceId $node.InstanceId -KeyName DEVPKEY_Device_Service).Data
        Assert-X04G ($node.Status -eq 'OK' -and $service -eq 'WinUSB') 'USB_DRIVER_NOT_READY'
        $id = $node.InstanceId
        for ($i = 0; $i -lt 4; $i++) {
            $id = [string](Get-PnpDeviceProperty -InstanceId $id -KeyName DEVPKEY_Device_Parent).Data
            if ($id -match '^USB\\VID_0E8D&PID_201D\\([A-Za-z0-9._-]{1,80})$') { $result += $Matches[1]; break }
        }
    }
    @($result | Select-Object -Unique)
}
function ConvertTo-X04GArgument {
    param([string]$Value)
    $b = New-Object Text.StringBuilder; $null = $b.Append('"'); $slashes = 0
    foreach ($c in $Value.ToCharArray()) {
        if ($c -eq '\') { $slashes++; continue }
        if ($c -eq '"') { $null = $b.Append(('\' * ($slashes * 2 + 1))); $null = $b.Append('"') }
        else { if ($slashes) { $null = $b.Append(('\' * $slashes)) }; $null = $b.Append($c) }
        $slashes = 0
    }
    if ($slashes) { $null = $b.Append(('\' * ($slashes * 2))) }; $null = $b.Append('"'); $b.ToString()
}
function Invoke-X04GProcess {
    param([string]$FilePath, [string[]]$Arguments, [string]$InputText, [int]$TimeoutSeconds, [hashtable]$Environment)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $FilePath; $info.Arguments = (($Arguments | ForEach-Object { ConvertTo-X04GArgument $_ }) -join ' ')
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true; $info.RedirectStandardInput = $true
    if ($info.PSObject.Properties.Name -contains 'StandardOutputEncoding') { $info.StandardOutputEncoding = $script:Utf8 }
    if ($info.PSObject.Properties.Name -contains 'StandardErrorEncoding') { $info.StandardErrorEncoding = $script:Utf8 }
    foreach ($name in $Environment.Keys) { $info.EnvironmentVariables[$name] = [string]$Environment[$name] }
    $p = New-Object Diagnostics.Process; $p.StartInfo = $info
    try {
        Assert-X04G ($TimeoutSeconds -ge 1 -and $TimeoutSeconds -le 180 -and $InputText.Length -le 32768) 'PROCESS_BOUNDS'
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $null = $p.Start(); $out = $p.StandardOutput.ReadToEndAsync(); $err = $p.StandardError.ReadToEndAsync()
        $timedOut = $false
        if ($InputText) {
            # StandardInputEncoding is absent in Windows PowerShell 5.1/.NET
            # Framework. Write BOM-free UTF8 directly to the redirected pipe.
            $inputBytes = $script:Utf8.GetBytes($InputText.Replace("`r`n", "`n"))
            $write = $p.StandardInput.BaseStream.WriteAsync($inputBytes, 0, $inputBytes.Length)
            $timedOut = -not $write.Wait([Math]::Max(1, $TimeoutSeconds * 1000 - [int]$watch.ElapsedMilliseconds))
        }
        if (-not $timedOut) { $p.StandardInput.BaseStream.Close(); $timedOut = -not $p.WaitForExit([Math]::Max(1, $TimeoutSeconds * 1000 - [int]$watch.ElapsedMilliseconds)) }
        if ($timedOut) { try { $p.Kill() } catch {}; $null = $p.WaitForExit(5000) }
        Assert-X04G ($out.Wait(5000) -and $err.Wait(5000)) 'PROCESS_READ_TIMEOUT'
        $stdout = $out.GetAwaiter().GetResult(); $stderr = $err.GetAwaiter().GetResult()
        Assert-X04G ($stdout.Length -le 2097152 -and $stderr.Length -le 2097152) 'PROCESS_OUTPUT_LIMIT'
        [pscustomobject]@{ ExitCode = $(if ($timedOut) { -1 } else { $p.ExitCode }); StdOut = $stdout; StdErr = $stderr; TimedOut = $timedOut }
    } finally { $p.Dispose() }
}
function Invoke-X04GAdb {
    param($Context, [string[]]$Arguments, [string]$InputText = '', [int]$Timeout = 45, [switch]$Inventory)
    $values = @('-H', '127.0.0.1', '-P', '5142')
    if (-not $Inventory) { $values += @('-s', $Context.Serial) }; $values += $Arguments
    $r = & $Context.Runner $Context.AdbPath $values $InputText $Timeout $Context.Environment
    Assert-X04G ($null -ne $r -and -not $r.TimedOut) 'ADB_TIMEOUT'
    Assert-X04G ($r.ExitCode -eq 0) 'ADB_COMMAND_FAILED'
    [string]$r.StdOut
}
function Invoke-X04GShell {
    param($Context, [string]$Script, [switch]$Root, [int]$Timeout = 45)
    $commandArguments = @('shell', 'sh', '-s')
    if ($Root) { $commandArguments = @('shell', "su -c 'sh -s'") }
    Invoke-X04GAdb $Context $commandArguments ($Script.Replace("`r`n", "`n") + "`n") $Timeout
}
function ConvertFrom-X04GFields {
    param([string]$Text)
    $r = @{}
    foreach ($line in ($Text -split '\r?\n')) {
        if (-not $line) { continue }
        Assert-X04G ($line -cmatch '^([a-z_]+)=([ -~]*)$' -and -not $r.ContainsKey($Matches[1])) 'PROBE_FORMAT'
        $r[$Matches[1]] = $Matches[2]
    }
    $r
}
function Get-X04GProbeScript {
@'
# X04G:PROBE
set -eu
uid_of() { pm list packages -U "$1" | awk -v p="package:$1" '$1==p { for(i=2;i<=NF;i++) if($i ~ /^uid:[0-9]+$/) {sub(/^uid:/,"",$i); print $i} }'; }
code_of() { dumpsys package "$1" | sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' | sort -u; }
name_of() { dumpsys package "$1" | sed -n 's/^[[:space:]]*versionName=\(.*\)$/\1/p' | sort -u; }
path_of() { pm path "$1" | sed 's/^package://'; }
for pair in 'model ro.product.model' 'device ro.product.device' 'api ro.build.version.sdk' 'abi ro.product.cpu.abi' 'boot_completed sys.boot_completed' 'adb_secure ro.adb.secure'; do
 set -- $pair; printf '%s=%s\n' "$1" "$(getprop "$2")"
done
printf 'shell_uid=%s\n' "$(id -u)"
printf 'boot_id=%s\n' "$(cat /proc/sys/kernel/random/boot_id)"
printf 'selinux=%s\n' "$(getenforce)"
printf 'display=%s\n' "$(wm size | tr '\n' '|')"
printf 'oem_path=%s\n' "$(path_of com.google.assistant.launcher)"
printf 'oem_sha=%s\n' "$(sha256sum /product/app/com.google.assistant.launcher/app.apk | awk '{print $1}')"
printf 'oem_version_code=%s\n' "$(code_of com.google.assistant.launcher)"
printf 'oem_version_name=%s\n' "$(name_of com.google.assistant.launcher)"
printf 'launcher_uid=%s\n' "$(uid_of com.google.assistant.launcher)"
printf 'cast_uid=%s\n' "$(uid_of com.google.android.apps.mediashell)"
p=$(path_of org.x04g.home)
if [ -n "$p" ]; then
 case "$p" in /data/app/*/base.apk) ;; *) exit 70;; esac
 printf 'app_path=%s\n' "$p"
 printf 'app_sha=%s\n' "$(sha256sum "$p" | awk '{print $1}')"
 printf 'app_version_code=%s\n' "$(code_of org.x04g.home)"
 printf 'app_version_name=%s\n' "$(name_of org.x04g.home)"
else printf 'app_path=NONE\napp_sha=NONE\napp_version_code=0\napp_version_name=NONE\n'; fi
'@
}
function Get-X04GRootProbeScript {
@'
# X04G:SU
set -eu
[ "$(id -u)" = 0 ]
for base in /data/adb /data/adb/modules /data/adb/modules_update; do
 [ ! -L "$base" ]
 if [ -e "$base" ]; then [ -d "$base" ] && [ "$(stat -c '%u:%g' "$base")" = 0:0 ]; fi
done
printf 'su_root=1\n'
printf 'magisk=%s\n' "$(magisk -V)"
printf 'zygisk=%s\n' "$(magisk --sqlite "SELECT value FROM settings WHERE key='zygisk';" 2>/dev/null)"
rc=0; magisk --denylist status >/dev/null 2>&1 || rc=$?
case "$rc" in 0) enforced=1;; 1) enforced=0;; *) exit 69;; esac
printf 'denylist_enforced=%s\n' "$enforced"
if magisk --denylist ls 2>/dev/null | grep -E '(^|[|[:space:]])com\.google\.(assistant\.launcher|android\.apps\.mediashell)([|[:space:]]|$)' >/dev/null; then listed=1; else listed=0; fi
printf 'denylist_target=%s\n' "$listed"
for label in module update; do
 if [ "$label" = module ]; then d=/data/adb/modules/x04g_home_bootstrap_direct01; else d=/data/adb/modules_update/x04g_home_bootstrap_direct01; fi
 [ ! -L "$d" ]
 if [ -e "$d" ]; then
  [ -d "$d" ] && [ "$(stat -c '%u:%g' "$d")" = 0:0 ]
  printf '%s_present=1\n' "$label"
  if [ -f "$d/disable" ] && [ ! -L "$d/disable" ]; then printf '%s_disabled=1\n' "$label"; else printf '%s_disabled=0\n' "$label"; fi
  marker="$d/x04g-installer-managed.txt"
  if [ -f "$marker" ] && [ ! -L "$marker" ] && [ "$(stat -c '%u:%g:%a:%h' "$marker")" = 0:0:600:1 ] && [ "$(head -n 1 "$marker")" = x04g-installer-v1 ]; then
   printf '%s_managed=1\n' "$label"
   for field in apk_sha signer_sha module_sha version_code; do
    value=$(sed -n "s/^$field=//p" "$marker")
    printf '%s_%s=%s\n' "$label" "$field" "$value"
   done
   printf '%s_payload_sha=%s\n' "$label" "$(sha256sum "$d/payload.dex" | awk '{print $1}')"
   printf '%s_native_sha=%s\n' "$label" "$(sha256sum "$d/zygisk/armeabi-v7a.so" | awk '{print $1}')"
   if [ -f "$d/uid.pin" ] && [ ! -L "$d/uid.pin" ]; then
    printf '%s_uid_pin=%s\n' "$label" "$(cat "$d/uid.pin")"
    printf '%s_uid_metadata=%s\n' "$label" "$(stat -c '%u:%g:%a:%h:%s' "$d/uid.pin")"
   fi
  else printf '%s_managed=0\n' "$label"; fi
 else printf '%s_present=0\n' "$label"; fi
done
'@
}
function Test-X04GCompatibility {
    [CmdletBinding()] param([hashtable]$Probe, $Manifest)
    Assert-X04G ($Probe.model -eq 'X04G' -and $Probe.device -eq 'mico_x04g' -and $Probe.api -eq '29' -and
        $Probe.abi -eq 'armeabi-v7a' -and $Probe.boot_completed -eq '1' -and $Probe.shell_uid -eq '2000') 'DEVICE_COMPATIBILITY'
    Assert-X04G ($Probe.adb_secure -eq '1' -and $Probe.selinux -eq 'Enforcing') 'SECURITY_STATE'
    Assert-X04G ($Probe.display -match '^Physical size: 800x480\|(?:Override size: 800x480\|)?$') 'DISPLAY'
    Assert-X04G ($Probe.oem_path -eq $Manifest.oem.activePath -and $Probe.oem_sha -eq $Manifest.oem.sha256 -and
        [long]$Probe.oem_version_code -eq [long]$Manifest.oem.versionCode -and
        $Probe.oem_version_name -eq $Manifest.oem.versionName) 'OEM_ACTIVE_PACKAGE'
    Assert-X04G ($Probe.launcher_uid -cmatch '^1[0-9]{4}$' -and $Probe.launcher_uid -eq $Probe.cast_uid) 'SHARED_UID'
    Assert-X04G ($Probe.su_root -eq '1' -and $Probe.magisk -cmatch '^[0-9]{5,6}$' -and
        [long]$Probe.magisk -ge 26000 -and $Probe.zygisk -eq 'value=1') 'MAGISK_ZYGISK'
    Assert-X04G (-not ($Probe.denylist_enforced -eq '1' -and $Probe.denylist_target -eq '1')) 'DENYLIST_TARGET'
    $true
}
function Get-X04GInstallPlan {
    [CmdletBinding()] param([hashtable]$Existing, $Manifest, $Pair, [switch]$Install, [switch]$Upgrade, [switch]$NoReboot)
    $present = @('module', 'update' | Where-Object { $Existing[($_ + '_present')] -eq '1' })
    $resume = $false
    if ($present.Count) {
        foreach ($label in $present) {
            Assert-X04G ($Existing[($label + '_managed')] -eq '1' -and $Existing[($label + '_disabled')] -eq '1') 'EXISTING_MODULE_NOT_DISABLED_MANAGED'
            Assert-X04G ($Existing[($label + '_signer_sha')] -eq $Manifest.app.signerSha256 -and
                $Existing[($label + '_version_code')] -cmatch '^[0-9]{1,8}$' -and
                [long]$Existing[($label + '_version_code')] -le [long]$Manifest.module.versionCode) 'MANAGED_UPGRADE_IDENTITY'
        }
        $newest = if ($present -contains 'update') { 'update' } else { 'module' }
        Assert-X04G ($Existing[($newest + '_apk_sha')] -eq $Existing.app_sha) 'MANAGED_APP_HASH'
        $resume = $Existing[($newest + '_module_sha')] -eq $Manifest.module.sha256 -and
            $Existing[($newest + '_payload_sha')] -eq $Pair.PayloadSha -and
            $Existing[($newest + '_native_sha')] -eq $Pair.NativeSha -and $Existing.app_sha -eq $Manifest.app.sha256
        Assert-X04G ($resume -or $Upgrade) 'EXPLICIT_UPGRADE_REQUIRED'
        Assert-X04G ($resume -or $Existing.update_present -ne '1') 'DIFFERENT_PENDING_UPDATE'
    }
    if ($Existing.app_sha -ne 'NONE' -and $Existing.app_sha -ne $Manifest.app.sha256) {
        Assert-X04G ($Upgrade -and $present.Count -gt 0) 'EXISTING_APP_NOT_MANAGED'
    }
    [pscustomobject]@{ Mode = $(if ($resume) { 'Resume' } elseif ($present.Count) { 'Upgrade' } else { 'Fresh' });
        Mutate = [bool]$Install; InstallApk = ($Existing.app_sha -ne $Manifest.app.sha256);
        InstallModule = (-not $resume); Activate = ([bool]$Install -and -not $NoReboot); Reboot = ([bool]$Install -and -not $NoReboot) }
}

function New-X04GDisableScript {
@'
# X04G:DISABLE
set -eu
[ "$(id -u)" = 0 ]
for d in /data/adb/modules/x04g_home_bootstrap_direct01 /data/adb/modules_update/x04g_home_bootstrap_direct01; do
 [ ! -L "$d" ]
 if [ -d "$d" ]; then
  [ "$(stat -c '%u:%g' "$d")" = 0:0 ]
  [ "$(sed -n 's/^id=//p' "$d/module.prop")" = x04g_home_bootstrap_direct01 ]
  [ ! -L "$d/disable" ]
  if [ -e "$d/disable" ]; then [ -f "$d/disable" ] && [ "$(stat -c '%h' "$d/disable")" = 1 ]; fi
  umask 077; : > "$d/disable"; chown 0:0 "$d/disable"; chmod 0600 "$d/disable"
  [ -f "$d/disable" ] && [ "$(stat -c '%u:%g:%h:%s' "$d/disable")" = 0:0:1:0 ]
 fi
done
printf 'X04G_DISABLED\n'
'@
}
function New-X04GPinScript {
    param($Manifest, $Pair, [string]$ExpectedUid)
    Assert-X04G ($ExpectedUid -cmatch '^1[0-9]{4}$') 'EXPECTED_UID'
    $text = @'
# X04G:PIN
set -eu
[ "$(id -u)" = 0 ]
uid_of() { pm list packages -U "$1" | awk -v p="package:$1" '$1==p {for(i=2;i<=NF;i++)if($i~/^uid:[0-9]+$/){sub(/^uid:/,"",$i);print $i}}'; }
u=$(uid_of com.google.assistant.launcher); c=$(uid_of com.google.android.apps.mediashell)
[ "$u" = "$c" ]
[ "$u" = '@EXPECTED_UID@' ]
case "$u" in 1[0-9][0-9][0-9][0-9]) ;; *) exit 71;; esac
[ "$(pm path com.google.assistant.launcher)" = package:/product/app/com.google.assistant.launcher/app.apk ]
[ "$(sha256sum /product/app/com.google.assistant.launcher/app.apk | awk '{print $1}')" = '@OEM_SHA@' ]
app=$(pm path org.x04g.home | sed 's/^package://')
case "$app" in /data/app/*/base.apk) ;; *) exit 72;; esac
[ "$(sha256sum "$app" | awk '{print $1}')" = '@APP_SHA@' ]
count=0
for d in /data/adb/modules/x04g_home_bootstrap_direct01 /data/adb/modules_update/x04g_home_bootstrap_direct01; do
 [ ! -L "$d" ]
 if [ -d "$d" ]; then
  [ "$(stat -c '%u:%g' "$d")" = 0:0 ]
  [ -f "$d/disable" ] && [ ! -L "$d/disable" ]
  [ ! -L "$d/uid.pin" ] && [ ! -e "$d/uid.pin.new" ]
  umask 077; printf '%s\n' "$u" > "$d/uid.pin.new"
  chown 0:0 "$d/uid.pin.new"; chmod 0600 "$d/uid.pin.new"; chcon u:object_r:system_file:s0 "$d/uid.pin.new"
  [ "$(stat -c '%u:%g:%a:%h:%s' "$d/uid.pin.new")" = 0:0:600:1:6 ]
  mv "$d/uid.pin.new" "$d/uid.pin"
  [ "$(cat "$d/uid.pin")" = "$u" ]
  count=$((count+1))
 fi
done
[ "$count" -ge 1 ]
printf 'X04G_PINNED\n'
'@
    $text.Replace('@OEM_SHA@', $Manifest.oem.sha256).Replace('@APP_SHA@', $Manifest.app.sha256).Replace('@EXPECTED_UID@', $ExpectedUid)
}
function New-X04GModuleScript {
    param($Manifest, $Pair, [string]$Stage, [string]$ScriptSha, [string]$RootStage)
    $text = @'
# X04G:INSTALL_MODULE
set -eu
[ "$(id -u)" = 0 ]
SOURCE='@STAGE@'
ROOT='@ROOT_STAGE@'
[ ! -L /data/adb/x04g-installer ]
if [ ! -d /data/adb/x04g-installer ]; then umask 077; mkdir /data/adb/x04g-installer; fi
[ "$(stat -c '%u:%g:%a' /data/adb/x04g-installer)" = 0:0:700 ]
[ ! -e "$ROOT" ] && [ ! -L "$ROOT" ]
umask 077; mkdir "$ROOT"
for file in app.apk module.zip install.sh; do
 [ -f "$SOURCE/$file" ] && [ ! -L "$SOURCE/$file" ]
 cp "$SOURCE/$file" "$ROOT/$file"
 chown 0:0 "$ROOT/$file"; chmod 0600 "$ROOT/$file"
 [ "$(stat -c '%u:%g:%h' "$ROOT/$file")" = 0:0:1 ]
done
[ "$(sha256sum "$ROOT/app.apk" | awk '{print $1}')" = '@APP_SHA@' ]
[ "$(sha256sum "$ROOT/module.zip" | awk '{print $1}')" = '@MODULE_SHA@' ]
[ "$(sha256sum "$ROOT/install.sh" | awk '{print $1}')" = '@SCRIPT_SHA@' ]
[ "$(wc -c < "$ROOT/app.apk" | tr -d ' ')" = '@APP_BYTES@' ]
[ "$(wc -c < "$ROOT/module.zip" | tr -d ' ')" = '@MODULE_BYTES@' ]
exec sh "$ROOT/install.sh"
'@
    $text.Replace('@STAGE@', $Stage).Replace('@ROOT_STAGE@', $RootStage).Replace('@APP_SHA@', $Manifest.app.sha256).
        Replace('@MODULE_SHA@', $Manifest.module.sha256).Replace('@SCRIPT_SHA@', $ScriptSha).
        Replace('@APP_BYTES@', [string]$Manifest.app.bytes).Replace('@MODULE_BYTES@', [string]$Manifest.module.bytes)
}
function New-X04GInstallPayload {
    param($Manifest, $Pair, [string]$RootStage)
    $text = @'
#!/system/bin/sh
# X04G private module transaction; generated only from validated release hashes.
set -eu
[ "$(id -u)" = 0 ]
ROOT='@ROOT_STAGE@'
failed=1
disable_own() {
 for d in /data/adb/modules/x04g_home_bootstrap_direct01 /data/adb/modules_update/x04g_home_bootstrap_direct01; do
  if [ -d "$d" ] && [ ! -L "$d" ] && [ ! -L "$d/disable" ]; then
   umask 077; : > "$d/disable"; chown 0:0 "$d/disable"; chmod 0600 "$d/disable"
  fi
 done
}
trap '[ "$failed" = 0 ] || disable_own' EXIT
# Only this exact policy-free ZIP is extracted. Never execute customize or hooks.
# The generic Magisk installer removes old disable, so it is not used here.
BB=/data/adb/magisk/busybox
[ -x "$BB" ]
d="$ROOT/module.prepared"
[ ! -e "$d" ] && [ ! -L "$d" ]; mkdir "$d"
"$BB" unzip -o "$ROOT/module.zip" -d "$d" > "$ROOT/module-install.log" 2>&1
[ -z "$(find "$d" -type l -print)" ]
chown -R 0:0 "$d"
find "$d" -type d -exec chmod 0755 '{}' ';'
find "$d" -type f -exec chmod 0644 '{}' ';'
chmod 0755 "$d/zygisk/armeabi-v7a.so"
[ -f "$d/disable" ] && [ ! -L "$d/disable" ] && [ -f "$d/skip_mount" ]
[ "$(sed -n 's/^id=//p' "$d/module.prop")" = x04g_home_bootstrap_direct01 ]
[ "$(sed -n 's/^versionCode=//p' "$d/module.prop")" = '@MODULE_VERSION@' ]
[ "$(sha256sum "$d/payload.dex" | awk '{print $1}')" = '@PAYLOAD_SHA@' ]
[ "$(sha256sum "$d/zygisk/armeabi-v7a.so" | awk '{print $1}')" = '@NATIVE_SHA@' ]
[ ! -L "$d/x04g-installer-managed.txt" ]
[ ! -e "$d/x04g-installer-managed.new" ] && [ ! -L "$d/x04g-installer-managed.new" ]
umask 077
printf '%s\n' x04g-installer-v1 'apk_sha=@APP_SHA@' 'signer_sha=@SIGNER_SHA@' 'module_sha=@MODULE_SHA@' 'version_code=@MODULE_VERSION@' > "$d/x04g-installer-managed.new"
chown 0:0 "$d/x04g-installer-managed.new"; chmod 0600 "$d/x04g-installer-managed.new"
[ "$(stat -c '%u:%g:%a:%h' "$d/x04g-installer-managed.new")" = 0:0:600:1 ]
mv "$d/x04g-installer-managed.new" "$d/x04g-installer-managed.txt"
chcon -R u:object_r:system_file:s0 "$d"
for base in /data/adb/modules /data/adb/modules_update; do
 [ ! -L "$base" ]
 if [ -e "$base" ]; then [ -d "$base" ] && [ "$(stat -c '%u:%g' "$base")" = 0:0 ]; else mkdir -m 0755 "$base"; fi
done
target=/data/adb/modules/x04g_home_bootstrap_direct01
if [ -e "$target" ]; then
 [ -d "$target" ] && [ ! -L "$target" ] && [ -f "$target/disable" ] && [ ! -L "$target/disable" ]
 target=/data/adb/modules_update/x04g_home_bootstrap_direct01
fi
[ ! -e "$target" ] && [ ! -L "$target" ]
mv "$d" "$target"
[ -f "$target/disable" ] && [ ! -L "$target/disable" ]
failed=0
printf 'X04G_MODULE_STAGED_DISABLED\n'
'@
    $text.Replace('@ROOT_STAGE@', $RootStage).Replace('@APP_SHA@', $Manifest.app.sha256).
        Replace('@SIGNER_SHA@', $Manifest.app.signerSha256).Replace('@MODULE_SHA@', $Manifest.module.sha256).
        Replace('@MODULE_VERSION@', [string]$Manifest.module.versionCode).Replace('@PAYLOAD_SHA@', $Pair.PayloadSha).
        Replace('@NATIVE_SHA@', $Pair.NativeSha)
}
function New-X04GEnableScript {
    param($Manifest, $Pair, [string]$ExpectedUid)
    Assert-X04G ($ExpectedUid -cmatch '^1[0-9]{4}$') 'EXPECTED_UID'
    $text = @'
# X04G:ENABLE
set -eu
[ "$(id -u)" = 0 ]
u=$(pm list packages -U com.google.assistant.launcher | awk '$1=="package:com.google.assistant.launcher"{sub(/^uid:/,"",$2);print $2}')
c=$(pm list packages -U com.google.android.apps.mediashell | awk '$1=="package:com.google.android.apps.mediashell"{sub(/^uid:/,"",$2);print $2}')
[ "$u" = "$c" ]
[ "$u" = '@EXPECTED_UID@' ]
case "$u" in 1[0-9][0-9][0-9][0-9]) ;; *) exit 80;; esac
[ "$(magisk --sqlite "SELECT value FROM settings WHERE key='zygisk';" 2>/dev/null)" = value=1 ]
[ "$(getenforce)" = Enforcing ]
candidate=/data/adb/modules/x04g_home_bootstrap_direct01
if [ -d /data/adb/modules_update/x04g_home_bootstrap_direct01 ]; then candidate=/data/adb/modules_update/x04g_home_bootstrap_direct01; fi
[ "$(sha256sum "$candidate/payload.dex" | awk '{print $1}')" = '@PAYLOAD_SHA@' ]
[ "$(sha256sum "$candidate/zygisk/armeabi-v7a.so" | awk '{print $1}')" = '@NATIVE_SHA@' ]
[ "$(sed -n 's/^module_sha=//p' "$candidate/x04g-installer-managed.txt")" = '@MODULE_SHA@' ]
for d in /data/adb/modules/x04g_home_bootstrap_direct01 /data/adb/modules_update/x04g_home_bootstrap_direct01; do
 [ ! -L "$d" ]
 if [ -d "$d" ]; then
  [ -f "$d/disable" ] && [ ! -L "$d/disable" ] && [ ! -L "$d/uid.pin" ]
  [ "$(stat -c '%u:%g:%a:%h:%s' "$d/uid.pin")" = 0:0:600:1:6 ] && [ "$(cat "$d/uid.pin")" = "$u" ]
 fi
done
for d in /data/adb/modules/x04g_home_bootstrap_direct01 /data/adb/modules_update/x04g_home_bootstrap_direct01; do
 if [ -d "$d" ]; then rm "$d/disable"; fi
done
printf 'X04G_ENABLED\n'
'@
    $text.Replace('@PAYLOAD_SHA@', $Pair.PayloadSha).Replace('@NATIVE_SHA@', $Pair.NativeSha).Replace('@MODULE_SHA@', $Manifest.module.sha256).Replace('@EXPECTED_UID@', $ExpectedUid)
}
function New-X04GCleanupScript {
    param([string]$Stage, [string]$RootStage)
    Assert-X04G ($Stage -cmatch '^/data/local/tmp/x04g-installer-[0-9a-f]{32}$' -and
        $RootStage -cmatch '^/data/adb/x04g-installer/[0-9a-f]{32}$') 'STAGE_PATH'
    $text = @'
# X04G:CLEANUP
set -eu
[ "$(id -u)" = 0 ]
for d in '@STAGE@' '@ROOT_STAGE@'; do
 [ ! -L "$d" ]
 if [ -d "$d" ]; then
  owner=$(stat -c '%u' "$d")
  case "$d:$owner" in /data/local/tmp/x04g-installer-*:2000|/data/adb/x04g-installer/*:0) ;; *) exit 89;; esac
  for name in app.apk module.zip install.sh module-install.log; do
   f="$d/$name"
   [ ! -L "$f" ]
   if [ -e "$f" ]; then [ -f "$f" ] && [ "$(stat -c '%h' "$f")" = 1 ]; rm "$f"; fi
  done
  rmdir "$d"
 fi
done
printf 'X04G_CLEAN\n'
'@
    $text.Replace('@STAGE@', $Stage).Replace('@ROOT_STAGE@', $RootStage)
}
function Get-X04GRuntimeScript {
@'
# X04G:RUNTIME
set -eu
boot=$(cat /proc/sys/kernel/random/boot_id)
p=$(pidof com.google.android.apps.mediashell)
case "$p" in ''|*[!0-9]*) exit 91;; esac
printf 'boot_id=%s\nruntime_pid=%s\n' "$boot" "$p"
logcat -b main -d -v raw --pid="$p" -s X04GRuntime:I '*:S' | tail -c 65536
[ "$(pidof com.google.android.apps.mediashell)" = "$p" ]
[ "$(cat /proc/sys/kernel/random/boot_id)" = "$boot" ]
'@
}
function Test-X04GRuntime {
    [CmdletBinding()] param([string]$Text, [string]$ExpectedBootId)
    if ($Text.Length -gt 66560 -or $ExpectedBootId -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { return $false }
    $lines = @($Text -split '\r?\n')
    if ($lines.Count -lt 3 -or $lines[0] -cne ('boot_id=' + $ExpectedBootId) -or
        $lines[1] -cnotmatch '^runtime_pid=[1-9][0-9]{0,7}$') { return $false }
    foreach ($line in $lines[2..($lines.Count - 1)]) {
        if ($line.Length -gt 3500 -or -not $line.StartsWith('{')) { continue }
        try {
            $record = $line | ConvertFrom-Json
            if ($record.read_state -ceq 'OK' -and [int]$record.sample -ge 1 -and [int]$record.sample -le 25 -and
                [long]$record.elapsed_ms -ge 0 -and [long]$record.elapsed_ms -lt 120000 -and
                $record.bootstrap.state -ceq 'INSTALLED' -and $record.carousel.phase -ceq 'installed' -and
                $record.carousel.active -is [bool] -and $record.carousel.active) { return $true }
        } catch { continue }
    }
    $false
}

function Invoke-X04GInstaller {
    [CmdletBinding()] param([string]$PackageRoot, [string]$AdbPath, [string]$AdbPrivateKey, [string]$Serial,
        [switch]$Install, [switch]$CheckOnly, [switch]$Disable, [switch]$Upgrade, [switch]$NoReboot,
        [scriptblock]$Runner, [string[]]$UsbInventory)
    $context = $null; $moduleTouched = $false; $reportPath = $null; $stagingUsed = $false; $stage = $null; $rootStage = $null
    $report = [pscustomobject][ordered]@{ schema = 'x04g.installer.report.v1'; startedUtc = [DateTime]::UtcNow.ToString('o');
        mode = $(if ($Disable) { 'Disable' } elseif ($Install) { 'Install' } else { 'CheckOnly' });
        success = $false; phase = 'LOCAL_PREFLIGHT'; errorCode = $null; serialMasked = $null;
        appInstalled = $false; moduleStaged = $false; uidPinned = $false; enabled = $false;
        rebootRequested = $false; postRebootVerified = $false; runtimeVerified = $false;
        rebootRequiredForDisable = $false;
        failureDisableAttempted = $false; failureDisableConfirmed = $false;
        stagingCleanupConfirmed = $false; plan = $null; finishedUtc = $null; reportPath = $null }
    try {
        Assert-X04G (-not ($Install -and ($CheckOnly -or $Disable)) -and -not ($CheckOnly -and $Disable) -and
            (-not ($NoReboot -or $Upgrade) -or $Install)) 'MODE_CONFLICT'
        Assert-X04G ($env:OS -eq 'Windows_NT') 'WINDOWS_REQUIRED'
        $local = Join-Path $env:LOCALAPPDATA 'X04GCameraMonitor\Installer\Runs'
        $null = [IO.Directory]::CreateDirectory($local)
        $runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N')
        $runPath = Join-Path $local $runId; $null = [IO.Directory]::CreateDirectory($runPath)
        $reportPath = Join-Path $runPath 'report.json'
        $root = [IO.Path]::GetFullPath($PackageRoot)
        $manifestPath = Resolve-X04GPackageFile $root 'installer-manifest.json'
        Assert-X04G ((Get-Item -LiteralPath $manifestPath).Length -le 65536) 'MANIFEST_SIZE'
        $manifest = [IO.File]::ReadAllText($manifestPath) | ConvertFrom-Json
        $pair = Test-X04GArtifactPair $manifest $root
        if (-not $AdbPath) { $AdbPath = Resolve-X04GPackageFile $root $manifest.adb.path }
        $AdbPath = [IO.Path]::GetFullPath($AdbPath)
        Assert-X04G ([IO.File]::Exists($AdbPath) -and (Get-X04GSha256 $AdbPath) -eq $manifest.adb.sha256 -and
            (Get-Item -LiteralPath $AdbPath).Length -eq [long]$manifest.adb.bytes) 'ADB_BINARY_HASH'
        if ($manifest.adb.PSObject.Properties.Name -contains 'files') {
            foreach ($p in $manifest.adb.files.PSObject.Properties) {
                Assert-X04G ($p.Name -cmatch '^platform-tools/(adb\.exe|AdbWinApi\.dll|AdbWinUsbApi\.dll)$') 'ADB_COMPANION_PATH'
                $file = Join-Path ([IO.Path]::GetDirectoryName($AdbPath)) ([IO.Path]::GetFileName($p.Name))
                Assert-X04G ([IO.File]::Exists($file) -and (Get-X04GSha256 $file) -eq $p.Value.sha256 -and
                    (Get-Item -LiteralPath $file).Length -eq [long]$p.Value.bytes) 'ADB_COMPANION_HASH'
            }
        }
        if (-not $AdbPrivateKey) { $AdbPrivateKey = Join-Path $env:USERPROFILE '.android\adbkey' }
        $AdbPrivateKey = [IO.Path]::GetFullPath($AdbPrivateKey)
        Assert-X04G ([IO.File]::Exists($AdbPrivateKey) -and (Get-Item -LiteralPath $AdbPrivateKey).Length -le 16384) 'ADB_PRIVATE_KEY_MISSING'
        if (-not $Runner) { $Runner = ${function:Invoke-X04GProcess} }
        $context = [pscustomobject]@{ AdbPath = $AdbPath; Serial = ''; Runner = $Runner;
            Environment = @{ ADB_VENDOR_KEYS = $AdbPrivateKey; ADB_SERVER_SOCKET = 'tcp:127.0.0.1:5142';
                ANDROID_ADB_SERVER_PORT = '5142'; ADB_SERVER_PORT = '5142'; ADB_TRACE = '' } }
        $report.phase = 'USB_AUTHENTICATION'
        if (-not $PSBoundParameters.ContainsKey('UsbInventory')) { $UsbInventory = @(Get-X04GUsbInventory) }
        $inventoryText = Invoke-X04GAdb $context @('devices', '-l') -Inventory
        $context.Serial = Select-X04GUsbDevice $inventoryText $UsbInventory $Serial
        $serialHash = Get-X04GBytesSha256 ($script:Utf8.GetBytes($context.Serial)); $report.serialMasked = 'sha256:' + $serialHash.Substring(0, 12)
        $report.phase = 'DEVICE_PREFLIGHT'
        $probe = ConvertFrom-X04GFields (Invoke-X04GShell $context (Get-X04GProbeScript))
        $su = ConvertFrom-X04GFields (Invoke-X04GShell $context (Get-X04GRootProbeScript) -Root)
        foreach ($key in $su.Keys) { $probe[$key] = $su[$key] }
        if ($Disable) {
            Assert-X04G ($probe.model -eq 'X04G' -and $probe.device -eq 'mico_x04g' -and
                $probe.shell_uid -eq '2000' -and $probe.su_root -eq '1') 'DISABLE_DEVICE_IDENTITY'
            $report.phase = 'DISABLING'
            $answer = Invoke-X04GShell $context (New-X04GDisableScript) -Root
            Assert-X04G ($answer -match '(?m)^X04G_DISABLED\r?$') 'DISABLE_NOT_CONFIRMED'
            $report.success = $true; $report.phase = 'DISABLED_REBOOT_REQUIRED'; $report.rebootRequiredForDisable = $true; return [pscustomobject]$report
        }
        $null = Test-X04GCompatibility $probe $manifest
        Assert-X04G ($probe.boot_id -cmatch '^[0-9a-f-]{36}$') 'BOOT_ID'
        if ($probe.app_sha -eq $manifest.app.sha256) {
            Assert-X04G ([long]$probe.app_version_code -eq [long]$manifest.app.versionCode -and
                $probe.app_version_name -eq $manifest.app.versionName) 'EXISTING_APP_VERSION'
        }
        $plan = Get-X04GInstallPlan $probe $manifest $pair -Install:$Install -Upgrade:$Upgrade -NoReboot:$NoReboot
        $report.plan = $plan.Mode
        if (-not $Install) { $report.success = $true; $report.phase = 'PREFLIGHT_PASSED'; return [pscustomobject]$report }
        $token = [Guid]::NewGuid().ToString('N'); $stage = '/data/local/tmp/x04g-installer-' + $token
        $rootStage = '/data/adb/x04g-installer/' + $token
        if ($plan.InstallApk -or $plan.InstallModule) {
            $report.phase = 'STAGING'
            $payloadFile = Join-Path $runPath 'install.sh'
            [IO.File]::WriteAllText($payloadFile, (New-X04GInstallPayload $manifest $pair $rootStage).Replace("`r`n", "`n") + "`n", $script:Utf8)
            $scriptSha = Get-X04GSha256 $payloadFile
            $stagingUsed = $true
            $null = Invoke-X04GShell $context ("# X04G:STAGE`nset -eu`numask 077`nmkdir -m 700 '$stage'`n")
            $null = Invoke-X04GAdb $context @('push', $pair.Apk, ($stage + '/app.apk')) -Timeout 120
            $null = Invoke-X04GAdb $context @('push', $pair.Module, ($stage + '/module.zip')) -Timeout 120
            $null = Invoke-X04GAdb $context @('push', $payloadFile, ($stage + '/install.sh')) -Timeout 45
            $check = "set -eu`n[ `"`$(sha256sum '$stage/app.apk' | cut -d ' ' -f 1)`" = '$($manifest.app.sha256)' ]`n[ `"`$(sha256sum '$stage/module.zip' | cut -d ' ' -f 1)`" = '$($manifest.module.sha256)' ]`n"
            $null = Invoke-X04GShell $context $check
            if ($plan.InstallApk) {
                $report.phase = 'INSTALLING_APK'
                $answer = Invoke-X04GShell $context ("# X04G:INSTALL_APK`nset -eu`npm install -r '$stage/app.apk'`n") -Timeout 180
                Assert-X04G ($answer -match '(?m)^Success\r?$') 'APK_INSTALL_NOT_SUCCESS'
                $after = ConvertFrom-X04GFields (Invoke-X04GShell $context (Get-X04GProbeScript))
                Assert-X04G ($after.app_sha -eq $manifest.app.sha256 -and
                    [long]$after.app_version_code -eq [long]$manifest.app.versionCode -and
                    $after.app_version_name -eq $manifest.app.versionName) 'INSTALLED_APK_PIN_MISMATCH'
                $report.appInstalled = $true
            }
            if ($plan.InstallModule) {
                $report.phase = 'INSTALLING_MODULE'; $moduleTouched = $true
                $answer = Invoke-X04GShell $context (New-X04GModuleScript $manifest $pair $stage $scriptSha $rootStage) -Root -Timeout 180
                Assert-X04G ($answer -match '(?m)^X04G_MODULE_STAGED_DISABLED\r?$') 'MODULE_STAGE_NOT_CONFIRMED'
                $report.moduleStaged = $true
            }
        }
        $report.phase = 'PINNING_UID'; $moduleTouched = $true
        $answer = Invoke-X04GShell $context (New-X04GPinScript $manifest $pair $probe.launcher_uid) -Root
        Assert-X04G ($answer -match '(?m)^X04G_PINNED\r?$') 'UID_PIN_NOT_CONFIRMED'
        $report.uidPinned = $true
        if ($stagingUsed) {
            $answer = Invoke-X04GShell $context (New-X04GCleanupScript $stage $rootStage) -Root
            Assert-X04G ($answer -match '(?m)^X04G_CLEAN\r?$') 'STAGE_CLEANUP_NOT_CONFIRMED'
            $report.stagingCleanupConfirmed = $true; $stagingUsed = $false
        }
        if ($NoReboot) { $report.success = $true; $report.phase = 'PREPARED_DISABLED'; return [pscustomobject]$report }
        $report.phase = 'ENABLING'
        $answer = Invoke-X04GShell $context (New-X04GEnableScript $manifest $pair $probe.launcher_uid) -Root
        Assert-X04G ($answer -match '(?m)^X04G_ENABLED\r?$') 'ENABLE_NOT_CONFIRMED'
        $report.enabled = $true; $report.phase = 'REBOOT_REQUEST'
        $null = Invoke-X04GAdb $context @('reboot') -Timeout 20
        $report.rebootRequested = $true; $report.phase = 'POST_REBOOT_VERIFICATION'
        $null = Invoke-X04GAdb $context @('wait-for-device') -Timeout 120
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        $post = $null
        do {
            $remaining = [int][Math]::Ceiling(($deadline - [DateTime]::UtcNow).TotalSeconds)
            Assert-X04G ($remaining -gt 0) 'BOOT_TIMEOUT'
            $post = ConvertFrom-X04GFields (Invoke-X04GShell $context (Get-X04GProbeScript) -Timeout ([Math]::Min(45, $remaining)))
            if ($post.boot_completed -eq '1') { break }
            Start-Sleep -Seconds ([Math]::Min(3, [Math]::Max(1, $remaining)))
        } while ([DateTime]::UtcNow -lt $deadline)
        Assert-X04G ($null -ne $post -and $post.boot_completed -eq '1') 'BOOT_TIMEOUT'
        Assert-X04G ($post.boot_id -cmatch '^[0-9a-f-]{36}$' -and $post.boot_id -ne $probe.boot_id) 'REBOOT_NOT_OBSERVED'
        $postSu = ConvertFrom-X04GFields (Invoke-X04GShell $context (Get-X04GRootProbeScript) -Root)
        foreach ($key in $postSu.Keys) { $post[$key] = $postSu[$key] }
        $null = Test-X04GCompatibility $post $manifest
        Assert-X04G ($post.launcher_uid -eq $probe.launcher_uid -and $post.app_sha -eq $manifest.app.sha256 -and
            [long]$post.app_version_code -eq [long]$manifest.app.versionCode -and
            $post.app_version_name -eq $manifest.app.versionName) 'POST_REBOOT_APP_OR_UID'
        Assert-X04G ($post.module_present -eq '1' -and $post.update_present -eq '0' -and
            $post.module_managed -eq '1' -and $post.module_disabled -eq '0' -and
            $post.module_module_sha -eq $manifest.module.sha256 -and
            $post.module_apk_sha -eq $manifest.app.sha256 -and $post.module_signer_sha -eq $manifest.app.signerSha256 -and
            $post.module_payload_sha -eq $pair.PayloadSha -and $post.module_native_sha -eq $pair.NativeSha -and
            $post.module_uid_pin -eq $probe.launcher_uid -and $post.module_uid_metadata -eq '0:0:600:1:6') 'POST_REBOOT_MODULE'
        $report.postRebootVerified = $true; $report.phase = 'RUNTIME_VERIFICATION'
        $runtimeDeadline = [DateTime]::UtcNow.AddSeconds(120)
        do {
            $remaining = [int][Math]::Ceiling(($runtimeDeadline - [DateTime]::UtcNow).TotalSeconds)
            Assert-X04G ($remaining -gt 0) 'CAROUSEL_RUNTIME_TIMEOUT'
            $runtime = Invoke-X04GShell $context (Get-X04GRuntimeScript) -Timeout ([Math]::Min(20, $remaining))
            if (Test-X04GRuntime $runtime $post.boot_id) { $report.runtimeVerified = $true; break }
            Start-Sleep -Seconds ([Math]::Min(5, [Math]::Max(1, $remaining)))
        } while ([DateTime]::UtcNow -lt $runtimeDeadline)
        Assert-X04G $report.runtimeVerified 'CAROUSEL_RUNTIME_NOT_INSTALLED'
        $report.success = $true; $report.phase = 'INSTALLED_RUNTIME_VERIFIED'
        [pscustomobject]$report
    } catch {
        $code = [string]$_.Exception.Message
        if ($code -notmatch '^X04G_[A-Z0-9_]+$') { $code = 'X04G_UNEXPECTED' }
        $report.errorCode = $code; $report.success = $false
        if ($moduleTouched -and $null -ne $context -and $context.Serial) {
            $report.failureDisableAttempted = $true
            $wasEnabled = $report.enabled -or $report.rebootRequested
            try { $answer = Invoke-X04GShell $context (New-X04GDisableScript) -Root -Timeout 30
                $report.failureDisableConfirmed = [bool]($answer -match '(?m)^X04G_DISABLED\r?$')
                if ($report.failureDisableConfirmed) { $report.enabled = $false; $report.rebootRequiredForDisable = [bool]$wasEnabled }
            } catch {}
        }
        if ($stagingUsed -and $null -ne $context -and $context.Serial) {
            try { $answer = Invoke-X04GShell $context (New-X04GCleanupScript $stage $rootStage) -Root -Timeout 30
                $report.stagingCleanupConfirmed = [bool]($answer -match '(?m)^X04G_CLEAN\r?$') } catch {}
        }
        [pscustomobject]$report
    } finally {
        $report.finishedUtc = [DateTime]::UtcNow.ToString('o')
        if ($reportPath) {
            $report.reportPath = $reportPath
            try { [IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 8), $script:Utf8) } catch {}
        }
    }
}
Export-ModuleMember -Function Test-X04GManifest, Test-X04GArtifactPair, Select-X04GUsbDevice, Test-X04GCompatibility,
    Get-X04GInstallPlan, Invoke-X04GInstaller, ConvertTo-X04GArgument, ConvertFrom-X04GFields, Invoke-X04GProcess, Test-X04GRuntime

<#
Standalone HOST tests for Windows PowerShell 5.1 and PowerShell 7. No Pester.
Every installer invocation supplies a closed fake Runner and USB inventory.
The dummy adb.exe is plain text, never executable. PnP calls are booby-trapped.
Usage: powershell -NoProfile -File installer/tests/Test-X04GInstaller.ps1
#>
[CmdletBinding()]
param(
    [string]$CorePath,
    [string]$ReportPath,
    [switch]$KeepFixtures
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
if (-not $CorePath) { $CorePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'X04G.Installer.psm1' }
$script:Checks = 0
$script:Results = New-Object System.Collections.ArrayList
$script:Utf8 = New-Object System.Text.UTF8Encoding($false)
$script:LastTransport = $null
$script:LastReport = $null
$script:TestRoot = Join-Path ([IO.Path]::GetTempPath()) ('X04GInstallerHostTests-' + [Guid]::NewGuid().ToString('N'))
$script:OldLocalAppData = $env:LOCALAPPDATA
$null = [IO.Directory]::CreateDirectory($script:TestRoot)

# A missing injection must fail without consulting the computer's USB inventory.
function global:Get-PnpDevice { throw 'TEST_REAL_PNP_FORBIDDEN' }
function global:Get-PnpDeviceProperty { throw 'TEST_REAL_PNP_FORBIDDEN' }
Import-Module -Name $CorePath -Force -DisableNameChecking
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

function Assert-Test {
    param([bool]$Condition, [string]$Code)
    $script:Checks++
    if (-not $Condition) { throw ('TEST_' + $Code) }
}
function Expect-Rejection {
    param([scriptblock]$Action, [string]$Code)
    $rejected = $false
    try { $null = & $Action } catch { $rejected = $true }
    Assert-Test $rejected $Code
}
function Test-Case {
    param([string]$Name, [scriptblock]$Action)
    $script:LastTransport = $null
    $script:LastReport = $null
    try {
        & $Action
        if ($null -ne $script:LastTransport) {
            Assert-Test ($script:LastTransport.Unexpected.Count -eq 0) 'UNPLANNED_FAKE_COMMAND'
        }
        $null = $script:Results.Add([pscustomobject]@{ name = $Name; pass = $true; error = $null })
    } catch {
        $code = [string]$_.Exception.Message
        if ($code -notmatch '^TEST_[A-Z0-9_]+$') { $code = $_.Exception.GetType().Name }
        $null = $script:Results.Add([pscustomobject]@{ name = $Name; pass = $false; error = $code })
        Write-Host ('FAIL ' + $Name + ': ' + $code + ' (line ' + $_.InvocationInfo.ScriptLineNumber + ')')
        if ($null -ne $script:LastReport) { Write-Host ('  phase=' + $script:LastReport.phase + ' code=' + $script:LastReport.errorCode) }
        if ($null -ne $script:LastTransport) { Write-Host ('  commands=' + (($script:LastTransport.Calls | ForEach-Object { $_.Key }) -join ',')) }
    }
}
function Hash-Bytes {
    param([byte[]]$Bytes)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($hash.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}
function Write-Json {
    param([string]$Path, $Value)
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12), $script:Utf8)
}
function Add-ZipBytes {
    param($Archive, [string]$Name, [byte[]]$Bytes)
    $entry = $Archive.CreateEntry($Name)
    $stream = $entry.Open()
    try { if ($Bytes.Length) { $stream.Write($Bytes, 0, $Bytes.Length) } }
    finally { $stream.Dispose() }
}
function New-Fixture {
    param([string]$Name, [hashtable]$Options = @{})
    $folder = Join-Path $script:TestRoot ($Name + '-' + [Guid]::NewGuid().ToString('N'))
    foreach ($directory in @($folder, (Join-Path $folder 'artifacts'), (Join-Path $folder 'platform-tools'))) {
        $null = [IO.Directory]::CreateDirectory($directory)
    }
    $apkPath = Join-Path $folder 'artifacts/app.apk'
    $apk = $script:Utf8.GetBytes('PUBLIC SYNTHETIC APK FIXTURE; NOT AN ANDROID PACKAGE')
    [IO.File]::WriteAllBytes($apkPath, $apk)
    $payload = $script:Utf8.GetBytes('PUBLIC SYNTHETIC DEX FIXTURE; NOT EXECUTABLE')
    $native = New-Object byte[] 52
    $native[0] = 127; $native[1] = 69; $native[2] = 76; $native[3] = 70
    $native[4] = 1; $native[5] = 1; $native[18] = 40
    if ($Options['ZipArm64']) { $native[4] = 2 }
    $oem = [ordered]@{ package = 'com.google.assistant.launcher'; process = 'com.google.android.apps.mediashell';
        activePath = '/product/app/com.google.assistant.launcher/app.apk'; versionCode = 111706;
        versionName = '11.51.6.416171605'; sha256 = '1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21' }
    $app = [ordered]@{ package = 'org.x04g.home'; path = 'artifacts/app.apk'; versionCode = 3;
        versionName = '0.1.0-rc.2'; bytes = $apk.Length; sha256 = (Hash-Bytes $apk); signerSha256 = ('a' * 64) }
    $provenance = [ordered]@{ apk_sha256 = $app.sha256; apk_bytes = $app.bytes; apk_signer_sha256 = $app.signerSha256;
        apk_package = $app.package; apk_version_code = $app.versionCode; apk_version_name = $app.versionName;
        initially_disabled = $true; uid_pin_included = $false; stock_launcher_sha256 = $oem.sha256;
        payload_sha256 = (Hash-Bytes $payload); native_sha256 = (Hash-Bytes $native);
        module_id = 'x04g_home_bootstrap_direct01'; module_version = '0.1.0-rc.2'; module_version_code = 4 }
    if ($Options['ZipPinMismatch']) { $provenance.apk_version_code = 2 }
    if ($Options['ZipPayloadBad']) { $provenance.payload_sha256 = ('b' * 64) }
    $modulePath = Join-Path $folder 'artifacts/module.zip'
    $archive = [IO.Compression.ZipFile]::Open($modulePath, [IO.Compression.ZipArchiveMode]::Create)
    try {
        if (-not $Options['ZipMissingDisable']) { Add-ZipBytes $archive 'disable' (New-Object byte[] 0) }
        Add-ZipBytes $archive 'skip_mount' (New-Object byte[] 0)
        Add-ZipBytes $archive 'payload.dex' $payload
        Add-ZipBytes $archive 'zygisk/armeabi-v7a.so' $native
        $prop = "id=x04g_home_bootstrap_direct01`nversion=0.1.0-rc.2`nversionCode=4`n"
        if ($Options['ZipVersionBad']) { $prop = $prop.Replace('versionCode=4', 'versionCode=3') }
        Add-ZipBytes $archive 'module.prop' ($script:Utf8.GetBytes($prop))
        Add-ZipBytes $archive 'provenance.json' ($script:Utf8.GetBytes(($provenance | ConvertTo-Json -Depth 8)))
        foreach ($entry in @('customize.sh', 'META-INF/com/google/android/update-binary',
            'META-INF/com/google/android/updater-script', 'licenses/project-MIT.txt',
            'licenses/magisk-installer-GPL-3.0.txt', 'licenses/zygisk-ISC.txt', 'licenses/nayuki-qr-MIT.txt')) {
            Add-ZipBytes $archive $entry ($script:Utf8.GetBytes('PUBLIC INERT SYNTHETIC ZIP ENTRY'))
        }
        if ($Options['ZipUidPin']) { Add-ZipBytes $archive 'uid.pin' ($script:Utf8.GetBytes("13331`n")) }
    } finally { $archive.Dispose() }
    $adbFiles = [ordered]@{}
    foreach ($file in @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll')) {
        $bytes = $script:Utf8.GetBytes('INERT SYNTHETIC TEST FILE: ' + $file)
        [IO.File]::WriteAllBytes((Join-Path $folder ('platform-tools/' + $file)), $bytes)
        $adbFiles[('platform-tools/' + $file)] = [ordered]@{ sha256 = (Hash-Bytes $bytes); bytes = $bytes.Length }
    }
    $adb = [ordered]@{ path = 'platform-tools/adb.exe'; sha256 = $adbFiles['platform-tools/adb.exe'].sha256;
        bytes = $adbFiles['platform-tools/adb.exe'].bytes; files = $adbFiles }
    $module = [ordered]@{ id = 'x04g_home_bootstrap_direct01'; path = 'artifacts/module.zip';
        version = '0.1.0-rc.2'; versionCode = 4; sha256 = (Hash-Bytes ([IO.File]::ReadAllBytes($modulePath)));
        bytes = (Get-Item -LiteralPath $modulePath).Length }
    $manifest = [pscustomobject]@{ schema = 'x04g.installer.v1'; app = [pscustomobject]$app; module = [pscustomobject]$module;
        oem = [pscustomobject]$oem; adb = [pscustomobject]$adb }
    $manifestPath = Join-Path $folder 'installer-manifest.json'; Write-Json $manifestPath $manifest
    $key = Join-Path $folder 'synthetic-adbkey'
    [IO.File]::WriteAllText($key, 'PUBLIC NON-KEY FIXTURE', $script:Utf8)
    [pscustomobject]@{ Root = $folder; Manifest = $manifest; ManifestPath = $manifestPath; Apk = $apkPath;
        Module = $modulePath; Adb = (Join-Path $folder 'platform-tools/adb.exe'); PrivateKey = $key;
        PayloadSha = $provenance.payload_sha256; NativeSha = $provenance.native_sha256 }
}
function New-Transport {
    param($Fixture, [hashtable]$Options = @{})
    $uid = '13331'; if ($Options['Uid']) { $uid = [string]$Options['Uid'] }
    $state = @{
        Calls = (New-Object System.Collections.ArrayList); Unexpected = (New-Object System.Collections.ArrayList);
        Devices = "List of devices attached`nFAKEUSB01 device usb:1-1 product:mico_x04g model:X04G device:mico_x04g transport_id:1`n";
        Options = $Options; InitialUid = $uid; CurrentUid = $uid; AppInstalled = $false; ModuleStaged = $false;
        Pinned = $false; Disabled = $true; Rebooted = $false; ProbeCount = 0;
        Probe = @{ model = 'X04G'; device = 'mico_x04g'; api = '29'; abi = 'armeabi-v7a'; boot_completed = '1';
            adb_secure = '1'; shell_uid = '2000'; selinux = 'Enforcing'; display = 'Physical size: 800x480|'; boot_id = '00000000-0000-4000-8000-000000000001';
            oem_path = $Fixture.Manifest.oem.activePath; oem_sha = $Fixture.Manifest.oem.sha256;
            oem_version_code = '111706'; oem_version_name = '11.51.6.416171605'; launcher_uid = $uid; cast_uid = $uid };
        RootProbe = @{ su_root = '1'; magisk = '26100'; zygisk = 'value=1'; denylist_enforced = '0'; denylist_target = '0';
            module_present = '0'; update_present = '0' }
    }
    if ($Options['Devices']) { $state.Devices = [string]$Options['Devices'] }
    if ($Options['ProbeChanges']) { foreach ($key in $Options['ProbeChanges'].Keys) { $state.Probe[$key] = [string]$Options['ProbeChanges'][$key] } }
    if ($Options['RootChanges']) { foreach ($key in $Options['RootChanges'].Keys) { $state.RootProbe[$key] = [string]$Options['RootChanges'][$key] } }
    $runner = {
        param([string]$FilePath, [string[]]$Arguments, [string]$InputText, [int]$TimeoutSeconds, [hashtable]$Environment)
        # The only operations here are string/fixture inspection; never execute FilePath.
        $key = 'UNKNOWN'
        try {
            if ($FilePath -cne $Fixture.Adb -or $Arguments.Count -lt 6 -or
                (($Arguments[0..3] -join '|') -cne '-H|127.0.0.1|-P|5142') -or
                $Environment.ADB_VENDOR_KEYS -cne $Fixture.PrivateKey -or
                $Environment.ADB_SERVER_SOCKET -cne 'tcp:127.0.0.1:5142' -or $TimeoutSeconds -le 0 -or $TimeoutSeconds -gt 180) {
                throw 'FAKE_TRANSPORT_CONTRACT'
            }
            $tail = @($Arguments[4..($Arguments.Count - 1)])
            if (($tail -join '|') -ceq 'devices|-l') { $key = 'DEVICES' }
            else {
                if ($tail.Count -lt 3 -or $tail[0] -cne '-s' -or $tail[1] -cne 'FAKEUSB01') { throw 'FAKE_USB_TARGET' }
                $command = @($tail[2..($tail.Count - 1)])
                if (($command -join '|') -ceq 'reboot') { $key = 'REBOOT' }
                elseif (($command -join '|') -ceq 'wait-for-device') { $key = 'WAIT' }
                elseif ($command.Count -eq 3 -and $command[0] -ceq 'push') {
                    if ($command[2] -notmatch '^/data/local/tmp/x04g-installer-[a-f0-9]{32}/(app[.]apk|module[.]zip|install[.]sh)$' -or -not [IO.File]::Exists($command[1])) { throw 'FAKE_PUSH_PATH' }
                    $key = @{ 'app.apk' = 'PUSH_APP'; 'module.zip' = 'PUSH_MODULE'; 'install.sh' = 'PUSH_SCRIPT' }[$Matches[1]]
                    if ($key -eq 'PUSH_SCRIPT') { $state.InstallPayload = [IO.File]::ReadAllText($command[1]) }
                }
                elseif (($command -join '|') -ceq 'shell|sh|-s' -or ($command -join '|') -ceq "shell|su -c 'sh -s'") {
                    $match = [regex]::Match($InputText, '(?m)^# X04G:([A-Z_]+)\r?$')
                    if ($match.Success) { $key = $match.Groups[1].Value }
                    elseif ($InputText.Contains('sha256sum') -and $InputText.Contains($Fixture.Manifest.app.sha256) -and $InputText.Contains($Fixture.Manifest.module.sha256)) { $key = 'STAGE_VERIFY' }
                    if ($key -in @('SU', 'INSTALL_MODULE', 'PIN', 'ENABLE', 'DISABLE', 'CLEANUP') -and ($command -join '|') -cne "shell|su -c 'sh -s'") { throw 'FAKE_ROOT_INVOCATION' }
                }
            }
            $allowed = @('DEVICES', 'PROBE', 'SU', 'STAGE', 'PUSH_APP', 'PUSH_MODULE', 'PUSH_SCRIPT', 'STAGE_VERIFY', 'INSTALL_APK', 'INSTALL_MODULE', 'PIN', 'ENABLE', 'DISABLE', 'CLEANUP', 'REBOOT', 'WAIT', 'RUNTIME')
            if ($allowed -cnotcontains $key) { throw 'FAKE_UNPLANNED_COMMAND' }
            $null = $state.Calls.Add([pscustomobject]@{ Key = $key; Input = $InputText; Arguments = @($Arguments); Timeout = $TimeoutSeconds })
            if ($state.Options['CheckOnly'] -and $key -notin @('DEVICES', 'PROBE', 'SU')) { throw 'FAKE_DRYRUN_MUTATION' }
            if ($state.Options['TimeoutOn'] -eq $key) { return @{ ExitCode = -1; StdOut = ''; StdErr = ''; TimedOut = $true } }
            if ($state.Options['FailOn'] -eq $key) { return @{ ExitCode = 1; StdOut = ''; StdErr = 'Synthetic failure'; TimedOut = $false } }
            $answer = ''
            switch ($key) {
                'DEVICES' { $answer = $state.Devices }
                'PROBE' {
                    $state.ProbeCount++; $probe = $state.Probe.Clone()
                    if ($state.Rebooted) { $probe.boot_id = '00000000-0000-4000-8000-000000000002' }
                    if ($state.Options['UidChanged'] -and $state.AppInstalled) { $state.CurrentUid = '16661'; $probe.launcher_uid = '16661'; $probe.cast_uid = '16661' }
                    if ($state.AppInstalled) {
                        $probe.app_path = '/data/app/org.x04g.home-SYNTHETIC/base.apk'; $probe.app_sha = $Fixture.Manifest.app.sha256
                        $probe.app_version_code = [string]$Fixture.Manifest.app.versionCode; $probe.app_version_name = $Fixture.Manifest.app.versionName
                    } else { $probe.app_path = 'NONE'; $probe.app_sha = 'NONE'; $probe.app_version_code = '0'; $probe.app_version_name = 'NONE' }
                    $answer = (($probe.Keys | Sort-Object | ForEach-Object { $_ + '=' + $probe[$_] }) -join "`n") + "`n"
                }
                'SU' {
                    $probe = $state.RootProbe.Clone()
                    if ($state.Rebooted) {
                        $probe.module_present = '1'; $probe.update_present = '0'; $probe.module_managed = '1'
                        $probe.module_disabled = $(if ($state.Disabled -or $state.Options['PostRebootFault']) { '1' } else { '0' })
                        $probe.module_apk_sha = $Fixture.Manifest.app.sha256; $probe.module_signer_sha = $Fixture.Manifest.app.signerSha256
                        $probe.module_module_sha = $Fixture.Manifest.module.sha256; $probe.module_version_code = [string]$Fixture.Manifest.module.versionCode
                        $probe.module_payload_sha = $Fixture.PayloadSha; $probe.module_native_sha = $Fixture.NativeSha
                        $probe.module_uid_pin = $state.CurrentUid; $probe.module_uid_metadata = '0:0:600:1:6'
                    }
                    $answer = (($probe.Keys | Sort-Object | ForEach-Object { $_ + '=' + $probe[$_] }) -join "`n") + "`n"
                }
                'INSTALL_APK' {
                    if ($state.Options['FalseApkSuccess']) { $answer = 'Performing Streamed Install' }
                    else { $state.AppInstalled = $true; $answer = 'Success' }
                }
                'INSTALL_MODULE' { $state.ModuleStaged = $true; $state.Disabled = $true; $answer = 'X04G_MODULE_STAGED_DISABLED' }
                'PIN' {
                    if (-not $state.ModuleStaged) { throw 'FAKE_PIN_BEFORE_MODULE' }
                    # A fake cannot execute sh: require the substantive safety checks before acknowledging it.
                    foreach ($snippet in @('chown 0:0 "$d/uid.pin.new"', 'chmod 0600 "$d/uid.pin.new"', 'mv "$d/uid.pin.new" "$d/uid.pin"', '0:0:600:1:6', $state.InitialUid)) {
                        if (-not $InputText.Contains($snippet)) { throw 'FAKE_UID_PIN_GUARD_MISSING' }
                    }
                    if ($state.CurrentUid -ne $state.InitialUid) { return @{ ExitCode = 71; StdOut = ''; StdErr = ''; TimedOut = $false } }
                    $state.Pinned = $true; $answer = 'X04G_PINNED'
                }
                'ENABLE' {
                    if (-not $state.Pinned -or -not $InputText.Contains('0:0:600:1:6') -or -not $InputText.Contains($state.InitialUid)) { throw 'FAKE_ENABLE_GUARD_MISSING' }
                    $state.Disabled = $false; $answer = 'X04G_ENABLED'
                }
                'DISABLE' { $state.Disabled = $true; $answer = 'X04G_DISABLED' }
                'CLEANUP' { $answer = 'X04G_CLEAN' }
                'REBOOT' { if (-not $state.Pinned -or $state.Disabled) { throw 'FAKE_UNSAFE_REBOOT' }; $state.Rebooted = $true }
                'WAIT' { if (-not $state.Rebooted) { throw 'FAKE_WAIT_BEFORE_REBOOT' } }
                'RUNTIME' { $answer = 'boot_id=00000000-0000-4000-8000-000000000002' + "`nruntime_pid=1234`n" + '{"sample":1,"elapsed_ms":100,"read_state":"OK","bootstrap":{"state":"INSTALLED"},"carousel":{"phase":"installed","active":true}}' }
                'STAGE' {}
                'PUSH_APP' {}
                'PUSH_MODULE' {}
                'PUSH_SCRIPT' {}
                'STAGE_VERIFY' {}
                default { throw 'FAKE_UNPLANNED_COMMAND' }
            }
            @{ ExitCode = 0; StdOut = $answer; StdErr = ''; TimedOut = $false }
        } catch { $null = $state.Unexpected.Add($key); throw }
    }.GetNewClosure()
    $script:LastTransport = $state
    [pscustomobject]@{ State = $state; Runner = $runner }
}
function Invoke-Fixture {
    param($Fixture, $Transport, [switch]$Install, [switch]$NoReboot, [string[]]$UsbInventory = @('FAKEUSB01'))
    $env:LOCALAPPDATA = Join-Path $Fixture.Root 'local-appdata'
    $script:LastReport = Invoke-X04GInstaller -PackageRoot $Fixture.Root -AdbPrivateKey $Fixture.PrivateKey -Runner $Transport.Runner -UsbInventory $UsbInventory -Install:$Install -NoReboot:$NoReboot
    $script:LastReport
}
function Assert-NoMutation {
    param($Transport)
    $mutations = @($Transport.State.Calls | Where-Object { $_.Key -notin @('DEVICES', 'PROBE', 'SU') })
    Assert-Test ($mutations.Count -eq 0) 'MUTATION_BEFORE_VALIDATION'
}
function Assert-Cleanup {
    param($Report, $Transport)
    Assert-Test (-not $Report.success) 'FAILURE_REPORTED_SUCCESS'
    Assert-Test ($Report.failureDisableAttempted -and $Report.failureDisableConfirmed -and $Transport.State.Disabled) 'FAILURE_DISABLE_MISSING'
    Assert-Test (@($Transport.State.Calls | Where-Object { $_.Key -eq 'DISABLE' }).Count -eq 1) 'DISABLE_NOT_EXACTLY_ONCE'
}

try {
    Test-Case 'pure.valid-artifact-pair' {
        $f = New-Fixture 'valid'; $pair = Test-X04GArtifactPair $f.Manifest $f.Root
        Assert-Test ($pair.PayloadSha -eq $f.PayloadSha -and $pair.NativeSha -eq $f.NativeSha) 'ARTIFACT_PAIR'
    }
    Test-Case 'pure.manifest-path-and-version-rejected' {
        $f = New-Fixture 'manifest'; $f.Manifest.app.path = '../outside.apk'
        Expect-Rejection { Test-X04GManifest $f.Manifest } 'PATH_TRAVERSAL_ACCEPTED'
        $f.Manifest.app.path = 'artifacts/app.apk'; $f.Manifest.module.versionCode = 3
        Expect-Rejection { Test-X04GManifest $f.Manifest } 'MODULE_VERSION_ACCEPTED'
    }
    Test-Case 'pure.fields-duplicates-and-controls-rejected' {
        Assert-Test ((ConvertFrom-X04GFields "model=X04G`napi=29`n").api -eq '29') 'FIELDS_VALID'
        Expect-Rejection { ConvertFrom-X04GFields "api=29`napi=30`n" } 'DUPLICATE_FIELD_ACCEPTED'
        Expect-Rejection { ConvertFrom-X04GFields "api=29`0`n" } 'CONTROL_FIELD_ACCEPTED'
    }
    Test-Case 'pure.install-plans-fresh-resume-and-unmanaged' {
        $f = New-Fixture 'plans'; $pair = [pscustomobject]@{ PayloadSha = $f.PayloadSha; NativeSha = $f.NativeSha }
        $existing = @{ module_present = '0'; update_present = '0'; app_sha = 'NONE' }
        $plan = Get-X04GInstallPlan $existing $f.Manifest $pair -Install -NoReboot
        Assert-Test ($plan.Mode -eq 'Fresh' -and $plan.InstallApk -and $plan.InstallModule -and -not $plan.Activate -and -not $plan.Reboot) 'FRESH_PLAN'
        $existing.app_sha = ('b' * 64)
        Expect-Rejection { Get-X04GInstallPlan $existing $f.Manifest $pair -Install } 'UNMANAGED_APP_PLAN_ACCEPTED'
        $existing = @{ module_present = '1'; update_present = '0'; app_sha = $f.Manifest.app.sha256;
            module_managed = '1'; module_disabled = '1'; module_signer_sha = $f.Manifest.app.signerSha256;
            module_apk_sha = $f.Manifest.app.sha256; module_version_code = '4'; module_module_sha = $f.Manifest.module.sha256;
            module_payload_sha = $f.PayloadSha; module_native_sha = $f.NativeSha }
        $plan = Get-X04GInstallPlan $existing $f.Manifest $pair -Install
        Assert-Test ($plan.Mode -eq 'Resume' -and -not $plan.InstallApk -and -not $plan.InstallModule -and $plan.Activate) 'RESUME_PLAN'
        $existing.module_disabled = '0'
        Expect-Rejection { Get-X04GInstallPlan $existing $f.Manifest $pair -Install } 'ACTIVE_MODULE_PLAN_ACCEPTED'
        $existing.module_disabled = '1'; $existing.module_module_sha = ('c' * 64)
        Expect-Rejection { Get-X04GInstallPlan $existing $f.Manifest $pair -Install } 'IMPLICIT_UPGRADE_ACCEPTED'
        $plan = Get-X04GInstallPlan $existing $f.Manifest $pair -Install -Upgrade
        Assert-Test ($plan.Mode -eq 'Upgrade' -and $plan.InstallModule) 'EXPLICIT_UPGRADE_PLAN'
        $existing.update_present = '1'
        foreach ($key in @($existing.Keys)) {
            if ($key.StartsWith('module_') -and $key -ne 'module_present') { $existing[('update_' + $key.Substring(7))] = $existing[$key] }
        }
        Expect-Rejection { Get-X04GInstallPlan $existing $f.Manifest $pair -Install -Upgrade } 'DIFFERENT_PENDING_UPDATE_ACCEPTED'
    }
    Test-Case 'pure.runtime-acceptance-is-bounded-and-specific' {
        $boot = '00000000-0000-4000-8000-000000000002'
        $prefix = 'boot_id=' + $boot + "`nruntime_pid=1234`n"
        $good = '{"sample":1,"elapsed_ms":100,"read_state":"OK","bootstrap":{"state":"INSTALLED"},"carousel":{"phase":"installed","active":true}}'
        Assert-Test (Test-X04GRuntime ($prefix + $good) $boot) 'RUNTIME_VALID_REJECTED'
        Assert-Test (-not (Test-X04GRuntime ($prefix + $good) '00000000-0000-4000-8000-000000000003')) 'STALE_RUNTIME_ACCEPTED'
        Assert-Test (-not (Test-X04GRuntime (('boot_id=' + ('-' * 36) + "`nruntime_pid=1234`n") + $good) ('-' * 36))) 'INVALID_BOOT_UUID_ACCEPTED'
        Assert-Test (-not (Test-X04GRuntime ($prefix.Replace('runtime_pid=1234', 'runtime_pid=0') + $good) $boot)) 'ZERO_PID_ACCEPTED'
        Assert-Test (-not (Test-X04GRuntime ($prefix.Replace('runtime_pid=1234', 'runtime_pid=1234 5678') + $good) $boot)) 'MULTIPLE_PID_ACCEPTED'
        foreach ($bad in @(
            $good.Replace('"state":"INSTALLED"', '"state":"STOCK_VERSION_REJECTED"'),
            $good.Replace('"phase":"installed"', '"phase":"not_installed"'),
            $good.Replace('"active":true', '"active":false'),
            $good.Replace('"active":true', '"active":"true"'),
            $good.Replace('"read_state":"OK"', '"read_state":"NOT_READY"'),
            $good.Replace('"elapsed_ms":100', '"elapsed_ms":120000'),
            $good.Replace('"sample":1', '"sample":0'),
            $good.Replace('"sample":1', '"sample":26'),
            ('{' + ('x' * 3500) + '}')
        )) { Assert-Test (-not (Test-X04GRuntime ($prefix + $bad) $boot)) 'INVALID_RUNTIME_ACCEPTED' }
    }
    foreach ($item in @(
        @{ Name = 'apk'; File = 'Apk' }, @{ Name = 'module'; File = 'Module' }, @{ Name = 'adb'; File = 'Adb' },
        @{ Name = 'dll'; File = 'Dll' }
    )) {
        $testItem = $item
        Test-Case ('tamper.' + $testItem.Name + '-before-adb') {
            $f = New-Fixture ('tamper-' + $testItem.Name); $t = New-Transport $f
            $path = if ($testItem.File -eq 'Dll') { Join-Path $f.Root 'platform-tools/AdbWinUsbApi.dll' } else { $f.($testItem.File) }
            [IO.File]::AppendAllText($path, 'TAMPER', $script:Utf8)
            $r = Invoke-Fixture $f $t -Install
            Assert-Test (-not $r.success -and $t.State.Calls.Count -eq 0) 'TAMPER_CONTACTED_ADB'
            $expected = if ($testItem.File -eq 'Adb') { 'X04G_ADB_BINARY_HASH' } elseif ($testItem.File -eq 'Dll') { 'X04G_ADB_COMPANION_HASH' } else { 'X04G_ARTIFACT_HASH' }
            Assert-Test ($r.errorCode -eq $expected) 'TAMPER_WRONG_VALIDATION'
        }
    }
    foreach ($option in @('ZipMissingDisable', 'ZipUidPin', 'ZipPinMismatch', 'ZipPayloadBad', 'ZipArm64', 'ZipVersionBad')) {
        $testOption = $option
        Test-Case ('artifact.' + $testOption + '-before-adb') {
            $opts = @{}; $opts[$testOption] = $true; $f = New-Fixture $testOption $opts; $t = New-Transport $f
            $r = Invoke-Fixture $f $t -Install
            Assert-Test (-not $r.success -and $t.State.Calls.Count -eq 0) 'BAD_MODULE_CONTACTED_ADB'
            $expected = @{ ZipMissingDisable = 'X04G_ZIP_COUNT'; ZipUidPin = 'X04G_ZIP_COUNT';
                ZipPinMismatch = 'X04G_ZIP_APK_PIN_MISMATCH'; ZipPayloadBad = 'X04G_ZIP_PAYLOAD_HASH';
                ZipArm64 = 'X04G_ZIP_ARM32'; ZipVersionBad = 'X04G_ZIP_MODULE_VERSION' }[$testOption]
            Assert-Test ($r.errorCode -eq $expected) 'ZIP_WRONG_VALIDATION'
        }
    }
    foreach ($item in @(
        @{ Name = 'none'; Devices = "List of devices attached`n"; Usb = @('FAKEUSB01') },
        @{ Name = 'multiple'; Devices = "FAKEUSB01 device`nFAKEUSB02 device`n"; Usb = @('FAKEUSB01', 'FAKEUSB02') },
        @{ Name = 'unauthorized'; Devices = "FAKEUSB01 unauthorized`n"; Usb = @('FAKEUSB01') },
        @{ Name = 'offline'; Devices = "FAKEUSB01 offline`n"; Usb = @('FAKEUSB01') },
        @{ Name = 'network'; Devices = "192.0.2.25:5555 device`n"; Usb = @('FAKEUSB01') },
        @{ Name = 'emulator'; Devices = "emulator-5602 device`n"; Usb = @('emulator-5602') },
        @{ Name = 'not-pnp-usb'; Devices = "FAKEUSB01 device`n"; Usb = @('OTHERFAKE01') }
    )) {
        $testItem = $item
        Test-Case ('usb.' + $testItem.Name) {
            $f = New-Fixture ('usb-' + $testItem.Name); $t = New-Transport $f @{ Devices = $testItem.Devices }
            $r = Invoke-Fixture $f $t -Install -UsbInventory $testItem.Usb
            Assert-Test (-not $r.success -and $t.State.Calls.Count -eq 1) 'USB_REJECTION_LATE'
            $expected = if ($testItem.Name -in @('none', 'multiple')) { 'X04G_SELECT_ONE_USB_DEVICE' }
                elseif ($testItem.Name -in @('unauthorized', 'offline')) { 'X04G_ADB_NOT_AUTHENTICATED' } else { 'X04G_SERIAL_NOT_USB' }
            Assert-Test ($r.errorCode -eq $expected) 'USB_WRONG_VALIDATION'
            Assert-NoMutation $t
        }
    }
    foreach ($item in @(
        @{ Name = 'model'; Probe = @{ model = 'OTHER' } }, @{ Name = 'device'; Probe = @{ device = 'OTHER' } },
        @{ Name = 'api'; Probe = @{ api = '30' } }, @{ Name = 'abi'; Probe = @{ abi = 'arm64-v8a' } },
        @{ Name = 'oem-hash'; Probe = @{ oem_sha = ('0' * 64) } }, @{ Name = 'oem-version'; Probe = @{ oem_version_code = '111707' } },
        @{ Name = 'oem-version-name'; Probe = @{ oem_version_name = 'OTHER' } }, @{ Name = 'oem-path'; Probe = @{ oem_path = '/data/app/OTHER/base.apk' } },
        @{ Name = 'uid-mismatch'; Probe = @{ cast_uid = '16661' } }, @{ Name = 'uid-range'; Probe = @{ launcher_uid = '9999'; cast_uid = '9999' } },
        @{ Name = 'selinux'; Probe = @{ selinux = 'Permissive' } }, @{ Name = 'adb-insecure'; Probe = @{ adb_secure = '0' } },
        @{ Name = 'root'; Root = @{ su_root = '0' } }, @{ Name = 'magisk'; Root = @{ magisk = '25999' } },
        @{ Name = 'zygisk'; Root = @{ zygisk = 'value=0' } }, @{ Name = 'denylist'; Root = @{ denylist_enforced = '1'; denylist_target = '1' } }
    )) {
        $testItem = $item
        Test-Case ('compatibility.' + $testItem.Name) {
            $f = New-Fixture ('compat-' + $testItem.Name)
            $opts = @{}; if ($testItem['Probe']) { $opts.ProbeChanges = $testItem['Probe'] }; if ($testItem['Root']) { $opts.RootChanges = $testItem['Root'] }
            $t = New-Transport $f $opts; $r = Invoke-Fixture $f $t -Install
            Assert-Test (-not $r.success) 'COMPATIBILITY_ACCEPTED'
            $expected = if ($testItem.Name -in @('model', 'device', 'api', 'abi')) { 'X04G_DEVICE_COMPATIBILITY' }
                elseif ($testItem.Name -like 'oem-*') { 'X04G_OEM_ACTIVE_PACKAGE' }
                elseif ($testItem.Name -like 'uid-*') { 'X04G_SHARED_UID' }
                elseif ($testItem.Name -in @('selinux', 'adb-insecure')) { 'X04G_SECURITY_STATE' }
                elseif ($testItem.Name -eq 'denylist') { 'X04G_DENYLIST_TARGET' } else { 'X04G_MAGISK_ZYGISK' }
            Assert-Test ($r.errorCode -eq $expected -and $r.phase -eq 'DEVICE_PREFLIGHT') 'COMPATIBILITY_WRONG_VALIDATION'
            Assert-NoMutation $t
        }
    }
    Test-Case 'check-only.no-mutations' {
        $f = New-Fixture 'check-only'; $t = New-Transport $f @{ CheckOnly = $true }; $r = Invoke-Fixture $f $t
        Assert-Test ($r.success -and $r.phase -eq 'PREFLIGHT_PASSED') 'CHECK_ONLY_FAILED'; Assert-NoMutation $t
    }
    Test-Case 'install.success-and-own-uid' {
        $f = New-Fixture 'install'; $t = New-Transport $f; $r = Invoke-Fixture $f $t -Install
        Assert-Test ($r.success -and $r.appInstalled -and $r.moduleStaged -and $r.uidPinned -and $r.enabled -and $r.rebootRequested) 'INSTALL_SUCCESS_STATE'
        Assert-Test ($r.postRebootVerified -and $r.runtimeVerified) 'POSTREBOOT_VERIFICATION_STATE'
        $keys = @($t.State.Calls | ForEach-Object { $_.Key })
        $wanted = @('DEVICES', 'PROBE', 'SU', 'STAGE', 'PUSH_APP', 'PUSH_MODULE', 'PUSH_SCRIPT', 'STAGE_VERIFY', 'INSTALL_APK', 'PROBE', 'INSTALL_MODULE', 'PIN', 'CLEANUP', 'ENABLE', 'REBOOT', 'WAIT', 'PROBE', 'SU', 'RUNTIME')
        Assert-Test (($keys -join '|') -eq ($wanted -join '|')) 'INSTALL_SEQUENCE'
        Assert-Test ($t.State.InstallPayload.Contains('trap') -and $t.State.InstallPayload.Contains('disable_own')) 'SHELL_FAILURE_TRAP_MISSING'
        Assert-Test (-not $t.State.InstallPayload.Contains('magisk --install-module') -and $t.State.InstallPayload.Contains('"$BB" unzip') -and
            $t.State.InstallPayload.Contains('mv "$d" "$target"') -and $t.State.InstallPayload.Contains('[ -f "$target/disable" ]')) 'MODULE_ATOMIC_DISABLED_STAGING'
    }
    Test-Case 'install.second-device-own-uid' {
        $f = New-Fixture 'second-uid'; $t = New-Transport $f @{ Uid = '16661' }; $r = Invoke-Fixture $f $t -Install -NoReboot
        Assert-Test ($r.success -and $t.State.Pinned -and $t.State.InitialUid -eq '16661') 'OWN_UID_NOT_DERIVED'
    }
    Test-Case 'install.no-reboot-stays-disabled' {
        $f = New-Fixture 'no-reboot'; $t = New-Transport $f; $r = Invoke-Fixture $f $t -Install -NoReboot
        Assert-Test ($r.success -and $r.phase -eq 'PREPARED_DISABLED' -and $r.uidPinned -and $t.State.Disabled) 'NO_REBOOT_DISABLED'
        Assert-Test (@($t.State.Calls | Where-Object { $_.Key -in @('ENABLE', 'REBOOT', 'WAIT') }).Count -eq 0) 'NO_REBOOT_ACTIVATED'
    }
    Test-Case 'install.false-adb-success-rejected' {
        $f = New-Fixture 'false-success'; $t = New-Transport $f @{ FalseApkSuccess = $true }; $r = Invoke-Fixture $f $t -Install
        Assert-Test (-not $r.success -and $r.errorCode -eq 'X04G_APK_INSTALL_NOT_SUCCESS') 'FALSE_APK_SUCCESS_ACCEPTED'
        Assert-Test (@($t.State.Calls | Where-Object { $_.Key -in @('INSTALL_MODULE', 'PIN', 'ENABLE', 'REBOOT') }).Count -eq 0) 'FALSE_APK_SUCCESS_CONTINUED'
    }
    Test-Case 'install.uid-changed-midway-disables' {
        $f = New-Fixture 'changed-uid'; $t = New-Transport $f @{ UidChanged = $true }; $r = Invoke-Fixture $f $t -Install
        Assert-Cleanup $r $t
        Assert-Test (-not $r.rebootRequested -and -not $r.enabled) 'UID_DRIFT_ACTIVATED'
    }
    Test-Case 'install.module-fault-disables' {
        $f = New-Fixture 'module-fault'; $t = New-Transport $f @{ FailOn = 'INSTALL_MODULE' }; $r = Invoke-Fixture $f $t -Install
        Assert-Cleanup $r $t
    }
    Test-Case 'install.postreboot-fault-disables' {
        $f = New-Fixture 'post-fault'; $t = New-Transport $f @{ PostRebootFault = $true }; $r = Invoke-Fixture $f $t -Install
        Assert-Cleanup $r $t; Assert-Test $r.rebootRequested 'POSTFAULT_BEFORE_REBOOT'
    }
    Test-Case 'install.runtime-fault-disables' {
        $f = New-Fixture 'runtime-fault'; $t = New-Transport $f @{ FailOn = 'RUNTIME' }; $r = Invoke-Fixture $f $t -Install
        Assert-Cleanup $r $t; Assert-Test ($r.rebootRequested -and $r.postRebootVerified -and -not $r.runtimeVerified) 'RUNTIME_FAILURE_STATE'
    }
    Test-Case 'transport.timeout-rejected' {
        $f = New-Fixture 'timeout'; $t = New-Transport $f @{ TimeoutOn = 'DEVICES' }; $r = Invoke-Fixture $f $t -Install
        Assert-Test (-not $r.success -and $r.errorCode -eq 'X04G_ADB_TIMEOUT') 'TIMEOUT_ACCEPTED'; Assert-NoMutation $t
    }
    Test-Case 'transport.root-denied-before-mutations' {
        $f = New-Fixture 'su-failure'; $t = New-Transport $f @{ FailOn = 'SU' }; $r = Invoke-Fixture $f $t -Install
        Assert-Test (-not $r.success -and $r.errorCode -eq 'X04G_ADB_COMMAND_FAILED') 'ROOT_FAILURE_ACCEPTED'; Assert-NoMutation $t
    }
    Test-Case 'pure.windows-argument-quoting' {
        Assert-Test ((ConvertTo-X04GArgument '') -ceq '""') 'EMPTY_ARGUMENT_QUOTE'
        Assert-Test ((ConvertTo-X04GArgument 'plain') -ceq '"plain"') 'PLAIN_ARGUMENT_QUOTE'
        Assert-Test ((ConvertTo-X04GArgument 'a b') -ceq '"a b"') 'SPACED_ARGUMENT_QUOTE'
        Assert-Test ((ConvertTo-X04GArgument 'a"b') -ceq '"a\"b"') 'EMBEDDED_QUOTE'
        Assert-Test ((ConvertTo-X04GArgument 'C:\folder with space\') -ceq '"C:\folder with space\\"') 'TRAILING_SLASH_QUOTE'
        Assert-Test ((ConvertTo-X04GArgument '$&|<>') -ceq '"$&|<>"') 'SHELL_META_QUOTE'
    }
    Test-Case 'pure.windows-argument-native-roundtrip' {
        # Windows' own parser verifies the escaping; this does not start a process.
        if (-not ('X04GInstallerHost.Arguments' -as [type])) {
            Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
namespace X04GInstallerHost {public static class Arguments {
[DllImport("shell32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern IntPtr CommandLineToArgvW(string text,out int count);
[DllImport("kernel32.dll")]static extern IntPtr LocalFree(IntPtr value);
public static string[] Split(string text){int count;IntPtr array=CommandLineToArgvW(text,out count);if(array==IntPtr.Zero)throw new InvalidOperationException();
try{string[] result=new string[count];for(int i=0;i<count;i++)result[i]=Marshal.PtrToStringUni(Marshal.ReadIntPtr(array,i*IntPtr.Size));return result;}finally{LocalFree(array);}}}}
'@
        }
        foreach ($value in @('', 'plain', 'a b', 'a"b', 'C:\folder with space\', '\\server\share\', '$&|<>', 'three\\\"slashes', "line1`nline2")) {
            $parsed = [X04GInstallerHost.Arguments]::Split(('probe.exe ' + (ConvertTo-X04GArgument $value)))
            Assert-Test ($parsed.Count -eq 2 -and $parsed[1] -ceq $value) 'NATIVE_ARGUMENT_ROUNDTRIP'
        }
    }
    Test-Case 'transport.process-utf8-stdin-and-environment' {
        # This single fixed Windows helper is not ADB and never enumerates devices.
        $helper = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
        $code = '$ProgressPreference="SilentlyContinue";[Console]::InputEncoding=New-Object Text.UTF8Encoding($false);[Console]::Out.Write([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([Console]::In.ReadToEnd())));[Console]::Error.Write($env:X04G_HOST_TEST)'
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
        $inputText = "línea1`r`nlínea2"
        $result = Invoke-X04GProcess -FilePath $helper -Arguments @('-NoProfile', '-NonInteractive', '-EncodedCommand', $encoded) -InputText $inputText -TimeoutSeconds 10 -Environment @{ X04G_HOST_TEST = 'PUBLIC_ENV_FIXTURE' }
        $expected = [Convert]::ToBase64String($script:Utf8.GetBytes($inputText.Replace("`r`n", "`n")))
        Assert-Test (-not $result.TimedOut -and $result.ExitCode -eq 0 -and $result.StdOut -ceq $expected -and $result.StdErr -ceq 'PUBLIC_ENV_FIXTURE') 'PROCESS_UTF8_STDIN_OR_ENVIRONMENT'
    }
    Test-Case 'transport.process-real-timeout-with-inert-helper' {
        $helper = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
        $code = '[Console]::Out.Write("PUBLIC_TIMEOUT_FIXTURE");[Console]::Out.Flush();Start-Sleep -Seconds 8'
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $result = Invoke-X04GProcess -FilePath $helper -Arguments @('-NoProfile', '-NonInteractive', '-EncodedCommand', $encoded) -InputText '' -TimeoutSeconds 1 -Environment @{}
        $watch.Stop()
        Assert-Test ($result.TimedOut -and $result.ExitCode -eq -1 -and $watch.Elapsed.TotalSeconds -lt 7) 'PROCESS_TIMEOUT_NOT_BOUNDED'
    }
} finally {
    if ($null -eq $script:OldLocalAppData) { Remove-Item Env:\LOCALAPPDATA -ErrorAction SilentlyContinue }
    else { $env:LOCALAPPDATA = $script:OldLocalAppData }
    Remove-Item Function:\Get-PnpDevice -ErrorAction SilentlyContinue
    Remove-Item Function:\Get-PnpDeviceProperty -ErrorAction SilentlyContinue
    if (-not $KeepFixtures) {
        $resolved = [IO.Path]::GetFullPath($script:TestRoot)
        $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        if (-not $resolved.StartsWith($parent, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -notmatch '^X04GInstallerHostTests-[a-f0-9]{32}$') { throw 'TEST_CLEANUP_PATH_REJECTED' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
$failed = @($script:Results | Where-Object { -not $_.pass })
$report = [ordered]@{ schema = 'x04g.installer.host-tests.v1'; pass = ($failed.Count -eq 0);
    powershell = $PSVersionTable.PSVersion.ToString(); tests = $script:Results.Count; assertions = $script:Checks;
    deviceAccessed = $false; realAdbExecuted = $false; realPnpQueried = $false; results = @($script:Results) }
if ($ReportPath) {
    $target = [IO.Path]::GetFullPath($ReportPath); $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))
    Write-Json $target $report
}
[pscustomobject]@{ pass = $report.pass; tests = $report.tests; assertions = $report.assertions;
    powershell = $report.powershell; devices = 0; failed = @($failed | ForEach-Object { $_.name }) } | ConvertTo-Json -Depth 4
if ($failed.Count) { exit 1 }

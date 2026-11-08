param(
    [string]$Serial = "d29e87ee",
    [string]$Adb = "E:\Android\sdk\platform-tools\adb.exe"
)

# ZeroLinux one-shot smoke check.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\smoke.ps1

$ErrorActionPreference = "Continue"
$pkg = "com.zerolinux.app"

function Section($title) {
    ""
    "== $title =="
}

Section "processes"
& $Adb -s $Serial shell "dumpsys activity processes | grep -E '$pkg' | head -20"

Section "proot count"
$proot = (& $Adb -s $Serial shell "ps -A | grep -c libproot.so").Trim()
"libproot.so processes: $proot"

Section "services"
& $Adb -s $Serial shell "dumpsys activity services $pkg | grep -E 'ServiceRecord|isForeground|foregroundId' | head -20"

Section "notifications"
& $Adb -s $Serial shell "dumpsys notification --noredact | grep -E '$pkg' | head -10"

Section "containers"
& $Adb -s $Serial shell "run-as $pkg cat files/zerolinux/containers.json"

Section "logcat"
& $Adb -s $Serial shell "logcat -d -s ZeroLinux:* PRoot:* ZlTermWeb:* | tail -40"

""
"smoke check done."

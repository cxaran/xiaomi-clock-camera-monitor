#!/system/bin/sh
# Experimental X04G / Android 10 helper. Not an installer; never run on the PC.
# Packaged by build_boot.py into the owner's own stock boot image.
# Called once by init AFTER sys.boot_completed=1, with one ADB public-key file.
# Magisk changes the boot/runtime security policy; this is not a stock-only boot.
# No network listener, unauthenticated ADB, SELinux change, or early adbd start.

umask 077
BASE=/data/adb/x04g-adb-usb-v4
STATE=$BASE/state
LOCK=$BASE/lock
ROLLBACK=/data/local/tmp/x04g-adb-usb.rollback
KEYS=/data/misc/adb/adb_keys
KEYDIR=/data/misc/adb
LOCK_HELD=0
TEMP_KEY=


# Protected, fixed-field telemetry. No key/token, identity, account or property dump.
# Failure to write diagnostics never relaxes any ADB guard.
DIAG=/cache/x04g-adb-diag-v4
DIAG_READY=0
DIAG_STAGE=ENTRY
DIAG_FAILURE_STAGE=
DIAG_FAILURE_REASON=
diag_init() {
    [ -d /cache ] && [ ! -L /cache ] || return 1
    awk '$2 == "/cache" && $4 ~ /(^|,)rw(,|$)/ { ok=1 } END { exit ok ? 0 : 1 }' /proc/mounts || return 1
    [ ! -L "$DIAG" ] || return 1
    if [ ! -d "$DIAG" ]; then mkdir "$DIAG" || return 1; fi
    chown 0:0 "$DIAG" && chmod 0700 "$DIAG" || return 1
    [ ! -L "$DIAG/status" ] && { [ ! -e "$DIAG/status" ] || [ -f "$DIAG/status" ]; } || return 1
    DIAG_READY=1
}
diag_prop() {
    diag_v=$(getprop "$1" 2>/dev/null) || diag_v=UNAVAILABLE
    case "$diag_v" in *[!a-zA-Z0-9_,.:-]*) diag_v=INVALID ;; esac
    printf '%s=%s\n' "$1" "$diag_v"
}
diag_emit() {
    [ "$DIAG_READY" = 1 ] || return 1
    case "$DIAG_STAGE" in FAILED|USB_NONE_TIMEOUT|USB_TARGET_TIMEOUT|USB_NONE_SET_FAILED|USB_TARGET_SET_FAILED)
        if [ -z "$DIAG_FAILURE_STAGE" ]; then DIAG_FAILURE_STAGE=$DIAG_STAGE; DIAG_FAILURE_REASON=$1; fi ;;
    esac
    diag_tmp=$(mktemp "$DIAG/.status.XXXXXX") || return 1
    {
        printf 'schema=2\nstage=%s\nmessage=%s\n' "$DIAG_STAGE" "$1"
        printf 'first_failure_stage=%s\nfirst_failure_reason=%s\n' "$DIAG_FAILURE_STAGE" "$DIAG_FAILURE_REASON"
        printf 'uid=%s\n' "$(id -u)"
        printf 'context=%s\n' "$(id -Z 2>/dev/null)"
        for diag_p in sys.boot_completed ro.secure ro.adb.secure ro.debuggable sys.usb.configfs persist.sys.usb.config sys.usb.config sys.usb.state sys.usb.ffs.ready init.svc.adbd service.adb.tcp.port persist.adb.tcp.port; do
            diag_prop "$diag_p"
        done
        [ ! -e /dev/usb-ffs/adb/ep0 ] || printf 'ep0=present\n'
        [ ! -d /data/adb ] || printf 'data_adb=present\n'
    } > "$diag_tmp" || { rm -f "$diag_tmp"; return 1; }
    chown 0:0 "$diag_tmp" && chmod 0600 "$diag_tmp" || { rm -f "$diag_tmp"; return 1; }
    # The first failure survives later status updates, rollback and later invocations.
    # Hard-link creation is exclusive; it never follows or overwrites an old name.
    if [ -n "$DIAG_FAILURE_STAGE" ] && [ ! -e "$DIAG/failure" ] && [ ! -L "$DIAG/failure" ]; then
        ln "$diag_tmp" "$DIAG/failure" || :
    fi
    mv -f "$diag_tmp" "$DIAG/status" || { rm -f "$diag_tmp"; return 1; }
}
tcp_disabled() {
    for tcp_p in service.adb.tcp.port persist.adb.tcp.port; do
        tcp_v=$(getprop "$tcp_p") || return 1
        case "$tcp_v" in ''|0|-1) ;; *) return 1 ;; esac
    done
}
usb_ready() {
    [ "$(getprop sys.usb.config)" = "$1" ] && [ "$(getprop sys.usb.state)" = "$1" ] || return 1
    if [ "$1" = mtp,adb ]; then
        [ "$(getprop init.svc.adbd)" = running ] && [ "$(getprop sys.usb.ffs.ready)" = 1 ] || return 1
    elif [ "$1" = none ]; then
        [ "$(getprop init.svc.adbd)" = stopped ] && [ "$(getprop sys.usb.ffs.ready)" = 0 ] || return 1
    fi
}
request_runtime_usb() {
    usb_target=$1
    case "$usb_target" in none|mtp|mtp,adb) ;; *) return 1 ;; esac
    [ "$usb_target" != mtp,adb ] || tcp_disabled || return 1
    if usb_ready "$usb_target"; then return 0; fi
    DIAG_STAGE=USB_NONE_REQUEST
    diag_emit 'requesting stock gadget cleanup' || :
    setprop sys.usb.config none || { DIAG_STAGE=USB_NONE_SET_FAILED; diag_emit 'stock none request failed' || :; return 1; }
    usb_i=0
    while ! usb_ready none; do
        [ "$usb_i" -lt 10 ] || { DIAG_STAGE=USB_NONE_TIMEOUT; diag_emit 'stock none acknowledgment not observed' || :; return 1; }
        usb_i=$((usb_i + 1))
        sleep 1 || return 1
    done
    [ "$usb_target" != none ] || return 0
    [ "$usb_target" != mtp,adb ] || tcp_disabled || return 1
    DIAG_STAGE=USB_TARGET_REQUEST
    diag_emit 'requesting stock target configuration' || :
    setprop sys.usb.config "$usb_target" || { DIAG_STAGE=USB_TARGET_SET_FAILED; diag_emit 'stock target request failed' || :; return 1; }
    usb_i=0
    while ! usb_ready "$usb_target"; do
        [ "$usb_i" -lt 20 ] || { DIAG_STAGE=USB_TARGET_TIMEOUT; diag_emit 'stock target state or FFS or adbd not observed' || :; return 1; }
        usb_i=$((usb_i + 1))
        sleep 1 || return 1
    done
    DIAG_STAGE=USB_READY
    diag_emit 'stock USB target observed; host authentication remains untested' || :
}

say() { printf '%s\n' "x04g-adb-usb: $*"; diag_emit "$*" || :; }
fail() { DIAG_STAGE=FAILED; say "$*" >&2; exit 1; }
cleanup() {
    if [ -n "$TEMP_KEY" ]; then rm -f "$TEMP_KEY"; fi
    if [ "$LOCK_HELD" = 1 ]; then
        rm -f "$LOCK/key.decoded" "$LOCK/keys.new"
        rmdir "$LOCK" 2>/dev/null
    fi
}
trap cleanup 0
trap 'exit 1' 1 2 15

[ "$(id -u)" = 0 ] || fail 'root required'
diag_init || say 'protected cache telemetry unavailable'
diag_emit 'helper entered' || :
[ ! -L /data/adb ] && [ -d /data/adb ] || fail 'Magisk data directory unavailable'
[ ! -L "$BASE" ] || fail 'unsafe state directory'
mkdir -p "$BASE" || fail 'cannot create state directory'
chown 0:0 "$BASE" && chmod 0700 "$BASE" || fail 'cannot protect state directory'
mkdir "$LOCK" 2>/dev/null || fail 'another invocation or stale lock exists'
LOCK_HELD=1

read_saved() { cat "$STATE/$1"; }
touch_state() { : > "$STATE/$1"; }

key_context_ok() {
    context_line=$(ls -Zd "$1") || return 1
    set -- $context_line
    [ "$#" -ge 1 ] && [ "$1" = u:object_r:adb_keys_file:s0 ]
}

# Create and label the replacement in the key store's own directory, then rename
# atomically. Never truncate the live key store. Arguments: source, uid:gid, mode.
commit_keys() {
    if [ -n "$TEMP_KEY" ]; then rm -f "$TEMP_KEY" || return 1; TEMP_KEY=; fi
    TEMP_KEY=$(mktemp "$KEYDIR/.x04g-adb-usb.XXXXXX") || return 1
    cat "$1" > "$TEMP_KEY" || return 1
    chown "$2" "$TEMP_KEY" && chmod "$3" "$TEMP_KEY" && restorecon "$TEMP_KEY" || return 1
    key_context_ok "$TEMP_KEY" || return 1
    mv -f "$TEMP_KEY" "$KEYS" || return 1
    TEMP_KEY=
    key_context_ok "$KEYS" || return 1
}

# Remove only our newly added public key; preserve keys added by other actors.
# USB configuration is restored only if unchanged since this helper set it.
rollback() {
    [ -d "$STATE" ] && [ ! -L "$STATE" ] && [ -f "$STATE/complete" ] || return 1
    if [ -f "$STATE/rolled_back" ]; then return 0; fi
    rb_bad=0
    if [ -f "$STATE/key_touched" ]; then
        if [ -L "$KEYS" ]; then
            rb_bad=1
        elif [ -f "$KEYS" ]; then
            if [ -f "$STATE/key_added" ]; then
                if [ -f "$STATE/keys_after_apply" ] && cmp -s "$KEYS" "$STATE/keys_after_apply"; then
                    # With no intervening edits, restore exact original bytes.
                    if [ -f "$STATE/keys_absent" ]; then : > "$LOCK/keys.new" || return 1
                    else cp "$STATE/adb_keys.before" "$LOCK/keys.new" || return 1
                    fi
                else
                    rb_token=$(read_saved key_token) || return 1
                    rb_last=$(tail -c 1 "$KEYS" | od -An -tu1 | tr -d ' \n')
                    # Keep every other line verbatim, including final-newline state.
                    awk -v key="$rb_token" -v last="$rb_last" '
                        $1 != key { if (seen) printf "\n"; printf "%s", $0; seen=1 }
                        END { if (seen && last == "10") printf "\n" }
                    ' "$KEYS" > "$LOCK/keys.new" || return 1
                fi
            else cp "$KEYS" "$LOCK/keys.new" || return 1
            fi
            if [ -f "$STATE/keys_absent" ] && [ ! -s "$LOCK/keys.new" ]; then
                rm -f "$KEYS" || rb_bad=1
            else
                if [ -f "$STATE/keys_metadata" ]; then
                    set -- $(read_saved keys_metadata)
                    [ "$#" = 3 ] || return 1
                    commit_keys "$LOCK/keys.new" "$1:$2" "$3" || rb_bad=1
                else
                    commit_keys "$LOCK/keys.new" 1000:2000 0640 || rb_bad=1
                fi
            fi
        fi
    fi
    if [ -f "$STATE/key_dir_created" ] && [ -d "$KEYDIR" ]; then
        # rmdir preserves a directory that now contains other files/keys.
        rmdir "$KEYDIR" 2>/dev/null || :
    fi
    if [ -f "$STATE/usb_changed" ]; then
        rb_before=$(read_saved usb_config) || return 1
        rb_now=$(getprop persist.sys.usb.config) || return 1
        if [ "$rb_now" = "$rb_before" ]; then :
        elif [ "$rb_now" = mtp,adb ]; then setprop persist.sys.usb.config "$rb_before" || rb_bad=1
        else rb_bad=1
        fi
    fi
    if [ -f "$STATE/usb_effective_changed" ]; then
        if [ ! -f "$STATE/usb_effective_config" ]; then rb_bad=1
        else
            rb_runtime_before=$(read_saved usb_effective_config) || return 1
            rb_runtime_now=$(getprop sys.usb.config) || return 1
            case "$rb_runtime_before" in none|mtp|mtp,adb) ;; *) return 1 ;; esac
            if [ "$rb_runtime_now" = "$rb_runtime_before" ]; then
                usb_ready "$rb_runtime_before" || rb_bad=1
            elif [ "$rb_runtime_now" = none ] || [ "$rb_runtime_now" = mtp,adb ]; then
                # Do not restore runtime while another rollback failure remains.
                if [ "$rb_bad" = 0 ]; then request_runtime_usb "$rb_runtime_before" || rb_bad=1; fi
            else rb_bad=1
            fi
        fi
    fi
    [ "$rb_bad" = 0 ] || return 1
    touch_state rolled_back || return 1
    rm -f "$STATE/needs_manual"
    return 0
}

if [ -e "$ROLLBACK" ]; then
    [ ! -L "$ROLLBACK" ] && [ -f "$ROLLBACK" ] || fail 'unsafe rollback marker'
    if rollback; then
        rm -f "$ROLLBACK" || fail 'cannot clear rollback marker'
        say 'rollback completed; helper remains disabled'
        exit 0
    fi
    [ ! -d "$STATE" ] || touch_state needs_manual
    fail 'rollback incomplete; retained protected state for review'
fi
[ ! -f "$STATE/rolled_back" ] || { say 'disabled after rollback'; exit 0; }
[ ! -f "$STATE/needs_manual" ] || fail 'previous incomplete rollback requires review'

[ "$#" = 1 ] || fail 'one public-key file argument required'
KEY_FILE=$1
[ -f "$KEY_FILE" ] && [ ! -L "$KEY_FILE" ] || fail 'public-key file unavailable'
[ "$(getprop sys.boot_completed)" = 1 ] || fail 'Android boot not completed'
[ "$(getprop ro.secure)" = 1 ] || fail 'ro.secure must remain 1'
[ "$(getprop ro.adb.secure)" = 1 ] || fail 'ADB RSA authentication must remain enabled'
[ "$(getprop ro.debuggable)" = 0 ] || fail 'ro.debuggable must remain 0'
[ "$(getenforce)" = Enforcing ] || fail 'SELinux must remain enforcing'
[ -e /dev/usb-ffs/adb/ep0 ] || fail 'ADB FunctionFS endpoint unavailable'
[ "$(getprop sys.usb.configfs)" = 1 ] || fail 'USB configfs unavailable'
for prop in service.adb.tcp.port persist.adb.tcp.port; do
    port=$(getprop "$prop") || fail 'cannot inspect ADB TCP setting'
    case "$port" in ''|0|-1) ;; *) fail 'ADB TCP setting must be disabled' ;; esac
done
[ -d /data/misc ] && [ -d /data/property ] || fail 'Android data unavailable'
[ ! -L "$KEYDIR" ] && [ ! -L "$KEYS" ] || fail 'unsafe ADB key location'
[ ! -e "$KEYS" ] || [ -f "$KEYS" ] || fail 'ADB key store is not a regular file'

# Android's RSA2048 public-key wire structure is 524 bytes / 700 base64 chars.
# Reject PEM/private keys, multiple lines, malformed base64 and other layouts.
key_size=$(wc -c < "$KEY_FILE") || fail 'cannot inspect public key'
[ "$key_size" -ge 700 ] && [ "$key_size" -le 2048 ] || fail 'unexpected public-key size'
awk 'END { exit NR == 1 ? 0 : 1 }' "$KEY_FILE" || fail 'public key must contain exactly one line'
KEY_TOKEN=$(awk 'NR == 1 { print $1 }' "$KEY_FILE") || fail 'cannot parse public key'
[ "${#KEY_TOKEN}" = 700 ] || fail 'unexpected public-key encoding length'
case "$KEY_TOKEN" in *[!A-Za-z0-9+/=]*|'') fail 'invalid public-key encoding' ;; esac
printf '%s' "$KEY_TOKEN" | base64 -d > "$LOCK/key.decoded" 2>/dev/null || fail 'invalid base64 public key'
[ "$(wc -c < "$LOCK/key.decoded")" = 524 ] || fail 'unexpected public-key structure'
[ "$(od -An -tu4 -N4 "$LOCK/key.decoded" | tr -d ' \n')" = 64 ] || fail 'unsupported RSA modulus length'
key_exp=$(od -An -tu4 -j520 -N4 "$LOCK/key.decoded" | tr -d ' \n')
case "$key_exp" in 3|65537) ;; *) fail 'unsupported RSA exponent' ;; esac

if [ ! -d "$STATE" ]; then
    NEW=$BASE/state.new
    [ ! -e "$NEW" ] && [ ! -L "$NEW" ] || fail 'incomplete snapshot requires review'
    mkdir "$NEW" || fail 'cannot create protected snapshot'
    printf '%s\n' "$KEY_TOKEN" > "$NEW/key_token" || fail 'cannot save key identity'
    if [ -f "$KEYS" ]; then
        cp -p "$KEYS" "$NEW/adb_keys.before" || fail 'cannot back up ADB key store'
        stat -c '%u %g %a' "$KEYS" > "$NEW/keys_metadata" || fail 'cannot back up key metadata'
        if awk -v key="$KEY_TOKEN" '$1 == key { found=1 } END { exit found ? 0 : 1 }' "$KEYS"; then
            : > "$NEW/key_preexisting" || fail 'cannot save existing-key state'
        fi
    else
        : > "$NEW/keys_absent" || fail 'cannot save absent-key state'
    fi
    getprop persist.sys.usb.config > "$NEW/usb_config" || fail 'cannot back up persistent USB config'
    : > "$NEW/complete" || fail 'cannot finish snapshot'
    mv "$NEW" "$STATE" || fail 'cannot commit snapshot'
fi
[ ! -L "$STATE" ] && [ -f "$STATE/complete" ] || fail 'invalid protected snapshot'
[ "$(read_saved key_token)" = "$KEY_TOKEN" ] || fail 'public key differs from saved transaction'

# Migrate a valid old snapshot BEFORE any persistent/runtime USB mutation.
# This version only handles the three explicitly supported OEM configurations.
if [ ! -f "$STATE/usb_effective_config" ]; then
    runtime_before=$(getprop sys.usb.config) || fail 'cannot snapshot effective USB config'
    case "$runtime_before" in none|mtp|mtp,adb) ;; *) fail 'unsupported effective USB config' ;; esac
    [ ! -L "$STATE/usb_effective_config.new" ] || fail 'unsafe effective USB snapshot'
    printf '%s\n' "$runtime_before" > "$STATE/usb_effective_config.new" &&
        mv -f "$STATE/usb_effective_config.new" "$STATE/usb_effective_config" || fail 'cannot commit effective USB snapshot'
fi
runtime_saved=$(read_saved usb_effective_config) || fail 'missing effective USB snapshot'
case "$runtime_saved" in none|mtp|mtp,adb) ;; *) fail 'invalid effective USB snapshot' ;; esac
runtime_current=$(getprop sys.usb.config) || fail 'cannot inspect current effective USB config'
case "$runtime_current" in none|mtp|mtp,adb) ;; *) fail 'unsupported current effective USB config' ;; esac
DIAG_STAGE=GUARDS_PASSED
diag_emit 'all guards and snapshot validated' || :

apply() {
    if [ ! -d "$KEYDIR" ]; then
        touch_state key_dir_created || return 1
        mkdir "$KEYDIR" || return 1
        chown 1000:2000 "$KEYDIR" && chmod 02750 "$KEYDIR" && restorecon "$KEYDIR" || return 1
    fi
    if [ -f "$KEYS" ]; then cp "$KEYS" "$LOCK/keys.new" || return 1
    else : > "$LOCK/keys.new" || return 1
    fi
    if ! awk -v key="$KEY_TOKEN" '$1 == key { found=1 } END { exit found ? 0 : 1 }' "$KEYS" 2>/dev/null; then
        if [ ! -f "$STATE/key_preexisting" ]; then touch_state key_added || return 1; fi
        # Preserve an existing final line without a newline before appending.
        if [ -s "$LOCK/keys.new" ] && [ "$(tail -c 1 "$LOCK/keys.new" | od -An -tu1 | tr -d ' \n')" != 10 ]; then
            printf '\n' >> "$LOCK/keys.new" || return 1
        fi
        printf '%s x04g-adb-usb\n' "$KEY_TOKEN" >> "$LOCK/keys.new" || return 1
    fi
    if [ ! -f "$STATE/keys_after_apply" ]; then cp "$LOCK/keys.new" "$STATE/keys_after_apply" || return 1; fi
    touch_state key_touched || return 1
    commit_keys "$LOCK/keys.new" 1000:2000 0640 || return 1
    DIAG_STAGE=PUBLIC_KEY_COMMITTED
    diag_emit 'public key atomically committed and labeled' || :
    # Save ownership before any persistent/runtime USB configuration mutation.
    touch_state usb_effective_changed || return 1
    # Only the authenticated USB mechanism; OEM init starts adbd normally.
    touch_state usb_changed || return 1
    setprop persist.sys.usb.config mtp,adb || return 1
    [ "$(getprop persist.sys.usb.config)" = mtp,adb ] || return 1
    request_runtime_usb mtp,adb || return 1
    touch_state applied || return 1
    DIAG_STAGE=APPLIED_USB_OBSERVED
    diag_emit 'USB state observed; host authentication remains untested' || :
}

if apply; then
    say 'authenticated USB configuration requested; host connection still requires validation'
    exit 0
fi
if [ -z "$DIAG_FAILURE_STAGE" ]; then DIAG_FAILURE_STAGE=$DIAG_STAGE; DIAG_FAILURE_REASON='apply failed before rollback'; fi
say 'apply failed; attempting rollback' >&2
if rollback; then
    fail 'apply aborted and rolled back; helper remains disabled'
fi
touch_state needs_manual
fail 'apply and rollback incomplete; retained protected state for review'

#!/usr/bin/env bash
# Runs a headful test command so that a hang still leaves a diagnosable log.
#
# Usage: run-with-hang-watch.sh <limit-minutes> <log-file> <command> [args...]
#
# A job that hits its own timeout-minutes, or a runner that dies, keeps no log
# at all — which is all the Windows headful jobs ever left behind. This runs the
# command in the background with its output mirrored to <log-file> (upload it
# with `if: always()`), prints a heartbeat every couple of minutes (elapsed
# time, the last case started, free memory), and once <limit-minutes> pass:
# prints the last case started, dumps every JVM's threads (jcmd; a native image
# has none), lists the biggest processes, kills the command and fails the step.
# Keep <limit-minutes> well under the job's timeout-minutes.
set -uo pipefail

limit_min="$1"
log="$2"
shift 2

heartbeat_s=120
# HANG_WATCH_LIMIT_SECONDS overrides the limit, to exercise the hang path quickly.
limit_s=${HANG_WATCH_LIMIT_SECONDS:-$((limit_min * 60))}
mkdir -p "$(dirname "$log")"
: > "$log"

( "$@" 2>&1 | tee -a "$log"; exit "${PIPESTATUS[0]}" ) &
pid=$!

last_case() {
  grep -E '\[tao-headful\] (START|OK|FAIL)' "$log" | tail -1 || true
}

free_memory() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
      powershell -NoProfile -Command \
        '$o = Get-CimInstance Win32_OperatingSystem; "free {0:N0} MB of {1:N0} MB" -f ($o.FreePhysicalMemory / 1KB), ($o.TotalVisibleMemorySize / 1KB)' \
        2>/dev/null || true
      ;;
    Darwin) vm_stat | awk '/Pages free/ {printf "free %d MB\n", $3 * 4096 / 1048576}' || true ;;
    *) free -m | awk '/^Mem:/ {print "free " $7 " MB of " $2 " MB"}' || true ;;
  esac
}

biggest_processes() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
      powershell -NoProfile -Command \
        'Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 12 Id, ProcessName, @{n="WorkingSetMB";e={[int]($_.WorkingSet64/1MB)}}, @{n="CPU_s";e={[int]$_.CPU}} | Format-Table -AutoSize | Out-String -Width 200' \
        2>/dev/null || true
      ;;
    *) ps -axo pid,rss,pcpu,comm | sort -k2 -rn | head -12 || true ;;
  esac
}

dump_jvms() {
  local jcmd="${JAVA_HOME:-}/bin/jcmd"
  [ -x "$jcmd" ] || [ -x "$jcmd.exe" ] || jcmd="jcmd"
  local jvm
  # The suite's own JVM, and Gradle's in case the wait is in the build itself.
  for jvm in $("$jcmd" -l 2>/dev/null | awk '/TaoHeadfulTestSuiteMain|GradleDaemon/ {print $1}'); do
    echo "::group::Threads of JVM $jvm"
    "$jcmd" "$jvm" Thread.print -l 2>&1 || true
    echo "::endgroup::"
  done
}

descendants() {
  local child
  for child in $(pgrep -P "$1" 2>/dev/null); do
    descendants "$child"
    echo "$child"
  done
}

kill_command() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
      # MSYS pids don't map onto the Windows process tree; go by image name.
      taskkill //F //T //IM tao-native-test.exe > /dev/null 2>&1 || true
      taskkill //F //T //IM java.exe > /dev/null 2>&1 || true
      ;;
    *)
      local victims
      victims="$(descendants "$pid")"
      # shellcheck disable=SC2086
      [ -n "$victims" ] && kill -KILL $victims 2>/dev/null
      ;;
  esac
  kill -KILL "$pid" 2>/dev/null || true
}

start=$SECONDS
next_beat=$heartbeat_s
while kill -0 "$pid" 2>/dev/null; do
  elapsed=$((SECONDS - start))
  if [ "$elapsed" -ge "$limit_s" ]; then
    echo "::error::no result after ${limit_min} min; last case: $(last_case)"
    echo "HANG: no result after ${limit_min} min"
    echo "HANG: last case: $(last_case)"
    echo "HANG: memory: $(free_memory)"
    biggest_processes
    dump_jvms
    kill_command
    wait "$pid" 2>/dev/null
    exit 1
  fi
  if [ "$elapsed" -ge "$next_beat" ]; then
    echo "[hang-watch] $((elapsed / 60)) min — $(free_memory) — $(last_case)"
    next_beat=$((next_beat + heartbeat_s))
  fi
  sleep 5
done
wait "$pid"

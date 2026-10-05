#!/usr/bin/env bash
# Runs a headful test command so that a hang still leaves a diagnosable log.
#
# Usage: run-with-hang-watch.sh <limit-minutes> <log-file> <command> [args...]
#
# A job that hits its own timeout-minutes, or a runner that dies, keeps no log
# at all — which is all the Windows headful jobs ever left behind. And a step
# only ends once every process holding its output has exited: one wedged
# somewhere it cannot even be killed (inside a GPU driver call, say) keeps the
# step open until the job dies with it.
#
# So the command writes only to <log-file> (upload it with `if: always()`), and
# this script copies new lines from it to the step every few seconds. Nothing
# it starts ever holds the step's output: diagnostics go through temp files,
# and on a hang it gives up on the command instead of waiting for it.
#
# A hang is either no new output for STALL_MINUTES (no case runs that long —
# a stall after the suite's summary is a hang at exit), or <limit-minutes> in
# total. Either way it prints the last case started, the biggest processes and
# the test JVM's threads (jcmd, each bounded: attaching to a process wedged
# under the Windows loader lock blocks), tries to kill the command, and fails
# the step. Keep <limit-minutes> well under the job's timeout-minutes.
#
# With HANG_WATCH_PROGRESS=<name> and GITHUB_TOKEN (checks: write), progress is
# also sent off the runner every 30 s, as a check run <name> on the commit: the
# elapsed time, the last case and the log's tail. GitHub keeps each update as
# it lands, so it survives a runner that wedges or dies.
set -uo pipefail

limit_min="$1"
log="$2"
shift 2

poll_s=5
heartbeat_s=120
stall_s=${HANG_WATCH_STALL_SECONDS:-$((${STALL_MINUTES:-8} * 60))}
# HANG_WATCH_LIMIT_SECONDS overrides the limit, to exercise the hang path quickly.
limit_s=${HANG_WATCH_LIMIT_SECONDS:-$((limit_min * 60))}
mkdir -p "$(dirname "$log")"
: > "$log"
scratch="$(mktemp -d)"

"$@" > "$log" 2>&1 < /dev/null &
pid=$!

printed=0
flush_log() {
  local lines
  lines=$(wc -l < "$log" | tr -d ' ')
  if [ "$lines" -gt "$printed" ]; then
    sed -n "$((printed + 1)),${lines}p" "$log"
    printed=$lines
  fi
}

last_case() {
  grep -E '\[tao-headful\] (START|OK|FAIL)' "$log" | tail -1 || true
}

# Runs "$@" for at most $1 seconds, its output via a file so a stuck process
# never holds the step's output; prints what it produced.
bounded() {
  local secs="$1" out="$scratch/bounded.$RANDOM" p i=0
  shift
  "$@" > "$out" 2>&1 < /dev/null &
  p=$!
  while kill -0 "$p" 2>/dev/null; do
    if [ "$i" -ge "$secs" ]; then
      kill -KILL "$p" 2>/dev/null
      cat "$out"
      echo "(gave up after ${secs}s: $*)"
      return 1
    fi
    sleep 1
    i=$((i + 1))
  done
  cat "$out"
}

progress_id=""
progress_start() {
  [ -n "${HANG_WATCH_PROGRESS:-}" ] && [ -n "${GITHUB_TOKEN:-}" ] || return 0
  progress_id="$(bounded 20 env GH_TOKEN="$GITHUB_TOKEN" gh api "repos/$GITHUB_REPOSITORY/check-runs" \
    -f name="$HANG_WATCH_PROGRESS" -f head_sha="$GITHUB_SHA" -f status=in_progress --jq .id)"
  case "$progress_id" in *[!0-9]* | '') echo "[hang-watch] no progress check run: $progress_id"; progress_id="" ;; esac
}

# Fire and forget: detached, no output, so it can neither block nor hold the step.
progress_update() {
  [ -n "$progress_id" ] || return 0
  local title="$1" tail
  tail="$(tail -60 "$log")"
  (GH_TOKEN="$GITHUB_TOKEN" gh api -X PATCH "repos/$GITHUB_REPOSITORY/check-runs/$progress_id" \
    -f "output[title]=$title" -f "output[summary]=$title" -f "output[text]=$tail" \
    > /dev/null 2>&1 < /dev/null &)
}

# Closes the check run with a conclusion; bounded rather than detached, so the
# last word lands before the step ends.
progress_finish() {
  [ -n "$progress_id" ] || return 0
  bounded 20 env GH_TOKEN="$GITHUB_TOKEN" gh api -X PATCH "repos/$GITHUB_REPOSITORY/check-runs/$progress_id" \
    -f status=completed -f conclusion="$1" -f "output[title]=$2" -f "output[summary]=$2" \
    -f "output[text]=$(tail -60 "$log")" > /dev/null
}

is_windows() {
  case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) return 0 ;; *) return 1 ;; esac
}

free_memory() {
  if is_windows; then
    powershell -NoProfile -Command \
      '$o = Get-CimInstance Win32_OperatingSystem; "free {0:N0} MB of {1:N0} MB" -f ($o.FreePhysicalMemory / 1KB), ($o.TotalVisibleMemorySize / 1KB)'
  elif [ "$(uname -s)" = Darwin ]; then
    vm_stat | awk '/Pages free/ {printf "free %d MB\n", $3 * 4096 / 1048576}'
  else
    free -m | awk '/^Mem:/ {print "free " $7 " MB of " $2 " MB"}'
  fi
}

biggest_processes() {
  if is_windows; then
    powershell -NoProfile -Command \
      'Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 12 Id, ProcessName, Responding, @{n="WorkingSetMB";e={[int]($_.WorkingSet64/1MB)}}, @{n="CPU_s";e={[int]$_.CPU}}, @{n="Threads";e={$_.Threads.Count}} | Format-Table -AutoSize | Out-String -Width 200'
  else
    ps -axo pid,rss,pcpu,stat,comm | sort -k2 -rn | head -12
  fi
}

jcmd_bin() {
  local j="${JAVA_HOME:-}/bin/jcmd"
  if [ -x "$j" ] || [ -x "$j.exe" ]; then echo "$j"; else echo jcmd; fi
}

dump_jvms() {
  local jcmd jvm
  jcmd="$(jcmd_bin)"
  # The suite's own JVM, and Gradle's in case the wait is in the build itself.
  for jvm in $(bounded 20 "$jcmd" -l | awk '/TaoHeadfulTestSuiteMain|GradleDaemon/ {print $1}'); do
    echo "::group::Threads of JVM $jvm"
    bounded 30 "$jcmd" "$jvm" Thread.print -l
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
  if is_windows; then
    # MSYS pids don't map onto the Windows process tree; go by image name.
    bounded 20 taskkill //F //T //IM tao-native-test.exe
    bounded 20 taskkill //F //T //IM java.exe
  else
    local victims
    victims="$(descendants "$pid")"
    # shellcheck disable=SC2086
    [ -n "$victims" ] && kill -KILL $victims 2>/dev/null
  fi
  kill -KILL "$pid" 2>/dev/null || true
}

report_hang() {
  local why="$1"
  flush_log
  progress_update "HANG: $why; last case: $(last_case)"
  echo "::warning title=Headful run wedged::$why; last case: $(last_case)"
  echo "HANG: $why"
  echo "HANG: last case: $(last_case)"
  echo "HANG: last output line: $(tail -1 "$log")"
  echo "HANG: memory: $(bounded 20 free_memory)"
  bounded 30 biggest_processes
  dump_jvms
  echo "HANG: killing the run"
  kill_command
  flush_log
  echo "::error::$why; last case: $(last_case)"
  progress_finish failure "HANG: $why; last case: $(last_case)"
  # Not waiting for the command: a process that cannot be killed would keep
  # this step, and with it the job, from ever finishing.
  exit 1
}

progress_start
start=$SECONDS
last_growth=$SECONDS
next_progress=0
last_size=0
next_beat=$heartbeat_s
while kill -0 "$pid" 2>/dev/null; do
  flush_log
  now=$SECONDS
  size=$(wc -c < "$log" | tr -d ' ')
  if [ "$size" -ne "$last_size" ]; then
    last_size=$size
    last_growth=$now
  fi
  if [ $((now - last_growth)) -ge "$stall_s" ]; then
    report_hang "no output for $(((now - last_growth) / 60)) min"
  fi
  if [ $((now - start)) -ge "$limit_s" ]; then
    report_hang "no result after $(((now - start) / 60)) min"
  fi
  if [ $((now - start)) -ge "$next_progress" ]; then
    progress_update "$(((now - start) / 60)) min — $(last_case)"
    next_progress=$((next_progress + 30))
  fi
  if [ $((now - start)) -ge "$next_beat" ]; then
    echo "[hang-watch] $(((now - start) / 60)) min — $(bounded 20 free_memory) — $(last_case)"
    next_beat=$((next_beat + heartbeat_s))
  fi
  sleep "$poll_s"
done
wait "$pid"
status=$?
flush_log
if [ "$status" -eq 0 ]; then conclusion=success; else conclusion=failure; fi
progress_finish "$conclusion" "finished with exit status $status after $(((SECONDS - start) / 60)) min"
exit "$status"

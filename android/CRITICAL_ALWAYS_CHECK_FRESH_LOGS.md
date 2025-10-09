# ⚠️ CRITICAL: ALWAYS GET FRESH LOGS - NEVER USE CACHED/OLD LOGS ⚠️

## NEVER LOOK AT OLD BACKGROUND BASH OUTPUT

**PROBLEM**: I keep checking old BashOutput from background processes that were started hours ago, giving completely wrong information about the current state.

**SOLUTION**:
1. **ALWAYS** use `adb logcat -t <count>` to get the MOST RECENT logs
2. **NEVER** use BashOutput from background shells that have been running for a long time
3. When user says "try again" or "still failing", those background logs are STALE
4. The timestamp on BashOutput shows when it was captured - CHECK IT
5. If the timestamp is more than 5 minutes old, IT IS USELESS

## How to get FRESH logs:

```bash
# Get the last 200 lines from logcat (RECENT)
adb logcat -t 200 -s "InvisibleNIP55Handler:*" "MainActivity:*"

# Get logs since a specific time
adb logcat -t '10-03 20:45:00.000'

# Clear logs and start fresh monitoring
adb logcat -c && adb logcat -s "InvisibleNIP55Handler:*"
```

## Signs you're looking at OLD logs:
- Logs mention `http://localhost:3000` (we don't use dev server anymore)
- Logs show package `com.frostr.igloo.debug` (we fixed this)
- The BashOutput timestamp is old
- User says "still broken" but logs show it working

---

**IF USER SAYS SOMETHING IS BROKEN, GET FRESH LOGS IMMEDIATELY WITH `adb logcat -t 500`**

**DO NOT TRUST BACKGROUND BASH PROCESSES THAT HAVE BEEN RUNNING FOR HOURS**

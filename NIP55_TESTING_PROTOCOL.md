# NIP-55 Testing Protocol Guide

## Pre-Test Setup (ALWAYS DO FIRST)

1. **Clear all logs before every test**:
```bash
adb logcat -c
```

2. **Start fresh log monitoring**:
```bash
adb logcat -s "InvisibleNIP55Handler:*" "SecureIglooWrapper:*" "AsyncBridge:*" | head -50
```

## Test Command Format

**ALWAYS use InvisibleNIP55Handler to test the complete pipeline:**

### For sign_event (JSON in URI data):
```bash
adb shell am start \
    -a android.intent.action.VIEW \
    -d "nostrsigner:URL_ENCODED_JSON" \
    --es type "sign_event" \
    --es id "test_[unique_id]" \
    --es current_user "0000000000000000000000000000000000000000000000000000000000000000" \
    -n com.frostr.igloo.debug/com.frostr.igloo.InvisibleNIP55Handler
```

### Example with URL encoding:
```bash
adb shell am start \
    -a android.intent.action.VIEW \
    -d "nostrsigner:%7B%22content%22%3A%20%22nostr%20test%22%2C%20%22created_at%22%3A%201727472000%2C%20%22kind%22%3A%201%2C%20%22pubkey%22%3A%20%220000000000000000000000000000000000000000000000000000000000000000%22%2C%20%22tags%22%3A%20%5B%5D%7D" \
    --es type "sign_event" \
    --es id "test_working_demo" \
    --es current_user "0000000000000000000000000000000000000000000000000000000000000000" \
    -n com.frostr.igloo.debug/com.frostr.igloo.InvisibleNIP55Handler
```

### For get_public_key (no URI data):
```bash
adb shell am start \
    -a android.intent.action.VIEW \
    -d "nostrsigner:" \
    --es type "get_public_key" \
    --es id "test_[unique_id]" \
    -n com.frostr.igloo.debug/com.frostr.igloo.InvisibleNIP55Handler
```

**Key requirements:**
- For sign_event: JSON must be URL-encoded in the URI data
- For get_public_key: URI is just `nostrsigner:` with no data
- Use `--es` for Intent extras (type, id, current_user)
- Always use unique test ID to identify your specific test

## Checking Results

1. **Wait 5-10 seconds after running command**

2. **CRITICAL: Verify timestamp recency**:
   ```bash
   # Check current time first
   date "+%H:%M"

   # Then look for logs from the last few minutes only
   adb logcat -d | grep "test_[your_id]" | tail -10
   ```

3. **Check logs for YOUR specific test ID**:
   - Look for the test ID you just used
   - **CRITICAL**: Verify timestamps are within the last 2-3 minutes
   - If you see old timestamps (more than 5 minutes old), you're looking at old logs!

4. **Better log checking commands**:
   ```bash
   # Method 1: Get most recent logs with your test ID
   adb logcat -d | grep "test_[your_id]" | tail -10

   # Method 2: Use time-based filtering (check last 2 minutes)
   adb logcat -d | grep -A 5 -B 5 "test_[your_id]" | tail -20

   # Method 3: If no logs found, trust user observations and investigate
   # Sometimes the user sees the prompt working but logs aren't visible
   ```

5. **Success indicators to look for**:
   ```
   InvisibleNIP55Handler: NIP-55 intent handler started
   InvisibleNIP55Handler: Processing NIP-55 request with Intent extras
   InvisibleNIP55Handler: Extracted NIP-55 request: {"type":"sign_event","id":"test_[your_id]"...}
   InvisibleNIP55Handler: NIP-55 request processed successfully
   InvisibleNIP55Handler: Returned successful result: {"type":"sign_event","result":{"id":"...","sig":"..."}}
   ```

6. **Failure indicators**:
   ```
   InvisibleNIP55Handler: NIP-55 request failed: [specific error]
   InvisibleNIP55Handler: Returning error result: [error message]
   ```

7. **If user reports success but you don't see logs**:
   - **TRUST THE USER** - they can see the UI working
   - Investigate why logs aren't visible instead of declaring failure
   - Check for recent timestamps more carefully
   - Look in AsyncBridge logs for the response flow

## Critical Rules

1. **NEVER declare success without seeing the complete success flow in logs OR user confirmation**
2. **NEVER use old log entries - ALWAYS verify timestamps are recent (within 2-3 minutes)**
3. **NEVER skip clearing logs - always start fresh**
4. **NEVER test MainActivity directly - always go through InvisibleNIP55Handler**
5. **NEVER use URI encoding for JSON - use Intent extras**
6. **ALWAYS check current time with `date "+%H:%M"` before checking logs**
7. **TRUST USER OBSERVATIONS - if they see prompts working, investigate log visibility issues**

## Common Mistakes to Avoid

- ❌ Using `nostrsigner://` (wrong - has double slashes)
- ❌ **OLD MISTAKE**: Using `--es event` for sign_event (JSON must be URL-encoded in URI)
- ❌ **OLD MISTAKE**: Not URL-encoding JSON for sign_event
- ❌ Targeting MainActivity directly
- ❌ Not clearing logs before testing
- ❌ Looking at old log entries (CHECK TIMESTAMPS!)
- ❌ Declaring success without evidence
- ❌ Ignoring user observations when they see UI working
- ❌ Using `adb logcat -d` without time verification
- ❌ Not checking current time before looking at logs

## JSON Format Examples

**Correct for sign_event (URL-encoded in URI):**
```bash
-d "nostrsigner:%7B%22content%22%3A%20%22nostr%20test%22%2C%20%22created_at%22%3A%201727472000%2C%20%22kind%22%3A%201%2C%20%22pubkey%22%3A%20%220000000000000000000000000000000000000000000000000000000000000000%22%2C%20%22tags%22%3A%20%5B%5D%7D"
```

**Wrong (old format with Intent extras):**
```bash
--es event '{"content":"Hello","kind":1,"tags":[],"created_at":1735327200}'  # WRONG for sign_event
```

## Troubleshooting Checklist

If test fails:
1. ✅ Did I clear logs first?
2. ✅ Am I using InvisibleNIP55Handler?
3. ✅ Is my JSON properly formatted?
4. ✅ Am I checking the right test ID in logs?
5. ✅ Did I check current time with `date "+%H:%M"` first?
6. ✅ Are the log timestamps within the last 2-3 minutes?
7. ✅ If user says they saw a prompt, am I trusting their observation?
8. ✅ Am I using `tail -10` to get the most recent log entries?

## Key Lessons Learned

**Why I keep regressing to old timestamps:**
1. Using `adb logcat -d` shows ALL historical logs
2. I filter by content but not by time
3. I find old matching entries instead of recent ones
4. I don't verify timestamp recency before concluding

**Solutions implemented:**
1. Always check current time first: `date "+%H:%M"`
2. Use `tail -10` to get most recent entries
3. Verify timestamps are within last 2-3 minutes
4. Trust user observations when they see UI working
5. Investigate log visibility issues instead of declaring failure

**REFERENCE THIS GUIDE FOR EVERY TEST - NO EXCEPTIONS**
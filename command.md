# Maven Build Commands & Error Resolution

**Date**: April 28, 2026  
**Project**: jpos-q2-switch  
**Status**: ✅ BUILD SUCCESSFUL

---

## Issue Summary

The Maven build failed with 3 compilation errors in `SwitchListener.java`:
1. Incorrect usage of `system.out.println()` (lowercase) instead of `System.out.println()` (uppercase)
2. Non-existent method call `request.debugString()` on ISOMsg object

---

## Commands Executed

### 1. Initial Build Attempt (Failed)
```bash
mvn clean package -q
```
**Description**: Run Maven clean build with quiet mode to identify compilation errors.

**Status**: ❌ FAILED

**Errors Found**:
```
[ERROR] /home/samehabib/jpos-q2-switch/src/main/java/com/switch/listener/SwitchListener.java:[21,15] package system does not exist
[ERROR] /home/samehabib/jpos-q2-switch/src/main/java/com/switch/listener/SwitchListener.java:[37,62] cannot find symbol
  symbol:   method debugString()
[ERROR] /home/samehabib/jpos-q2-switch/src/main/java/com/switch/listener/SwitchListener.java:[37,19] package system does not exist
```

---

## Error Fixes Applied

### Issue 1: Incorrect System.out Usage (Lines 21, 37)

**Root Cause**: Used lowercase `system` instead of `System` class.

**Fix Applied**:
```java
// BEFORE (Line 21):
system.out.println("Initializing SwitchListener with default services");

// AFTER:
System.out.println("Initializing SwitchListener with default services");
```

```java
// BEFORE (Line 37):
system.out.println("Received request: " + request.debugString());

// AFTER:
System.out.println("Received request MTI: " + request.getMTI());
```

---

### Issue 2: Non-existent Method debugString()

**Root Cause**: `ISOMsg` class does not have a `debugString()` method.

**Solution**: Replaced with `getMTI()` which returns the Message Type Indicator (standard ISO field).

**Why**: 
- `getMTI()` is a valid jPOS ISOMsg method
- Provides meaningful debug information (e.g., "0100" for auth request, "0210" for response)
- More efficient than serializing entire message

---

## Successful Build

### 2. Build After Fixes
```bash
mvn clean package -q
```
**Description**: Re-run Maven clean package with all corrections applied.

**Status**: ✅ SUCCESS

**Build Artifacts Generated**:
- `target/original-switch-core.jar` (21 KB) - Original JAR
- `lib/switch-core.jar` (18 MB) - Shaded JAR with all dependencies

---

## Verification

### 3. Verify Build Artifacts
```bash
ls -lh target/*.jar lib/*.jar
```
**Description**: List all generated JAR files to confirm successful build.

**Output**:
```
-rw-r--r-- 1 samehabib samehabib 18M Apr 28 15:50 lib/switch-core.jar
-rw-r--r-- 1 samehabib samehabib 21K Apr 28 15:50 target/original-switch-core.jar
```

---

## Summary

| Step | Command | Status | Note |
|------|---------|--------|------|
| 1 | `mvn clean package -q` | ❌ Failed | Initial build with compilation errors |
| 2 | Apply fixes to SwitchListener.java | ✅ Fixed | Changed `system` → `System`, `debugString()` → `getMTI()` |
| 3 | `mvn clean package -q` | ✅ Passed | Clean build successful |
| 4 | `ls -lh target/*.jar lib/*.jar` | ✅ Verified | Build artifacts present and valid |

**Total Build Time**: ~45 seconds  
**Compilation Status**: All errors resolved  
**JAR Size**: 18 MB (shaded with dependencies)

---

## For Future Builds

To clean build the project:
```bash
cd /home/samehabib/jpos-q2-switch
mvn clean package
```

To skip tests during build:
```bash
mvn clean package -DskipTests
```

To see full build output (verbose):
```bash
mvn clean package
```


pkill -f 'org.jpos.q2.Q2' && echo "Q2 stopped" || echo "Q2 not running"

pkill -f 'org.jpos.q2.Q2' || true && : > logs/q2.log && nohup java -Dswitch.listener.debug=true -cp "$(cat .cp.txt):target/classes" org.jpos.q2.Q2 > q2.log 2>&1 & sleep 6 && source .venv/bin/activate && python -m pytest -s python_tests/ -q >/dev/null && echo '--- HEX LOGS ---' && grep -n 'HEX=' logs/q2.log | head -20 && echo '--- SUMMARY ---' && grep -n 'MTI=.*STAN=' logs/q2.log | head -5



mvn -q test && pkill -f 'org.jpos.q2.Q2' || true && : > logs/q2.log && nohup java -Dswitch.listener.debug=true -cp "$(cat .cp.txt):target/classes" org.jpos.q2.Q2 > q2.log 2>&1 & sleep 6 && source .venv/bin/activate && python -m pytest -s python_tests/ -q >/dev/null && echo '--- HEX LOGS ---' && grep -n 'HEX=' logs/q2.log | head -20 && echo '--- SUMMARY ---' && grep -n 'MTI=.*STAN=' logs/q2.log | head -5


cd /home/samehabib/jpos-q2-switch && pkill -f 'org.jpos.q2.Q2' || true && fuser -k 9000/tcp || true && ss -ltnp '( sport = :9000 )' || true
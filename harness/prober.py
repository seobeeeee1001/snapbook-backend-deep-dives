#!/usr/bin/env python3
"""HTTP 가용성 프로버: 50ms 간격으로 /actuator/health를 찔러 epochMs|result 기록."""
import sys, time, urllib.request, urllib.error, socket

out_path, duration_sec = sys.argv[1], float(sys.argv[2])
deadline = time.time() + duration_sec
with open(out_path, 'w', buffering=1) as f:
    while time.time() < deadline:
        t = int(time.time() * 1000)
        try:
            r = urllib.request.urlopen('http://localhost:8080/actuator/health', timeout=1.0)
            f.write(f"{t}|{r.status}\n")
        except urllib.error.HTTPError as e:
            f.write(f"{t}|{e.code}\n")
        except (urllib.error.URLError, socket.timeout, ConnectionError, OSError) as e:
            reason = getattr(e, 'reason', e)
            f.write(f"{t}|ERR:{type(reason).__name__ if not isinstance(reason, str) else reason}\n")
        time.sleep(0.05)

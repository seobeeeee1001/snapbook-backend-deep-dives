#!/usr/bin/env python3
"""02번 사례 측정 로그 집계.

README와 문서의 모든 수치는 이 스크립트로 원본 로그에서 계산한 값이다.
    python3 aggregate.py before
    python3 aggregate.py after
"""
import re
import sys
from collections import Counter
from pathlib import Path

phase = sys.argv[1] if len(sys.argv) > 1 else 'before'
here = Path(__file__).parent
tkill = int((here / f'tkill_{phase}.txt').read_text().strip())


def rel(t):
    return (t - tkill) / 1000


# ---- HTTP 가용성 프로버: 다운타임 창 ----
downs = first_down = recovered = None
downs = 0
for line in (here / f'probe_{phase}.log').read_text().splitlines():
    t, result = line.split('|', 1)
    t = int(t)
    if t < tkill - 1000:
        continue
    if result != '200':
        downs += 1
        if first_down is None:
            first_down = t
        recovered = None
    elif first_down is not None and recovered is None:
        recovered = t

print(f"== {phase.upper()} ==")
print(f"다운타임 창      : {(recovered - first_down) / 1000:.2f}s "
      f"(T+{rel(first_down):.2f}s ~ T+{rel(recovered):.2f}s, 실패 프로브 {downs}건)")

# ---- 클라이언트 하니스 이벤트 ----
events = []
for line in (here / f'storm_{phase}.log').read_text().splitlines():
    m = re.search(r'EVT\|(\d+)\|([^|]+)\|([^|]+)\|(.*)', line)
    if m:
        events.append((int(m.group(1)), m.group(2), m.group(3), m.group(4)))


def of(kind):
    return [e for e in events if e[2] == kind]


disc, fails, recon = of('DISCONNECTED'), of('ATTEMPT_FAIL'), of('RECONNECT_OK')
refetch = [e for e in events if e[2].startswith('REFETCH')]
closes = of('CLOSE_CODE')

if disc:
    print(f"절단             : {len(disc)}명, "
          f"T+{min(rel(e[0]) for e in disc):.2f}~{max(rel(e[0]) for e in disc):.2f}s")
if closes:
    print(f"종료 신호        : {closes[0][3]}")
print(f"재시도 폭풍      : {len(fails)}회 (다운타임 중 실패한 접속 시도)")

if recon:
    hist = Counter(int(rel(e[0])) for e in recon)
    print(f"재접속           : {len(recon)}건, "
          f"T+{min(rel(e[0]) for e in recon):.2f}~{max(rel(e[0]) for e in recon):.2f}s")
    print(f"초당 재접속      : {dict(sorted(hist.items()))}")
    print(f"피크 동시 재접속 : {max(hist.values())}명/초")

lat = sorted(int(e[3].replace('ms', '')) for e in refetch if e[2] == 'REFETCH_OK')
if lat:
    print(f"전체 재조회      : n={len(lat)} p50={lat[len(lat) // 2]}ms max={lat[-1]}ms")

#!/usr/bin/env python3
from pathlib import Path
p = Path('app/src/main/assets/web/index.html')
s = p.read_text(encoding='utf-8')
old='0.1.12.4'
if old not in s:
    raise SystemExit('Очікуваний номер 0.1.12.4 не знайдено. Нічого не змінено.')
p.write_text(s.replace(old,'0.1.13'), encoding='utf-8')
print('Номер інтерфейсу оновлено до 0.1.13')

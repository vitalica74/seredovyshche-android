#!/usr/bin/env python3
from pathlib import Path
p=Path('app/src/main/assets/web/index.html')
s=p.read_text()
if '0.1.12.2' not in s:
    raise SystemExit('Версія 0.1.12.2 в index.html не знайдена: файл не змінено')
p.write_text(s.replace('0.1.12.2','0.1.12.3'))
print('Оновлено номер версії в інтерфейсі')

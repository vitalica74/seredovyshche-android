#!/usr/bin/env python3
from pathlib import Path
p = Path('app/src/main/assets/web/index.html')
s = p.read_text()
if '0.1.12.3' not in s:
    raise SystemExit('Не знайдено 0.1.12.3 у файлі інтерфейсу; змін не внесено')
p.write_text(s.replace('0.1.12.3', '0.1.12.4'))
print('Інтерфейс оновлено до 0.1.12.4')

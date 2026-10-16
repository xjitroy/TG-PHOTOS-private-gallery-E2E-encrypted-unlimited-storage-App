#!/usr/bin/env python3
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
base = {n.attrib['name']: n.text for n in ET.parse(root / 'app/src/main/res/values/strings.xml').getroot()}
bn = {n.attrib['name']: n.text for n in ET.parse(root / 'app/src/main/res/values-bn/strings.xml').getroot()}
assert base.keys() == bn.keys(), 'Translation keys differ'
for key in base:
    assert bn[key], f'Empty Bengali resource: {key}'
    assert re.findall(r'%\d+\$\w', base[key]) == re.findall(r'%\d+\$\w', bn[key]), f'Format mismatch: {key}'
for kotlin in (root / 'app/src/main/java').rglob('*.kt'):
    for key in re.findall(r'R\.string\.(\w+)', kotlin.read_text()):
        assert key in base, f'Undefined string: {key}'
for xml in (root / 'app/src/main').rglob('*.xml'): ET.parse(xml)
print(f'PASS: {len(base)} English/Bengali string pairs, references and Android XML syntax')

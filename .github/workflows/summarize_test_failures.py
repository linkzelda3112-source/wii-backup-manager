#!/usr/bin/env python3
"""Extrae los fallos de las pruebas JUnit a texto plano, para subirlos al repo cuando el CI
falla (el almacenamiento de logs de GitHub Actions no siempre es accesible)."""
import glob
import os
import xml.etree.ElementTree as ET

sha = os.environ.get("GITHUB_SHA", "?")
print(f"# Fallo de pruebas - commit {sha}\n")

files = sorted(glob.glob("app/build/test-results/test/*.xml"))
if not files:
    print("(no se encontraron archivos de resultados de pruebas; puede que la compilación "
          "haya fallado antes de llegar a correrlas)")

for path in files:
    try:
        root = ET.parse(path).getroot()
    except Exception as e:
        print(f"## {path}\nNo se pudo leer: {e}\n")
        continue
    for tc in root.findall("testcase"):
        for tag in ("failure", "error"):
            el = tc.find(tag)
            if el is not None:
                print(f"## {tc.get('classname')}.{tc.get('name')}")
                print(f"{el.get('message')}\n")
                text = (el.text or "")[:4000]
                print("```")
                print(text)
                print("```\n")

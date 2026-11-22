"""Gera sources.cmake a partir do Makefile do doomgeneric (sem os arquivos de plataforma)."""
import re
import sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read().replace("\\\n", " ")
match = re.search(r"^SRC_DOOM\s*=\s*(.*)$", text, re.M)
if not match:
    sys.exit("SRC_DOOM não encontrado no Makefile")
objs = [o for o in match.group(1).split() if o.endswith(".o")]
srcs = [o[:-2] + ".c" for o in objs if not o.startswith("doomgeneric_")]
print("set(DG_SOURCES " + " ".join(srcs) + ")")

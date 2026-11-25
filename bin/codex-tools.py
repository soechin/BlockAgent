# 從 `codex debug prompt-input` 的輸出列出內建工具名稱(含 exec 裡的巢狀工具)，一行一個、排序。
import json, re, sys
items = json.load(sys.stdin)
names = set()
def walk(t, prefix=""):
    if t.get("type") == "namespace":
        for x in t.get("tools", []):
            walk(x, prefix + t["name"] + ".")
        return
    name = t.get("name", "<" + t.get("type", "?") + ">")
    names.add(prefix + name)
    if name == "exec":
        for n in re.findall(r"^### `([^`]+)`", t.get("description", ""), re.M):
            names.add("exec." + n)
for it in items:
    if it.get("type") == "additional_tools":
        for t in it["tools"]:
            walk(t)
for n in sorted(names):
    if "mcp__blockagent" in n or n.split(".")[-1].startswith("blockagent"):
        continue
    print(n)

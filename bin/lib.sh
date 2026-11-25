#!/bin/sh
# 兩個啟動腳本共用的函式(docs/SPEC.md S11.3、S11.4)。
set -eu

BA_BIN="$(cd "$(dirname "$0")" && pwd)"
# 開發版遊戲(./gradlew runClient)把 runtime.json 寫在專案的 build/blockagent-home(build.gradle)
DEV_HOME="$(cd "$BA_BIN/.." && pwd)/build/blockagent-home"
BA_HOME="${BLOCKAGENT_HOME:-$HOME/.blockagent}"
RUNTIME="$BA_HOME/runtime.json"

die() { printf '%s\n' "blockagent: $*" >&2; exit 1; }

# 「怎麼玩」提示詞(需求 6.2)：兩個 CLI 共用同一份，每一回合都會帶上。
PROMPT_FILE="$BA_BIN/../prompts/how-to-play.md"
[ -f "$PROMPT_FILE" ] || die "找不到提示詞檔案：$PROMPT_FILE"
export PROMPT_FILE

# 開場白(S11.8)：互動模式啟動時自動送出的第一句話，使用者不用先打字。之後在視窗裡說的話照樣優先。
KICKOFF_FILE="$BA_BIN/../prompts/kickoff.md"
[ -f "$KICKOFF_FILE" ] || die "找不到開場白檔案：$KICKOFF_FILE"
KICKOFF="$(cat "$KICKOFF_FILE")"
[ -n "$KICKOFF" ] || die "開場白檔案是空的：$KICKOFF_FILE"

json_get() { # json_get <file> <key>
	python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$1" "$2"
}

# runtime.json 存在、而且寫它的遊戲還在執行(遊戲當掉時檔案會留著)
runtime_alive() { # runtime_alive <home>
	[ -f "$1/runtime.json" ] || return 1
	pid="$(json_get "$1/runtime.json" pid 2>/dev/null)" || return 1
	kill -0 "$pid" 2>/dev/null
}

# 沒指定 BLOCKAGENT_HOME 時，正式版(~/.blockagent)和開發版(build/blockagent-home)哪個在跑就用哪個
pick_home() {
	[ -n "${BLOCKAGENT_HOME:-}" ] && { [ -f "$RUNTIME" ]; return; }
	for h in "$HOME/.blockagent" "$DEV_HOME"; do
		if runtime_alive "$h"; then
			BA_HOME="$h"
			RUNTIME="$h/runtime.json"
			[ "$h" = "$DEV_HOME" ] && printf '%s\n' "(接上開發版遊戲：${h})" >&2
			return 0
		fi
	done
	return 1
}

wait_runtime() {
	if ! pick_home; then
		printf '%s\n' "請先開啟 Minecraft(裝好 BlockAgent 模組)，等待 MCP 伺服器啟動…" >&2
		i=0
		until pick_home; do
			i=$((i + 1))
			[ "$i" -gt 600 ] && die "等了 10 分鐘還找不到執行中的遊戲($HOME/.blockagent 或 $DEV_HOME 的 runtime.json)"
			sleep 1
		done
	fi
	# Stop hook 也要找到同一個 runtime.json
	BLOCKAGENT_HOME="$BA_HOME"
	export BLOCKAGENT_HOME
	BLOCKAGENT_PORT="$(json_get "$RUNTIME" port)"
	BLOCKAGENT_TOKEN="$(json_get "$RUNTIME" token)"
	export BLOCKAGENT_PORT BLOCKAGENT_TOKEN
}

# 規劃服務專用的 token(S11.7)；舊版模組沒有就是空的
plan_token() {
	python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get("plan_token",""))' "$RUNTIME"
}

# 版本檢查(S11.5)：和測試過的版本不同時警告
check_version() { # check_version <cli> <actual>
	expected="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2],""))' "$BA_BIN/cli-versions.json" "$1")"
	case "$2" in
		*"$expected"*) ;;
		*) printf '%s\n' "警告：$1 的版本是「$2」，測試過的是 ${expected}。請先跑一次 ./gradlew cliTest(T8、T9)確認權限限制仍然有效。" >&2 ;;
	esac
}

prepare_workdir() {
	WORKDIR="$BA_HOME/workdir"
	mkdir -p "$WORKDIR"
	chmod 700 "$BA_HOME" "$WORKDIR"
}

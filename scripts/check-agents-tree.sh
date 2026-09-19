#!/usr/bin/env bash
# check-agents-tree.sh — AGENTS.md §5 文件树完整性守护（F-1，审查发现 P2）。
# 规则：遍历 app/src/main 下全部 Kotlin 源文件（含子目录，如 database/），
# 凡未在 AGENTS.md §5 文件树代码块中出现的文件名即报错并 exit 1。
# 例外（如需豁免请在此显式列出并注明理由）：
#   无——当前要求 main 源集 100% 进树。新文件未写进 AGENTS §5 属于合并错误。
set -euo pipefail

# 允许从任意目录调用：一律回到仓库根
if ! ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"; then
  ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fi
cd "$ROOT"

AGENTS="AGENTS.md"
if [[ ! -f "$AGENTS" ]]; then
  echo "找不到 AGENTS.md（当前目录：$(pwd)）" >&2
  exit 1
fi

# 抽取 "## 5. 文件树" 一节里的围栏代码块（第一个 ```...``` 对）
TREE="$(awk '
  /^## 5[\.、 ]*文件树/ { in_section = 1; next }
  in_section && /^```/ { fence++; if (fence == 2) exit; next }
  in_section && fence == 1 { print }
' "$AGENTS")"

if [[ -z "$TREE" ]]; then
  echo "AGENTS.md 中未找到 §5 文件树代码块（节标题格式可能被改动）" >&2
  exit 1
fi

missing=()
# 遍历 main 源集全部 .kt；以文件名为键匹配树条目（文件树按同类分组，键不重复）
while IFS= read -r path; do
  name="${path##*/}"
  # 文件名按独立 token 匹配，避免 Topics.kt 被 CloudTopics.kt 的子串命中（F-7）。
  name_regex="$(printf '%s' "$name" | sed 's/[][\.^$*+?(){}|]/\\&/g')"
  if ! grep -qE "(^|[[:space:]])${name_regex}([[:space:]]|$)" <<<"$TREE"; then
    missing+=("$path")
  fi
done < <(find app/src/main -name '*.kt' | LC_ALL=C sort)

if (( ${#missing[@]} > 0 )); then
  echo "AGENTS.md §5 文件树缺少以下 ${#missing[@]} 个 main Kotlin 源文件条目：" >&2
  printf '  %s\n' "${missing[@]}" >&2
  echo "请把条目补进 AGENTS.md §5 文件树；确需豁免的例外须在 scripts/check-agents-tree.sh 顶部登记并注明理由。" >&2
  exit 1
fi

count="$(find app/src/main -name '*.kt' | wc -l)"
echo "OK：AGENTS.md §5 文件树覆盖全部 ${count} 个 main Kotlin 源文件。"

#!/usr/bin/env bash
# 准备编译所需的本地依赖（从你正在运行的 Paper / Leaves 26.3 服务端里取）
#
# 用法:
#   ./prepare-libs.sh /path/to/leaves-26.3.jar [/path/to/server-root]
#
# 说明:
#   - leaves-server-26.3.jar  : 提供 NMS 类（CustomPayload / Identifier），仅供编译
#   - netty/*.jar             : 与服务端完全一致的 Netty 版本（4.2.16.Final）
#   libs/ysm-core-pure.jar 已内置，无需准备。
set -euo pipefail

SERVER_JAR="${1:-}"
SERVER_ROOT="${2:-}"

if [[ -z "$SERVER_JAR" ]]; then
  echo "用法: $0 <leaves-26.3.jar 路径> [服务端根目录]" >&2
  echo "提示: 服务端根目录默认取 jar 所在目录（libraries/ 就在旁边）" >&2
  exit 1
fi

if [[ ! -f "$SERVER_JAR" ]]; then
  echo "找不到服务端 jar: $SERVER_JAR" >&2
  exit 1
fi

if [[ -z "$SERVER_ROOT" ]]; then
  SERVER_ROOT="$(cd "$(dirname "$SERVER_JAR")" && pwd)"
fi

cd "$(dirname "$0")"
mkdir -p libs/netty

echo "==> 复制服务端 jar（仅编译期使用，不会打进插件）"
cp -f "$SERVER_JAR" libs/leaves-server-26.3.jar

NETTY_DIR="$SERVER_ROOT/libraries/io/netty"
if [[ ! -d "$NETTY_DIR" ]]; then
  echo "警告: 未找到 $NETTY_DIR —— 请手动把服务端 libraries/io/netty 下的" >&2
  echo "      netty-buffer / netty-common / netty-transport / netty-codec-base /" >&2
  echo "      netty-handler / netty-resolver / netty-codec-compression 复制到 libs/netty/" >&2
else
  echo "==> 复制 Netty 依赖"
  for m in netty-buffer netty-common netty-transport netty-codec-base \
           netty-handler netty-resolver netty-codec-compression; do
    cp -f "$NETTY_DIR/$m"/*/*.jar libs/netty/ 2>/dev/null || \
      echo "    跳过（未找到）: $m"
  done
fi

echo
echo "完成。现在可以构建："
echo "  JAVA_HOME=<JDK25> ./gradlew build    # 或 gradle build"
echo "产物: build/libs/YSMSync-*.jar"

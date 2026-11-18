#!/bin/bash
# tools/deanonymize.sh - 把占位符换回现场值（真值只在 ~/.config/pb_hosts.env 与
# ~/.config/pb_plug.env，不进 git；对照表见 docs/DESENSITIZATION.md）。
#
# 用法：
#   tools/deanonymize.sh phone-behavior/standby/pb-wake.sh > /tmp/run.sh && bash /tmp/run.sh
#   tools/deanonymize.sh panel/layout/agent-config-snapshot-20261118.json > /tmp/config.json
#   tools/deanonymize.sh --check     # 只看映射文件与变量在不在（不输出值）
#
# 规则顺序有讲究：带引号的占位（JSON 数值位写法）必须先换，否则会被裸规则吞成 "<lat>"。
# <REDACTED lan.token> / <REDACTED lan.webhookToken> 不还原——它们是新装 agent 自己
# 生成的运行时凭据，见 docs/DESENSITIZATION.md。
set -u
ENVF="${DESENS_ENV:-$HOME/.config/pb_hosts.env}"
PLUGF="${PB_PLUG_ENV:-$HOME/.config/pb_plug.env}"
if [ "${1:-}" = "--check" ]; then
  test -r "$ENVF" && { echo "mapping ok: $ENVF"; } || { echo "missing $ENVF"; exit 2; }
  test -r "$PLUGF" && { echo "plug env ok: $PLUGF"; } || { echo "note: $PLUGF 不存在（要用插座还原功能请补）"; }
  exit 0
fi
test -r "$ENVF" || { echo "need $ENVF（见 docs/DESENSITIZATION.md）"; exit 2; }
set -a; . "$ENVF"; set +a
test -r "$PLUGF" && { set -a; . "$PLUGF"; set +a; }
for v in PB_PLUG_IP PB_PHONE_IP PB_SERVER_IP PB_GATEWAY_IP PB_NAS_IP PB_PHONE_SERIAL PB_HOSTNAME PB_SSID PB_LAT PB_LON PB_USER; do
  [ -n "${!v:-}" ] || { echo "missing $v in $ENVF"; exit 3; }
done
[ -n "${PB_NAS_USER:-}" ]   || { echo "missing PB_NAS_USER in $ENVF（NAS 家目录用户名）"; exit 3; }
[ -n "${PB_DATA_DISK:-}" ]  || { echo "missing PB_DATA_DISK in $ENVF（数据盘挂载点里那个盘名，即 /mnt/<data-disk> 的 <data-disk> 真值）"; exit 3; }
[ -n "${PB_PLUG_TOKEN:-}" ] || { echo "missing PB_PLUG_TOKEN（$PLUGF）"; exit 3; }
sed -e "s|\"<lat>\"|$PB_LAT|g" -e "s|\"<lon>\"|$PB_LON|g" \
    -e "s|<plug-ip>|$PB_PLUG_IP|g" -e "s|<plug-token>|$PB_PLUG_TOKEN|g" \
    -e "s|<phone-ip>|$PB_PHONE_IP|g" -e "s|<server-ip>|$PB_SERVER_IP|g" \
    -e "s|<nas-ip>|$PB_NAS_IP|g" -e "s|<gateway>|$PB_GATEWAY_IP|g" \
    -e "s|<serial>|$PB_PHONE_SERIAL|g" -e "s|<host>|$PB_HOSTNAME|g" \
    -e "s|<ssid>|$PB_SSID|g" -e "s|<lat>|$PB_LAT|g" -e "s|<lon>|$PB_LON|g" \
    -e "s|<nas-user>|$PB_NAS_USER|g" -e "s|<user>|$PB_USER|g" \
    -e "s|<data-disk>|$PB_DATA_DISK|g" -- "${1:--}"

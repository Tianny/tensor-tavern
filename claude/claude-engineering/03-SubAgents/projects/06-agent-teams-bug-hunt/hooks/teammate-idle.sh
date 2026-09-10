#!/bin/bash
# TeammateIdle Hook — 当 Teammate 即将空闲时触发
#
# 用途：确保 Teammate 在空闲前完成了必要的工作
# 退出码 0：允许 Teammate 空闲
# 退出码 2：发送反馈让 Teammate 继续工作
#
# 环境变量：
#   $CLAUDE_TEAMMATE_NAME — Teammate 名称
#   $CLAUDE_TEAM_NAME     — Team 名称
#
# 使用方法：
# 在 settings.json 中配置：
# {
#   "hooks": {
#     "TeammateIdle": [{
#       "type": "command",
#       "command": "./hooks/teammate-idle.sh"
#     }]
#   }
# }

TEAMMATE_NAME="${CLAUDE_TEAMMATE_NAME:-unknown}"
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')

echo "[$TIMESTAMP] Teammate '$TEAMMATE_NAME' is going idle"

# 检查该 Teammate 是否已经向其他 Teammates 分享了发现
# 这里是一个简化的检查——实际中你可以检查任务状态文件
FINDINGS_FILE="./findings-${TEAMMATE_NAME}.md"

if [ ! -f "$FINDINGS_FILE" ]; then
  # Teammate 还没有写调查发现就要空闲了
  # 退出码 2 会发送反馈让它继续工作
  echo "你还没有记录你的调查发现。请：1) 总结你的发现 2) 发消息给其他 teammates 分享关键发现 3) 检查其他 teammates 的发现是否与你的有关联"
  exit 2
fi

# 允许空闲
exit 0

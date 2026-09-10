#!/bin/bash
# TaskCompleted Hook — 当任务即将被标记为完成时触发
#
# 用途：确保任务满足质量要求才能被标记为完成
# 退出码 0：允许标记为完成
# 退出码 2：阻止完成并发送反馈
#
# 环境变量：
#   $CLAUDE_TASK_ID          — 任务 ID
#   $CLAUDE_TASK_DESCRIPTION — 任务描述
#   $CLAUDE_TEAMMATE_NAME    — 完成任务的 Teammate 名称
#
# 使用方法：
# 在 settings.json 中配置：
# {
#   "hooks": {
#     "TaskCompleted": [{
#       "type": "command",
#       "command": "./hooks/task-completed.sh"
#     }]
#   }
# }

TASK_ID="${CLAUDE_TASK_ID:-unknown}"
TASK_DESC="${CLAUDE_TASK_DESCRIPTION:-unknown}"
TEAMMATE="${CLAUDE_TEAMMATE_NAME:-unknown}"
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')

echo "[$TIMESTAMP] Teammate '$TEAMMATE' completing task '$TASK_DESC'"

# 质量门禁检查：
# 1. 调查类任务必须包含具体的文件和行号引用
# 2. 调查类任务必须尝试与其他 Teammates 的发现建立关联

# 这里可以根据实际需求添加更复杂的检查逻辑
# 例如：检查是否有 message 发送给其他 teammates

# 简化示例：始终允许完成（实际中可以加入检查逻辑）
echo "Task quality check passed"
exit 0

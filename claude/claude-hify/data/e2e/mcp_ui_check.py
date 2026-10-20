#!/usr/bin/env python3
"""MCP 工具接入的 UI 验证（playwright）。

1. /mcp 列表与工具查看弹窗
2. /agent 编辑弹窗的 MCP 工具绑定回显
3. /chat 发送需要工具的问题 → 工具卡片渲染 + 最终回答
"""
import sys

from playwright.sync_api import sync_playwright

BASE = "http://localhost:5173"
results = []


def check(name, cond):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name, flush=True)


with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width": 1440, "height": 900})

    # ---- 1. MCP 管理页 ----
    page.goto(BASE + "/mcp")
    page.wait_for_selector(".el-table__row", timeout=10000)
    check("MCP 列表有「本地工具」", "本地工具" in page.inner_text("body"))
    page.get_by_role("button", name="工具").first.click()
    page.wait_for_selector(".el-dialog:visible .el-table__row", timeout=10000)
    tools_text = page.inner_text(".el-dialog:visible")
    check("工具弹窗列出 get_current_time / calculate",
          "get_current_time" in tools_text and "calculate" in tools_text)
    page.keyboard.press("Escape")
    page.wait_for_timeout(500)

    # ---- 2. Agent 编辑弹窗的绑定回显 ----
    page.goto(BASE + "/agent")
    page.wait_for_selector(".el-table__row", timeout=10000)
    row = page.locator(".el-table__row", has_text="工具助手").first
    row.get_by_role("button", name="编辑").click()
    page.wait_for_selector(".el-dialog:visible .tool-binding-row", timeout=10000)
    binding_text = page.inner_text(".el-dialog:visible .tool-binding-row")
    check("Agent 绑定行回显「本地工具」", "本地工具" in binding_text)
    page.keyboard.press("Escape")
    page.wait_for_timeout(500)

    # ---- 3. 对话页：工具卡片 + 最终回答 ----
    page.goto(BASE + "/chat")
    page.wait_for_timeout(800)
    page.locator(".side-head .el-select").click()
    page.wait_for_timeout(400)
    page.get_by_role("option", name="工具助手").click()
    page.wait_for_timeout(600)
    page.get_by_role("button", name="新建会话").click()
    page.wait_for_timeout(800)
    page.get_by_placeholder("输入消息，Enter 发送，Shift+Enter 换行").fill(
        "现在几点了？顺便帮我算一下 99 乘 101")
    page.get_by_role("button", name="发送").click()

    page.wait_for_selector(".tool-card", timeout=30000)
    check("工具卡片渲染", True)
    # 等流真正结束：done 后气泡底部出现「N tokens · Xms」元信息行
    # （不能等光标消失：reasoning/工具阶段光标本来就不显示，会提前误判）
    page.wait_for_selector("text=/tokens · /", timeout=90000)
    card_text = page.inner_text(".tool-card") if page.query_selector(".tool-card") else ""
    check("卡片含工具名", "get_current_time" in card_text or "calculate" in card_text)
    check("卡片含执行结果（时间/计算）", "时区" in card_text or "9999" in card_text)
    bubbles = page.locator(".bubble-md").all_inner_texts()
    answer = bubbles[-1] if bubbles else ""
    check("最终回答含计算结果 9999", "9999" in answer)
    page.screenshot(path="/tmp/hify_mcp_chat.png")
    browser.close()

fails = [n for n, ok in results if not ok]
print("ALL PASS" if not fails else "FAILED: " + ", ".join(fails))
sys.exit(1 if fails else 0)

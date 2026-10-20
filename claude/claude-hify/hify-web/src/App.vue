<script setup lang="ts">
import { ref } from 'vue'
import { Box, ChatDotRound, Collection, Expand, Fold, SetUp, Setting, User } from '@element-plus/icons-vue'

const collapsed = ref(false)
const version = __APP_VERSION__
</script>

<template>
  <el-container class="app">
    <el-aside
      :width="collapsed ? '64px' : '220px'"
      class="app-aside hify-sidebar"
      :class="{ 'is-collapsed': collapsed }"
    >
      <div class="app-logo">
        <span class="app-logo-text">{{ collapsed ? 'H' : 'Hify' }}</span>
        <span v-show="!collapsed" class="app-logo-sub">AI AGENT PLATFORM</span>
      </div>
      <el-menu router :default-active="$route.path">
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon>
          <span v-show="!collapsed">对话</span>
        </el-menu-item>
        <el-menu-item index="/agent">
          <el-icon><User /></el-icon>
          <span v-show="!collapsed">Agent管理</span>
        </el-menu-item>
        <el-menu-item index="/provider">
          <el-icon><Setting /></el-icon>
          <span v-show="!collapsed">模型管理</span>
        </el-menu-item>
        <el-menu-item index="/knowledge">
          <el-icon><Collection /></el-icon>
          <span v-show="!collapsed">知识库</span>
        </el-menu-item>
        <el-menu-item index="/workflow">
          <el-icon><SetUp /></el-icon>
          <span v-show="!collapsed">工作流</span>
        </el-menu-item>
        <el-menu-item index="/mcp">
          <el-icon><Box /></el-icon>
          <span v-show="!collapsed">MCP 工具</span>
        </el-menu-item>
      </el-menu>
      <div class="app-aside-footer">
        <button
          class="aside-toggle"
          :title="collapsed ? '展开侧边栏' : '折叠侧边栏'"
          @click="collapsed = !collapsed"
        >
          <el-icon>
            <Expand v-if="collapsed" />
            <Fold v-else />
          </el-icon>
        </button>
        <span v-show="!collapsed" class="aside-version">v{{ version }}</span>
      </div>
    </el-aside>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.app {
  height: 100vh;
}

.app-aside {
  overflow: hidden;
  transition: width var(--hify-duration-base) var(--hify-ease-out);
}

/* 页面自己控制留白（.page padding），el-main 不再叠加默认 20px */
.app-main {
  padding: 0;
}

/* 顶部 Logo 区：主色渐变品牌名 + 小字副标 */
.app-logo {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--hify-space-4) var(--hify-space-5) var(--hify-space-3);
  white-space: nowrap;
}

.app-logo-text {
  font-size: var(--hify-text-2xl);
  font-weight: var(--hify-weight-bold);
  letter-spacing: 0.5px;
  background: linear-gradient(
    120deg,
    var(--hify-primary-300),
    var(--hify-primary-500) 55%,
    var(--hify-accent-400)
  );
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.app-logo-sub {
  font-size: var(--hify-text-xs);
  letter-spacing: 1.5px;
  color: var(--hify-sidebar-text-dim);
}

/* 底部：折叠按钮 + 版本号 */
.app-aside-footer {
  display: flex;
  align-items: center;
  gap: var(--hify-space-2);
  padding: var(--hify-space-3) var(--hify-space-4);
  border-top: 1px solid var(--hify-sidebar-border);
  white-space: nowrap;
}

.app-aside.is-collapsed .app-aside-footer {
  justify-content: center;
  padding-inline: 0;
}

.aside-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  border-radius: var(--hify-radius-md);
  color: var(--hify-sidebar-text);
  cursor: pointer;
  transition: var(--hify-transition-color);
}

.aside-toggle:hover {
  background: var(--hify-sidebar-bg-hover);
  color: var(--hify-sidebar-text-active);
}

.aside-version {
  margin-left: auto;
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-xs);
  color: var(--hify-sidebar-text-dim);
}
</style>

<style>
body {
  margin: 0;
  font-family: var(--hify-font-sans);
  background: var(--hify-bg-page);
  color: var(--hify-text-2);
}
</style>

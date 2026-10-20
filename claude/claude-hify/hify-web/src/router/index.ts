import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: () => import('@/views/chat/ChatView.vue') },
    { path: '/agent', name: 'agent', component: () => import('@/views/agent/AgentList.vue') },
    { path: '/provider', name: 'provider', component: () => import('@/views/provider/ProviderList.vue') },
    { path: '/knowledge', name: 'knowledge', component: () => import('@/views/knowledge/KnowledgeList.vue') },
    { path: '/workflow', name: 'workflow', component: () => import('@/views/workflow/WorkflowList.vue') },
    { path: '/mcp', name: 'mcp', component: () => import('@/views/mcp/McpList.vue') },
    { path: '/workflow/new', name: 'workflowCreate', component: () => import('@/views/workflow/WorkflowDetail.vue') },
    { path: '/workflow/:id', name: 'workflowDetail', component: () => import('@/views/workflow/WorkflowDetail.vue') },
    { path: '/knowledge/:id', name: 'knowledgeDetail', component: () => import('@/views/knowledge/KnowledgeDetail.vue') },
  ],
})

export default router

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { session, logout } from './api'
const route=useRoute(), router=useRouter()
function signout(){ logout(); router.push('/connect') }
</script>
<template><div class="shell"><header><router-link class="brand" to="/projects"><span class="brand-icon">A</span><span>Agile Agent <small>项目工作台</small></span></router-link><div v-if="session.key" class="header-user"><span>{{ session.user?.displayName || session.user?.username || '已连接' }}</span><button class="link" @click="signout">退出本地会话</button></div></header><div class="layout"><aside v-if="session.key"><p class="eyebrow">工作空间</p><router-link to="/projects">项目列表</router-link><template v-if="route.params.projectId"><p class="eyebrow">当前项目</p><router-link :to="`/projects/${route.params.projectId}/import`">会议导入</router-link><router-link :to="`/projects/${route.params.projectId}/tasks`">正式任务</router-link></template><div class="aside-note">从会议到行动<br>让每个决定都有下文。</div></aside><main><router-view /></main></div></div></template>

import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import Connect from './pages/Connect.vue'
import Projects from './pages/Projects.vue'
import ImportMeeting from './pages/ImportMeeting.vue'
import Draft from './pages/Draft.vue'
import Tasks from './pages/Tasks.vue'
import { session } from './api'
import './style.css'
const router = createRouter({history: createWebHashHistory(), routes:[{path:'/',redirect:'/projects'},{path:'/connect',component:Connect},{path:'/projects',component:Projects},{path:'/projects/:projectId/import',component:ImportMeeting},{path:'/projects/:projectId/drafts/:draftId',component:Draft},{path:'/projects/:projectId/tasks',component:Tasks}]})
router.beforeEach(to => to.path !== '/connect' && !session.key ? '/connect' : true)
createApp(App).use(router).mount('#app')

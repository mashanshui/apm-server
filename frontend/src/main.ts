import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { pinia } from './stores/pinia'
import { useAppStore } from './stores/apps'
import { useSessionStore } from './stores/session'
import './style.css'

const app = createApp(App)
app.use(pinia).use(router)

window.addEventListener('apm:auth-expired', () => {
  const session = useSessionStore(pinia)
  session.handleExpired()
  useAppStore(pinia).clear()
  if (router.currentRoute.value.name !== 'login') {
    void router.push({
      name: 'login',
      query: { redirect: router.currentRoute.value.fullPath, expired: '1' },
    })
  }
})

app.mount('#app')

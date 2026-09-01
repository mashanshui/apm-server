<script setup lang="ts">
import AppLayout from '../components/AppLayout.vue'
</script>

<template>
  <AppLayout>
    <section class="panel not-found-state">
      <p class="eyebrow">404 / NOT FOUND</p>
      <h1>页面不存在</h1>
      <p>这个地址没有对应的工作区页面。</p>
      <RouterLink class="button button-primary" :to="{ name: 'apps' }">返回我的应用</RouterLink>
    </section>
  </AppLayout>
</template>

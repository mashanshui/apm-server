<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import FilterBar from '../components/FilterBar.vue'
import IssueTable from '../components/IssueTable.vue'
import StatCard from '../components/StatCard.vue'
import StatusMessage from '../components/StatusMessage.vue'
import TrendChart from '../components/TrendChart.vue'
import { useCrashOverview } from '../composables/useCrashOverview'
import type { CrashFilterForm } from '../types/crash'
import { formatDateTime, metricValue, statusLabel, statusTone } from '../utils/format'
import { filtersToQuery, parseFilters } from '../utils/query'

const route = useRoute()
const router = useRouter()
const appId = computed(() => String(route.params.appId))
const filters = ref<CrashFilterForm>(parseFilters(route.query))
const {
  overview,
  trend,
  issues,
  nextCursor,
  loading,
  error,
  issueError,
  load,
  loadMoreIssues,
} = useCrashOverview(appId, filters)

watch(() => route.fullPath, () => {
  filters.value = parseFilters(route.query)
  void load()
}, { immediate: true })

const stats = computed(() => overview.value?.stats ?? null)
const currentStatus = computed(() => stats.value?.status)
const dataSourceLabel = computed(() => {
  if (overview.value?.dataSource === 'clickhouse') {
    return 'ClickHouse'
  }
  if (overview.value?.dataSource === 'memory') {
    return '内存联调'
  }
  return overview.value?.dataSource ?? '未知'
})
const overviewRange = computed(() => {
  if (!overview.value) {
    return ''
  }
  return `${formatDateTime(overview.value.from)} — ${formatDateTime(overview.value.to)}`
})

function applyFilters(nextFilters: CrashFilterForm) {
  void router.push({
    name: 'crash-overview',
    params: { appId: appId.value },
    query: filtersToQuery(nextFilters),
  })
}

function openIssue(issue: { fingerprint: string }) {
  void router.push({
    name: 'crash-issue-events',
    params: {
      appId: appId.value,
      fingerprint: issue.fingerprint,
    },
    query: filtersToQuery({ ...filters.value, fingerprint: issue.fingerprint }),
  })
}

function statusFootnote(): string {
  return stats.value ? statusLabel(stats.value.status) : '等待查询'
}
</script>

<template>
  <AppLayout :app-id="appId">
    <header class="page-header">
      <div>
        <p class="eyebrow">APP / {{ appId }}</p>
        <h1>JVM Crash</h1>
        <p class="subtitle">从整体趋势定位高频问题，再下钻到脱敏后的原始堆栈。</p>
      </div>
      <div class="page-header-badges">
        <span v-if="overview" class="badge badge-success">数据源 · {{ dataSourceLabel }}</span>
        <span v-if="overviewRange" class="badge">{{ overviewRange }}</span>
      </div>
    </header>

    <FilterBar :model-value="filters" :loading="loading" @submit="applyFilters" />

    <StatusMessage
      v-if="error"
      kind="error"
      :message="error"
      @retry="load"
    />

    <div class="stats-grid">
      <StatCard
        label="启动会话"
        :value="metricValue(stats?.startedSessions, currentStatus, 'number')"
        :footnote="statusFootnote()"
      />
      <StatCard
        label="Crash 事件"
        :value="metricValue(stats?.crashEvents, currentStatus, 'number')"
        :footnote="statusFootnote()"
      />
      <StatCard
        label="崩溃会话"
        :value="metricValue(stats?.crashedSessions, currentStatus, 'number')"
        :footnote="statusFootnote()"
        tone="danger"
      />
      <StatCard
        label="受影响设备"
        :value="metricValue(stats?.affectedDevices, currentStatus, 'number')"
        :footnote="statusFootnote()"
      />
      <StatCard
        label="每千会话崩溃率"
        :value="metricValue(stats?.crashRatePer1000Sessions, currentStatus, 'perThousand')"
        :footnote="stats?.crashRatePer1000Sessions === null ? '需要 app_start 分母' : '崩溃会话 / 启动会话'"
        tone="warning"
      />
      <StatCard
        label="无崩溃会话率"
        :value="metricValue(stats?.crashFreeSessionRate, currentStatus, 'percent')"
        :footnote="stats?.crashFreeSessionRate === null ? '需要 app_start 分母' : '1 − 崩溃会话占比'"
        tone="success"
      />
    </div>

    <div class="content-grid">
      <section class="panel">
        <div class="panel-header">
          <div>
            <h2>Crash 趋势</h2>
            <p>按{{ filters.interval === 'hour' ? '小时' : '天' }}统计唯一 Crash 事件</p>
          </div>
          <span v-if="trend" class="badge">{{ trend.points.length }} 个时间点</span>
        </div>
        <div v-if="loading" class="loading-state"><p>正在加载趋势…</p></div>
        <TrendChart
          v-else-if="trend && trend.points.length > 0"
          :points="trend.points"
          :interval="trend.interval"
        />
        <div v-else class="empty-state"><p>当前范围没有趋势数据</p></div>
      </section>

      <section class="panel">
        <div class="panel-header">
          <div>
            <h2>统计状态</h2>
            <p>服务端返回的统计口径说明</p>
          </div>
          <span class="badge" :class="`badge-${statusTone(currentStatus)}`">{{ statusLabel(currentStatus) }}</span>
        </div>
        <div class="detail-card" style="padding-top: 8px">
          <div v-if="loading" class="loading-state" style="min-height: 150px"><p>正在计算统计…</p></div>
          <div v-else-if="currentStatus === 'no_data'" class="empty-state" style="min-height: 150px"><p>当前时间范围没有可用事件</p></div>
          <div v-else-if="currentStatus === 'denominator_insufficient'" class="notice">
            当前范围缺少有效的 app_start 会话分母。计数仍然可用，但崩溃率和无崩溃会话率不可计算。
          </div>
          <div v-else class="empty-state" style="min-height: 150px">
            <p>比例使用去重后的启动会话作为分母。</p>
          </div>
        </div>
      </section>
    </div>

    <section class="panel">
      <div class="panel-header">
        <div>
          <h2>Crash 问题排行</h2>
          <p>按事件数量排序，点击问题进入事件列表</p>
        </div>
        <span v-if="issues.length" class="badge">{{ issues.length }} 个问题</span>
      </div>
      <div v-if="issueError" class="notice">
        {{ issueError }}
        <button class="link-button" type="button" @click="loadMoreIssues">重试</button>
      </div>
      <IssueTable
        :issues="issues"
        :next-cursor="nextCursor"
        :loading="loading"
        @open="openIssue"
        @next="loadMoreIssues"
      />
    </section>
  </AppLayout>
</template>

<template>
  <!--
    历史兼容图标组件
    设计语言：十字几何主体 + 四角节点 + 斜向连线
    保留原因：兼容旧引用，建议优先使用 MindCrewIcon
    用法：<MediRagIcon :size="40" color="#fff" />
  -->
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    <!-- 竖向主体 -->
    <rect x="12.5" y="2.5" width="7" height="27" rx="3.5" :fill="color" />
    <!-- 横向主体 -->
    <rect x="2.5" y="12.5" width="27" height="7" rx="3.5" :fill="color" />

    <!-- 四角节点 -->
    <circle cx="5.5" cy="5.5" r="2.5" :fill="color" :opacity="nodeOpacity" />
    <circle cx="26.5" cy="5.5" r="2.5" :fill="color" :opacity="nodeOpacity" />
    <circle cx="5.5" cy="26.5" r="2.5" :fill="color" :opacity="nodeOpacity" />
    <circle cx="26.5" cy="26.5" r="2.5" :fill="color" :opacity="nodeOpacity" />

    <!-- 对角连线（节点 → 十字臂边缘） -->
    <line x1="7.5" y1="7.5" x2="12.5" y2="12.5"
      :stroke="color" stroke-width="1.2" :stroke-opacity="lineOpacity" stroke-linecap="round" />
    <line x1="24.5" y1="7.5" x2="19.5" y2="12.5"
      :stroke="color" stroke-width="1.2" :stroke-opacity="lineOpacity" stroke-linecap="round" />
    <line x1="7.5" y1="24.5" x2="12.5" y2="19.5"
      :stroke="color" stroke-width="1.2" :stroke-opacity="lineOpacity" stroke-linecap="round" />
    <line x1="24.5" y1="24.5" x2="19.5" y2="19.5"
      :stroke="color" stroke-width="1.2" :stroke-opacity="lineOpacity" stroke-linecap="round" />
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  size?: number | string
  color?: string
  nodeOpacity?: number
  lineOpacity?: number
}>(), {
  size: 32,
  color: '#ffffff',
  nodeOpacity: 0.55,
  lineOpacity: 0.4,
})
</script>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { message } from "@/utils/message";
import { deviceDetection } from "@pureadmin/utils";

defineOptions({
  name: "Preferences"
});

const STORAGE_KEY = "aisys:notification-prefs";
const list = ref([
  {
    key: "inApp",
    title: "站内信通知",
    illustrate: "事件通知以站内信形式展示在右上角铃铛",
    checked: true
  },
  {
    key: "email",
    title: "邮件通知",
    illustrate: "事件通知发送到绑定邮箱",
    checked: false
  }
]);

onMounted(() => {
  // 从本地读取已保存的偏好（客户端持久化，跨导航/刷新保留）
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
    list.value.forEach(it => {
      if (saved[it.key] !== undefined) it.checked = saved[it.key];
    });
  } catch {
    /* ignore */
  }
});

function onChange(val, item) {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
    saved[item.key] = val;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(saved));
    message(`${item.title}已${val ? "开启" : "关闭"}`, { type: "success" });
  } catch {
    message("设置保存失败", { type: "error" });
  }
}
</script>

<template>
  <div :class="['min-w-45', deviceDetection() ? 'max-w-full' : 'max-w-[70%]']">
    <h3 class="my-8!">通知偏好</h3>
    <div v-for="(item, index) in list" :key="index">
      <div class="flex items-center">
        <div class="flex-1">
          <p>{{ item.title }}</p>
          <p class="wp-4">
            <el-text class="mx-1" type="info">
              {{ item.illustrate }}
            </el-text>
          </p>
        </div>
        <el-switch
          v-model="item.checked"
          inline-prompt
          active-text="是"
          inactive-text="否"
          @change="val => onChange(val, item)"
        />
      </div>
      <el-divider />
    </div>
    <p class="text-xs text-gray-400">偏好保存在本地浏览器（跨刷新保留）。如需统一管理通知规则，请前往「系统管理 → 通知规则」。</p>
  </div>
</template>

<style lang="scss" scoped>
.el-divider--horizontal {
  border-top: 0.1px var(--el-border-color) var(--el-border-style);
}
</style>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue";
import { ElMessage } from "element-plus";
import NoticeList from "./components/NoticeList.vue";
import type { ListItem } from "./data";
import {
  getNotificationList,
  getUnreadCount,
  markAllNotificationsRead
} from "@/api/notification";

import BellIcon from "~icons/lucide/bell";

const dropdownRef = ref();
const list = ref<ListItem[]>([]);
const unread = ref(0);

const hasNotice = computed(() => list.value.length > 0);

// 后端通知 → 列表项映射
const toListItem = (n: any): ListItem => ({
  avatar: "",
  title: n.title || "(无标题)",
  description: n.content || "",
  datetime: n.createdAt || "",
  type: n.type || "",
  status: (n.level === "ERROR"
    ? "danger"
    : n.level === "WARN"
    ? "warning"
    : "info") as ListItem["status"],
  extra: n.isRead ? "已读" : "未读"
});

const fetchData = async () => {
  try {
    const [uc, ls]: any = await Promise.all([
      getUnreadCount(),
      getNotificationList({ page: 1, size: 20 })
    ]);
    if (uc?.code === 0) unread.value = uc.data?.count ?? 0;
    if (ls?.code === 0) {
      const d = ls.data;
      const items = Array.isArray(d) ? d : d?.items ?? [];
      list.value = items.map(toListItem);
    }
  } catch {
    /* 静默 */
  }
};

const onMarkAllRead = async () => {
  try {
    const res: any = await markAllNotificationsRead();
    if (res?.code === 0) {
      ElMessage.success("已全部标记为已读");
      await fetchData();
    } else {
      ElMessage.error(res?.message ?? "操作失败");
    }
  } catch {
    /* 静默 */
  }
};

const onVisible = (v: boolean) => {
  if (v) fetchData();
};

// 定时刷新未读数（红点随真实未读变化）
let timer: any = null;
onMounted(() => {
  fetchData();
  timer = setInterval(fetchData, 30000);
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <el-dropdown
    ref="dropdownRef"
    trigger="click"
    placement="bottom-end"
    @visible-change="onVisible"
  >
    <span
      :class="['dropdown-badge', 'navbar-bg-hover', 'select-none', 'mr-1.75']"
    >
      <el-badge is-dot :hidden="unread === 0">
        <span class="header-notice-icon">
          <IconifyIconOffline :icon="BellIcon" />
        </span>
      </el-badge>
    </span>
    <template #dropdown>
      <el-dropdown-menu>
        <div class="dropdown-tabs" style="width: 330px">
          <div class="notice-title">通知 ({{ list.length }})</div>
          <el-scrollbar max-height="345px">
            <div class="noticeList-container">
              <NoticeList :list="list" empty-text="暂无通知" />
            </div>
          </el-scrollbar>
          <div
            v-if="hasNotice"
            class="border-t border-t-(--el-border-color-light) text-sm"
          >
            <div class="flex-bc m-1">
              <span class="text-gray-400 text-xs pl-2">未读 {{ unread }} 条</span>
              <el-button type="primary" size="small" text @click="onMarkAllRead">
                全部已读
              </el-button>
            </div>
          </div>
        </div>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style lang="scss" scoped>
/* ”铃铛“摇晃衰减动画 */
@keyframes pure-bell-ring {
  0%,
  100% {
    transform-origin: top;
  }

  15% {
    transform: rotateZ(10deg);
  }

  30% {
    transform: rotateZ(-10deg);
  }

  45% {
    transform: rotateZ(5deg);
  }

  60% {
    transform: rotateZ(-5deg);
  }

  75% {
    transform: rotateZ(2deg);
  }
}

.dropdown-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 48px;
  cursor: pointer;

  .header-notice-icon {
    font-size: 16px;
  }

  &:hover {
    .header-notice-icon svg {
      animation: pure-bell-ring 1s both;
    }
  }
}

.dropdown-tabs {
  .noticeList-container {
    padding: 15px 24px 0;
  }
}

.notice-title {
  padding: 8px 16px;
  font-weight: 600;
}
</style>

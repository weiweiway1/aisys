<script setup lang="ts">
import { ref, onMounted } from "vue";
import { getMine } from "@/api/user";
import { deviceDetection } from "@pureadmin/utils";

defineOptions({
  name: "AccountManagement"
});

const info = ref<any>({});

onMounted(async () => {
  const { code, data }: any = await getMine();
  if (code === 0) info.value = data || {};
});
</script>

<template>
  <div :class="['min-w-45', deviceDetection() ? 'max-w-full' : 'max-w-[70%]']">
    <h3 class="my-8!">账号信息</h3>
    <el-descriptions :column="1" border>
      <el-descriptions-item label="用户名">
        {{ info.username || "-" }}
      </el-descriptions-item>
      <el-descriptions-item label="昵称">
        {{ info.nickname || "-" }}
      </el-descriptions-item>
      <el-descriptions-item label="邮箱">
        {{ info.email || "-" }}
      </el-descriptions-item>
      <el-descriptions-item label="手机">
        {{ info.phone || "未绑定" }}
      </el-descriptions-item>
      <el-descriptions-item label="角色">
        {{ (info.roles || []).join("、") || "-" }}
      </el-descriptions-item>
      <el-descriptions-item label="注册时间">
        {{ info.createdAt || "-" }}
      </el-descriptions-item>
    </el-descriptions>
    <p class="mt-4 text-sm text-gray-400">
      如需修改昵称 / 邮箱 / 手机或修改密码，请前往「个人信息」标签页。
    </p>
  </div>
</template>

<style lang="scss" scoped>
.el-divider--horizontal {
  border-top: 0.1px var(--el-border-color) var(--el-border-style);
}
</style>

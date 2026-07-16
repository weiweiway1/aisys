<script setup lang="ts">
import { formUpload } from "@/api/common";
import { message } from "@/utils/message";
import { onMounted, reactive, ref } from "vue";
import { type UserInfo, getMine, updateMine, changePassword } from "@/api/user";
import type { FormInstance, FormRules } from "element-plus";
import ReCropperPreview from "@/components/ReCropperPreview";
import { createFormData, deviceDetection } from "@pureadmin/utils";
import uploadLine from "~icons/ri/upload-line";

defineOptions({
  name: "Profile"
});

const imgSrc = ref("");
const cropperBlob = ref();
const cropRef = ref();
const uploadRef = ref();
const isShow = ref(false);
const userInfoFormRef = ref<FormInstance>();

const userInfos = reactive({
  avatar: "",
  nickname: "",
  email: "",
  phone: "",
  description: ""
});

const rules = reactive<FormRules<UserInfo>>({
  nickname: [{ required: true, message: "昵称必填", trigger: "blur" }]
});

function queryEmail(queryString, callback) {
  const emailList = [
    { value: "@qq.com" },
    { value: "@126.com" },
    { value: "@163.com" }
  ];
  let results = [];
  let queryList = [];
  emailList.map(item =>
    queryList.push({ value: queryString.split("@")[0] + item.value })
  );
  results = queryString
    ? queryList.filter(
        item =>
          item.value.toLowerCase().indexOf(queryString.toLowerCase()) === 0
      )
    : queryList;
  callback(results);
}

const onChange = uploadFile => {
  const reader = new FileReader();
  reader.onload = e => {
    imgSrc.value = e.target.result as string;
    isShow.value = true;
  };
  reader.readAsDataURL(uploadFile.raw);
};

const handleClose = () => {
  cropRef.value.hidePopover();
  uploadRef.value.clearFiles();
  isShow.value = false;
};

const onCropper = ({ blob }) => (cropperBlob.value = blob);

const handleSubmitImage = () => {
  const formData = createFormData({
    file: new File([cropperBlob.value], "avatar")
  });
  formUpload(formData)
    .then(({ code }) => {
      if (code === 0) {
        message("更新头像成功", { type: "success" });
        handleClose();
      } else {
        message("更新头像失败");
      }
    })
    .catch(error => {
      message(`提交异常 ${error}`, { type: "error" });
    });
};

// 更新信息
const onSubmit = async (formEl: FormInstance) => {
  await formEl.validate(async valid => {
    if (!valid) return;
    const res: any = await updateMine({
      nickname: userInfos.nickname,
      email: userInfos.email,
      phone: userInfos.phone
    });
    if (res?.code === 0) message("更新信息成功", { type: "success" });
    else message(res?.message ?? "更新失败", { type: "error" });
  });
};

// 修改密码
const pwdForm = reactive({
  oldPassword: "",
  newPassword: "",
  confirm: ""
});
const pwdSubmitting = ref(false);
const onChangePassword = async () => {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    message("请填写旧密码与新密码", { type: "warning" });
    return;
  }
  if (pwdForm.newPassword.length < 6) {
    message("新密码至少 6 位", { type: "warning" });
    return;
  }
  if (pwdForm.newPassword !== pwdForm.confirm) {
    message("两次输入的新密码不一致", { type: "warning" });
    return;
  }
  pwdSubmitting.value = true;
  try {
    const res: any = await changePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword
    });
    if (res?.code === 0) {
      message("密码修改成功", { type: "success" });
      pwdForm.oldPassword = "";
      pwdForm.newPassword = "";
      pwdForm.confirm = "";
    } else {
      message(res?.message ?? "修改失败", { type: "error" });
    }
  } finally {
    pwdSubmitting.value = false;
  }
};

onMounted(async () => {
  const { code, data } = await getMine();
  if (code === 0) {
    Object.assign(userInfos, data);
  }
});
</script>

<template>
  <div :class="['min-w-45', deviceDetection() ? 'max-w-full' : 'max-w-[70%]']">
    <h3 class="my-8!">个人信息</h3>
    <el-form
      ref="userInfoFormRef"
      label-position="top"
      :rules="rules"
      :model="userInfos"
    >
      <el-form-item label="头像">
        <el-avatar :size="80" :src="userInfos.avatar" />
        <el-upload
          ref="uploadRef"
          accept="image/*"
          action="#"
          :limit="1"
          :auto-upload="false"
          :show-file-list="false"
          :on-change="onChange"
        >
          <el-button plain class="ml-4!">
            <IconifyIconOffline :icon="uploadLine" />
            <span class="ml-2">更新头像</span>
          </el-button>
        </el-upload>
      </el-form-item>
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="userInfos.nickname" placeholder="请输入昵称" />
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-autocomplete
          v-model="userInfos.email"
          :fetch-suggestions="queryEmail"
          :trigger-on-focus="false"
          placeholder="请输入邮箱"
          clearable
          class="w-full"
        />
      </el-form-item>
      <el-form-item label="联系电话">
        <el-input
          v-model="userInfos.phone"
          placeholder="请输入联系电话"
          clearable
        />
      </el-form-item>
      <el-form-item label="简介">
        <el-input
          v-model="userInfos.description"
          placeholder="请输入简介"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 8 }"
          maxlength="56"
          show-word-limit
        />
      </el-form-item>
      <el-button type="primary" @click="onSubmit(userInfoFormRef)">
        更新信息
      </el-button>
    </el-form>

    <h3 class="my-8!">修改密码</h3>
    <el-form label-position="top" :class="deviceDetection() ? 'max-w-full' : 'max-w-[70%]'">
      <el-form-item label="旧密码">
        <el-input
          v-model="pwdForm.oldPassword"
          type="password"
          show-password
          placeholder="请输入旧密码"
        />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input
          v-model="pwdForm.newPassword"
          type="password"
          show-password
          placeholder="至少 6 位"
        />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input
          v-model="pwdForm.confirm"
          type="password"
          show-password
          placeholder="再次输入新密码"
        />
      </el-form-item>
      <el-button type="primary" :loading="pwdSubmitting" @click="onChangePassword">
        修改密码
      </el-button>
    </el-form>
    <el-dialog
      v-model="isShow"
      width="40%"
      title="编辑头像"
      destroy-on-close
      :closeOnClickModal="false"
      :before-close="handleClose"
      :fullscreen="deviceDetection()"
    >
      <ReCropperPreview ref="cropRef" :imgSrc="imgSrc" @cropper="onCropper" />
      <template #footer>
        <div class="dialog-footer">
          <el-button bg text @click="handleClose">取消</el-button>
          <el-button bg text type="primary" @click="handleSubmitImage">
            确定
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

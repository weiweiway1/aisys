import { getToken, formatToken } from "@/utils/auth";

type Result = {
  code: number;
  message: string;
  data: any;
};

/**
 * 单文件上传（头像等小文件）→ 后端 storage 真实接口 POST /api/v1/files/upload（multipart/form-data）。
 * 用 XHR 直发：http 工具默认 Content-Type=application/json 会覆盖 FormData，导致后端 415。
 */
export const formUpload = (data: FormData) => {
  return new Promise<Result>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/v1/files/upload");
    const t = getToken();
    if (t?.accessToken)
      xhr.setRequestHeader("Authorization", formatToken(t.accessToken));
    xhr.onload = () => {
      try {
        resolve(JSON.parse(xhr.responseText));
      } catch (e) {
        reject(e);
      }
    };
    xhr.onerror = () => reject(new Error("上传失败"));
    xhr.send(data);
  });
};

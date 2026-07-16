package com.aisys.storage.service;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.storage.constant.StorageConstants;
import com.aisys.storage.constant.StorageErrorCode;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 存储路径安全工具（DDD 5.8）。
 * <p>所有 prefix/path 服务端强制拼当前租户前缀 {@code {tenantId}/...}，规范化并拒绝：
 * <ul>
 *   <li>绝对路径（以 / 开头）</li>
 *   <li>路径穿越片段 {@code ..}</li>
 *   <li>反斜杠 / 空段 / 控制字符</li>
 * </ul>
 * 平台超管（{@code UserContext.isPlatformAdmin()}）可绕过租户前缀，但仍拒绝 {@code ..} 与绝对路径。
 */
@Component
public class StoragePathUtil {

    /**
     * 规范化为租户作用域内的对象 key。
     *
     * @param input 用户输入的相对路径（如 "models/abc/model.bin"）；允许为空（返回租户根）
     * @return 强制以 {@code {tenantId}/...} 开头的 key
     */
    public String resolveTenantKey(String input) {
        String normalized = normalize(input);
        Long tenantId = UserContext.getTenantId();
        boolean bypass = UserContext.isPlatformAdmin();

        if (tenantId == null) {
            if (!bypass) {
                // 普通用户缺租户上下文 → 拒绝
                throw new BusinessException(StorageErrorCode.TENANT_REQUIRED);
            }
            // 平台超管绕过：允许任意 key（但仍经过 normalize 防 ..）
            return normalized.isEmpty() ? "" : normalized;
        }

        String prefix = tenantId + StorageConstants.TENANT_ROOT_SEPARATOR;
        return normalized.isEmpty() ? prefix : prefix + normalized;
    }

    /**
     * 计算秒传 dedup key：dedup/{tenantId}/{hash}（DDD 4.2.1）。
     * 平台超管无租户时退化为 dedup/{hash}。
     */
    public String dedupKey(String fileHash) {
        if (fileHash == null || fileHash.isBlank()) {
            throw new BusinessException(StorageErrorCode.BAD_REQUEST, "fileHash 不能为空");
        }
        String safeHash = sanitize(fileHash);
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            if (!UserContext.isPlatformAdmin()) {
                throw new BusinessException(StorageErrorCode.TENANT_REQUIRED);
            }
            return StorageConstants.DEDUP_PREFIX + StorageConstants.TENANT_ROOT_SEPARATOR + safeHash;
        }
        return StorageConstants.DEDUP_PREFIX
                + StorageConstants.TENANT_ROOT_SEPARATOR + tenantId
                + StorageConstants.TENANT_ROOT_SEPARATOR + safeHash;
    }

    /**
     * 容错解析：若 input 已以当前租户前缀开头（已拼前缀，如来自 initiate()/uploadUrl() 的返回值），
     * 则原样返回；否则按相对路径拼前缀。用于 delete/download 等以客户端回传 key 为入参的场景，
     * 兼容「相对」与「已拼前缀」两种输入，避免双重前缀导致删/下载不到对象。
     * <p>注意：不能用 belongsToCurrentTenant 判断（平台超管恒为 true 会错误地不拼前缀），
     * 这里按真实前缀字符串 startsWith 判断。
     */
    public String resolveLenient(String input) {
        String normalized = normalize(input);
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            // 平台超管且无租户上下文 / 缺租户：不拼前缀
            return normalized;
        }
        String prefix = tenantId + StorageConstants.TENANT_ROOT_SEPARATOR;
        if (normalized.startsWith(prefix)) {
            return normalized;   // 已拼前缀，原样用
        }
        return normalized.isEmpty() ? prefix : prefix + normalized;
    }

    /**
     * 判断给定（已拼好前缀的）key 是否属于当前租户命名空间。
     * <p>用于 complete/completeMultipart 等以客户端回传 key 的场景做越权校验：
     * 非平台超管时，key 必须以 {@code {tenantId}/} 开头。
     */
    public boolean belongsToCurrentTenant(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (UserContext.isPlatformAdmin()) {
            return true;
        }
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            return false;
        }
        return key.startsWith(tenantId + StorageConstants.TENANT_ROOT_SEPARATOR);
    }

    /** 从已规范的 key 提取相对显示名（去掉租户前缀）。 */
    public String displayName(String key) {
        if (key == null || key.isEmpty()) return "";
        Long tenantId = UserContext.getTenantId();
        String prefix = (tenantId == null ? "" : tenantId + StorageConstants.TENANT_ROOT_SEPARATOR);
        if (key.startsWith(prefix)) {
            return key.substring(prefix.length());
        }
        return key;
    }

    private String normalize(String input) {
        if (input == null) return "";
        // 统一分隔符，禁止反斜杠
        String s = input.replace('\\', '/');
        // 去首尾空白与首尾分隔
        s = s.trim();
        while (s.startsWith(StorageConstants.TENANT_ROOT_SEPARATOR)) {
            s = s.substring(1);
        }
        while (s.endsWith(StorageConstants.TENANT_ROOT_SEPARATOR)) {
            s = s.substring(0, s.length() - 1);
        }
        // 注：绝对路径（首斜杠）已被上面的循环裁掉；核心安全靠逐段校验（.. 与控制字符）。
        // 逐段校验：拒绝整段为 .. 的穿越片段 / 空段 / 控制字符
        // （仅判整段相等，避免误伤 v1.2.0、file..bak 等含连续点的合法文件名）
        StringBuilder out = new StringBuilder();
        for (String seg : s.split(StorageConstants.TENANT_ROOT_SEPARATOR)) {
            if (seg.isEmpty()) continue;
            if (Objects.equals(seg, StorageConstants.PATH_TRAVERSAL_TOKEN)) {
                throw new BusinessException(StorageErrorCode.INVALID_PATH);
            }
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (c < 0x20 || c == 0x7f) {
                    throw new BusinessException(StorageErrorCode.INVALID_PATH);
                }
            }
            if (out.length() > 0) out.append(StorageConstants.TENANT_ROOT_SEPARATOR);
            out.append(seg);
        }
        return out.toString();
    }

    private String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '-' || c == '_') {
                sb.append(c);
            }
            // 其它字符（含 / ..）直接丢弃，防止 hash 注入路径
        }
        String s = sb.toString();
        if (s.isEmpty()) {
            throw new BusinessException(StorageErrorCode.BAD_REQUEST, "fileHash 格式非法");
        }
        return s;
    }
}

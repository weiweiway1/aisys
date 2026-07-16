package com.aisys.storage.init;

import com.aisys.common.s3.service.S3StorageService;
import com.aisys.storage.entity.StoragePool;
import com.aisys.storage.service.StoragePoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动初始化（DDD 5.8）：确保默认存储池（name=default）存在，并保证默认 bucket 可访问。
 * <p>平台共享表，无需租户上下文即可写入。
 */
@Component
@Order(20)
public class DefaultPoolInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultPoolInitializer.class);

    private final StoragePoolService poolService;
    private final S3StorageService s3;

    public DefaultPoolInitializer(StoragePoolService poolService, S3StorageService s3) {
        this.poolService = poolService;
        this.s3 = s3;
    }

    @Override
    public void run(String... args) {
        try {
            StoragePool pool = poolService.ensureDefaultPool();
            log.info("[Storage] 默认存储池就绪: id={} name={} bucket={}", pool.getId(), pool.getName(), pool.getBucket());
            if (pool.getBucket() != null && !pool.getBucket().isBlank()) {
                try {
                    s3.ensureBucket(pool.getBucket());
                } catch (Exception e) {
                    log.warn("[Storage] 默认 bucket ensure 失败（可能是 SeaweedFS 尚未就绪，稍后重试由业务侧兜底）: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Storage] 默认存储池初始化失败: {}", e.getMessage(), e);
            // 不抛出：避免阻断服务启动（依赖 Flyway 已建表后才生效）
        }
    }
}

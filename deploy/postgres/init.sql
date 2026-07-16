-- ============================================================================
-- AI模型测评平台 — PostgreSQL 初始化（以 POSTGRES_USER 超管身份在容器首次启动时执行）
-- 创建 RLS 双角色、授权、扩展（DDD 4.1.3 / 8.2）
-- ============================================================================

-- 1) 应用普通角色：受 RLS 约束（不带 BYPASSRLS），是默认连接角色
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aisys_app') THEN
    CREATE ROLE aisys_app LOGIN PASSWORD 'aisys_app_pwd'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
  END IF;
END $$;

-- 2) 平台超管角色：带 BYPASSRLS，访问全局资源（DDL/迁移不由此角色执行）
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aisys_platform_admin') THEN
    CREATE ROLE aisys_platform_admin LOGIN PASSWORD 'aisys_admin_pwd'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS;
  END IF;
END $$;

-- 3) 数据库与 schema 授权
GRANT CONNECT ON DATABASE aisys TO aisys_app, aisys_platform_admin;
GRANT USAGE, CREATE ON SCHEMA public TO aisys_app;
GRANT USAGE ON SCHEMA public TO aisys_platform_admin;

-- 4) 默认权限：aisys_app 建表/序列时同步授予 aisys_platform_admin，保证超管连接可读写
ALTER DEFAULT PRIVILEGES FOR ROLE aisys_app IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO aisys_platform_admin;
ALTER DEFAULT PRIVILEGES FOR ROLE aisys_app IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO aisys_platform_admin;

-- 5) 扩展
-- pg_trgm：contrib 自带，用于中文子串模糊检索（ILIKE + GIN trigram 索引）
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- pg_jieba：中文分词全文检索（DDD 11.2），未安装则静默跳过
DO $$ BEGIN
  CREATE EXTENSION IF NOT EXISTS pg_jieba;
  RAISE NOTICE 'pg_jieba 已启用，中文全文检索使用 jiebacfg';
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'pg_jieba 不可用，将回退到 simple/ts 检索（中文子串仍可用 pg_trgm）';
END $$;

-- 提示
\echo 'aisys 数据库初始化完成：角色 aisys_app(RLS) / aisys_platform_admin(BYPASSRLS) 已就绪'

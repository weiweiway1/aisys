-- 项目 / 项目成员（项目为租户隔离表，启用 RLS + FORCE，DDL 4.1.3）
CREATE TABLE project (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    created_by  BIGINT,
    status      VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_project_tenant ON project(tenant_id);

-- 租户隔离（RLS）
ALTER TABLE project ENABLE ROW LEVEL SECURITY;
ALTER TABLE project FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON project
    USING      (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE project ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;

CREATE TABLE project_member (
    id         BIGSERIAL PRIMARY KEY,
    project_id BIGINT     NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id    BIGINT     NOT NULL,
    role       VARCHAR(32) NOT NULL DEFAULT 'member',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, user_id)
);
CREATE INDEX idx_project_member_proj ON project_member(project_id);

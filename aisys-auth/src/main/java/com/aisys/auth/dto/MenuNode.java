package com.aisys.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 菜单节点（pure-admin 后端动态路由格式，DDD 6.1 getAsyncRoutes，详见官方文档「路由和菜单」）。
 * <p>meta 支持：icon / title / rank / roles / showLink / showParent / activePath / keepAlive 等。
 * component 为视图相对路径（如 "model/list/index"），前端 addAsyncRoutes 经 import.meta.glob 解析为
 * /src/views/model/list/index.vue；父级路由不传 component（前端用 Layout 承载）。
 * <p>NON_NULL：叶子菜单（无子级）不输出 children/component/redirect，符合 pure-admin 文档的叶子格式，
 * 避免前端对 null/空 children 处理异常。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MenuNode {

    private String path;
    private String name;
    private String component;
    private String redirect;
    private Map<String, Object> meta;
    private List<MenuNode> children;

    public MenuNode() {}

    public static MenuNode of(String path, String name, Map<String, Object> meta) {
        MenuNode n = new MenuNode();
        n.path = path;
        n.name = name;
        n.meta = meta;
        return n;
    }

    public static MenuNode of(String path, String name, String component, Map<String, Object> meta) {
        MenuNode n = of(path, name, meta);
        n.component = component;
        return n;
    }

    public MenuNode children(List<MenuNode> c) {
        this.children = c;
        return this;
    }

    public void setChildren(List<MenuNode> c) { this.children = c; }

    public MenuNode redirect(String r) {
        this.redirect = r;
        return this;
    }

    public String getPath() { return path; }
    public String getName() { return name; }
    public String getComponent() { return component; }
    public String getRedirect() { return redirect; }
    public Map<String, Object> getMeta() { return meta; }
    public List<MenuNode> getChildren() { return children; }
}

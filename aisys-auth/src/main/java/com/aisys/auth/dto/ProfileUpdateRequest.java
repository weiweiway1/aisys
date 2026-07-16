package com.aisys.auth.dto;

/** 当前用户自助更新资料（昵称/邮箱/电话）。 */
public record ProfileUpdateRequest(String nickname, String email, String phone) {}

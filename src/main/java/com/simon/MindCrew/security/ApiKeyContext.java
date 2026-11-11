package com.simon.MindCrew.security;

import com.simon.MindCrew.entity.ApiKey;

/**
 * 对外 API Key 上下文 · ThreadLocal
 * Filter 校验通过后塞入；业务层 ApiKeyContext.current() 拿当前 key
 */
public final class ApiKeyContext {
    private static final ThreadLocal<ApiKey> HOLDER = new ThreadLocal<>();

    public static void set(ApiKey k)    { HOLDER.set(k); }
    public static ApiKey current()      { return HOLDER.get(); }
    public static Long currentId()      { ApiKey k = HOLDER.get(); return k == null ? null : k.getId(); }
    public static void clear()          { HOLDER.remove(); }

    private ApiKeyContext() {}
}

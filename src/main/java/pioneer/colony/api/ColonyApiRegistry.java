package pioneer.colony.api;

/** API 实现注册点；本体只注册一次，附属模组只读取 ColonyApi.get()。 */
public final class ColonyApiRegistry {
    private static volatile ColonyApi api;

    private ColonyApiRegistry() {
    }

    public static synchronized void register(ColonyApi implementation) {
        if (implementation == null) throw new IllegalArgumentException("ColonyApi 实现不能为空");
        if (api != null && api != implementation) throw new IllegalStateException("ColonyApi 已注册");
        api = implementation;
    }

    public static ColonyApi get() {
        ColonyApi current = api;
        if (current == null) throw new IllegalStateException("ColonyApi 尚未初始化；请在服务端模组加载完成后调用");
        return current;
    }
}

package youraveragedev.safeserver;

/**
 * 安全服务器模组的常量定义类，包含消息提示、配置参数等。
 */
public final class SafeserverConstants {

    // 密码最小长度
    public static final int MIN_PASSWORD_LENGTH = 4;

    // 认证时安全出生点偏移
    public static final double SAFE_SPAWN_Y_OFFSET = 1.0;
    public static final double SAFE_SPAWN_CENTER_OFFSET = 0.5;
    public static final double FALLBACK_Y_COORDINATE = 65.0;

    // 认证期间交互提示
    public static final String AUTH_INTERACT_MESSAGE = "你必须完成认证才能进行此操作";
    public static final String AUTH_COMMAND_MESSAGE = "你必须先认证请使用 /login 或 /setpassword 命令";

    // 欢迎消息
    public static final String WELCOME_BACK_MESSAGE = "欢迎回来！请使用 /login <密码> 登录";
    public static final String WELCOME_NEW_MESSAGE = "欢迎！本服务器需要身份认证";
    public static final String SET_PASSWORD_PROMPT = "请使用 /setpassword <密码> <确认密码> 设置你的密码";
    public static final String RESET_PASSWORD_MESSAGE = "你的密码已被管理员重置";
    public static final String RESET_PASSWORD_PROMPT = "请使用 /setpassword <新密码> <确认密码> 重新设置密码";

    // 密码相关错误与成功提示
    public static final String PASSWORD_MISMATCH_ERROR = "两次输入的密码不一致，请重新输入";
    public static final String PASSWORD_LENGTH_ERROR = "密码长度必须不少于 " + MIN_PASSWORD_LENGTH + " 个字符";
    public static final String PASSWORD_SET_SUCCESS = "密码设置成功！你现在已登录";
    public static final String PASSWORD_RESET_SUCCESS = "密码重置成功！";
    public static final String PASSWORD_CHANGE_SUCCESS = "密码修改成功";
    public static final String LOGIN_SUCCESS = "登录成功！";
    public static final String INCORRECT_PASSWORD_ERROR = "密码错误";
    public static final String ALREADY_AUTHENTICATED_ERROR = "你已经通过认证，无需重复操作";
    public static final String NO_PASSWORD_SET_ERROR = "你尚未设置密码，请先使用 /setpassword 设置";
    public static final String ALREADY_HAS_PASSWORD_ERROR = "你已设置过密码，请使用 /login 登录";
    public static final String NO_PASSWORD_NEEDED_ERROR = "你现在无需设置密码";
    public static final String MUST_BE_LOGGED_IN_ERROR = "你必须先登录才能更改密码";
    public static final String CHECK_OLD_PASSWORD_ERROR = "更改密码失败，请检查原密码是否正确";
    public static final String PLAYER_ONLY_COMMAND_ERROR = "此命令只能由玩家执行";
    public static final String CONTACT_ADMIN_ERROR = "请联系管理员寻求帮助";

    // 哈希加密错误占位符
    public static final String HASHING_ERROR_VALUE = "HASHING_ERROR";

    // 工具类禁止实例化
    private SafeserverConstants() {
        throw new UnsupportedOperationException("此类为工具类，不可实例化。");
    }
}

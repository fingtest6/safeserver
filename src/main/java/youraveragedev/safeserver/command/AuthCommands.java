package youraveragedev.safeserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import youraveragedev.safeserver.Safeserver;
import youraveragedev.safeserver.SafeserverConstants;

import java.util.UUID;

public class AuthCommands {

    public static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, Safeserver modInstance) {
        dispatcher.register(CommandManager.literal("setpassword")
                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                .then(CommandManager.argument("password", StringArgumentType.string())
                        .then(CommandManager.argument("confirmPassword", StringArgumentType.string())
                                .executes(context -> runSetPasswordCommand(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "password"),
                                        StringArgumentType.getString(context, "confirmPassword"),
                                        modInstance)))));

        dispatcher.register(CommandManager.literal("login")
                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                .then(CommandManager.argument("password", StringArgumentType.greedyString())
                        .executes(context -> runLoginCommand(context.getSource(), StringArgumentType.getString(context, "password"), modInstance))));

        registerNewCommands(dispatcher, modInstance);
    }

    private static int runSetPasswordCommand(ServerCommandSource source, String password, String confirmPassword, Safeserver modInstance) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal(SafeserverConstants.PLAYER_ONLY_COMMAND_ERROR));
            return 0;
        }

        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();

        // 检查两次密码是否一致
        if (!password.equals(confirmPassword)) {
            source.sendError(Text.literal(SafeserverConstants.PASSWORD_MISMATCH_ERROR));
            return 0;
        }

        // 检查密码长度
        if (password.length() < SafeserverConstants.MIN_PASSWORD_LENGTH) {
            source.sendError(Text.literal(SafeserverConstants.PASSWORD_LENGTH_ERROR));
            return 0;
        }

        boolean isAuthenticating = modInstance.isPlayerAuthenticating(playerUuid);
        boolean hasPassword = modInstance.hasPassword(playerUuid);

        if (isAuthenticating && !hasPassword) {
            // 首次设置密码
            boolean success = modInstance.registerPlayer(playerUuid, password);
            if (success) {
                source.sendFeedback(() -> Text.literal(SafeserverConstants.PASSWORD_SET_SUCCESS), false);
                Safeserver.LOGGER.info("玩家 {} 设置了密码并完成认证。", playerName);
                return 1;
            } else {
                source.sendError(Text.literal("设置密码失败。" + SafeserverConstants.CONTACT_ADMIN_ERROR));
                Safeserver.LOGGER.error("为玩家 {} 设置密码失败。", playerName);
                return 0;
            }
        } else if (!isAuthenticating && hasPassword) {
            // 已认证用户重置密码
            boolean success = modInstance.resetAndSetPassword(playerUuid, password);
            if (success) {
                source.sendFeedback(() -> Text.literal(SafeserverConstants.PASSWORD_RESET_SUCCESS), false);
                Safeserver.LOGGER.info("玩家 {} 重置了密码。", playerName);
                return 1;
            } else {
                source.sendError(Text.literal("重置密码失败。" + SafeserverConstants.CONTACT_ADMIN_ERROR));
                Safeserver.LOGGER.error("为玩家 {} 重置密码失败。", playerName);
                return 0;
            }
        } else if (isAuthenticating && hasPassword) {
            // 正在认证但已有密码，应使用登录
            source.sendError(Text.literal(SafeserverConstants.ALREADY_HAS_PASSWORD_ERROR));
            return 0;
        } else {
            // 不在认证流程中且无密码（异常情况）
            source.sendError(Text.literal(SafeserverConstants.NO_PASSWORD_NEEDED_ERROR));
            return 0;
        }
    }

    private static int runLoginCommand(ServerCommandSource source, String password, Safeserver modInstance) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal(SafeserverConstants.PLAYER_ONLY_COMMAND_ERROR));
            return 0;
        }

        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();

        if (!modInstance.isPlayerAuthenticating(playerUuid)) {
            source.sendError(Text.literal(SafeserverConstants.ALREADY_AUTHENTICATED_ERROR));
            return 0;
        }

        if (!modInstance.hasPassword(playerUuid)) {
            source.sendError(Text.literal(SafeserverConstants.NO_PASSWORD_SET_ERROR));
            return 0;
        }

        boolean success = modInstance.authenticatePlayer(playerUuid, password);
        if (success) {
            source.sendFeedback(() -> Text.literal(SafeserverConstants.LOGIN_SUCCESS), false);
            Safeserver.LOGGER.info("玩家 {} 成功登录。", playerName);
            return 1;
        } else {
            source.sendError(Text.literal(SafeserverConstants.INCORRECT_PASSWORD_ERROR));
            Safeserver.LOGGER.warn("玩家 {} 登录失败（密码错误）。", playerName);
            return 0;
        }
    }

    private static void registerNewCommands(CommandDispatcher<ServerCommandSource> dispatcher, Safeserver modInstance) {
        // 玩家修改自己的密码
        dispatcher.register(CommandManager.literal("changepassword")
                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                .then(CommandManager.argument("oldPassword", StringArgumentType.string())
                        .then(CommandManager.argument("newPassword", StringArgumentType.string())
                                .then(CommandManager.argument("confirmNewPassword", StringArgumentType.string())
                                        .executes(context -> runChangePasswordCommand(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "oldPassword"),
                                                StringArgumentType.getString(context, "newPassword"),
                                                StringArgumentType.getString(context, "confirmNewPassword"),
                                                modInstance))))));

        // OP 重置他人密码
        dispatcher.register(CommandManager.literal("resetpassword")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("targetPlayer", EntityArgumentType.player())
                        .executes(context -> runResetPasswordCommand(
                                context.getSource(),
                                EntityArgumentType.getPlayer(context, "targetPlayer"),
                                modInstance))));
    }

    private static int runChangePasswordCommand(ServerCommandSource source, String oldPassword, String newPassword, String confirmNewPassword, Safeserver modInstance) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal(SafeserverConstants.PLAYER_ONLY_COMMAND_ERROR));
            return 0;
        }

        UUID playerUuid = player.getUuid();

        if (modInstance.isPlayerAuthenticating(playerUuid)) {
            source.sendError(Text.literal(SafeserverConstants.MUST_BE_LOGGED_IN_ERROR));
            return 0;
        }

        if (!newPassword.equals(confirmNewPassword)) {
            source.sendError(Text.literal(SafeserverConstants.PASSWORD_MISMATCH_ERROR));
            return 0;
        }

        if (newPassword.length() < SafeserverConstants.MIN_PASSWORD_LENGTH) {
            source.sendError(Text.literal(SafeserverConstants.PASSWORD_LENGTH_ERROR));
            return 0;
        }

        boolean success = modInstance.changePlayerPassword(playerUuid, oldPassword, newPassword);

        if (success) {
            source.sendFeedback(() -> Text.literal(SafeserverConstants.PASSWORD_CHANGE_SUCCESS), false);
            Safeserver.LOGGER.info("玩家 {} 修改了密码。", player.getName().getString());
            return 1;
        } else {
            source.sendError(Text.literal(SafeserverConstants.CHECK_OLD_PASSWORD_ERROR));
            Safeserver.LOGGER.warn("玩家 {} 修改密码失败（原密码错误）。", player.getName().getString());
            return 0;
        }
    }

    private static int runResetPasswordCommand(ServerCommandSource source, ServerPlayerEntity targetPlayer, Safeserver modInstance) {
        UUID targetUuid = targetPlayer.getUuid();
        String targetName = targetPlayer.getName().getString();
        String sourceName = source.getName();

        if (!modInstance.hasPassword(targetUuid)) {
            source.sendError(Text.literal("玩家 " + targetName + " 尚未通过此插件设置密码。"));
            return 0;
        }

        boolean success = modInstance.resetPlayerPassword(targetUuid);

        if (success) {
            source.sendFeedback(() -> Text.literal("玩家 " + targetName + " 的密码已重置，他们需要重新设置新密码。"), false);
            Safeserver.LOGGER.info("玩家 {} ({}) 的密码被 {} 重置。", targetName, targetUuid, sourceName);
            return 1;
        } else {
            source.sendError(Text.literal("无法重置玩家 " + targetName + " 的密码，可能该玩家未设置密码。"));
            Safeserver.LOGGER.error("{} 尝试为玩家 {} ({}) 重置密码失败。", sourceName, targetName, targetUuid);
            return 0;
        }
    }
}

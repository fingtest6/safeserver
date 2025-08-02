package youraveragedev.safeserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import youraveragedev.safeserver.Safeserver;

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
            source.sendError(Text.literal("This command can only be run by a player."));
            return 0;
        }

        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();

        // Check if passwords match
        if (!password.equals(confirmPassword)) {
            source.sendError(Text.literal("Passwords do not match. Please try again."));
            return 0;
        }

        // Basic password policy (e.g., minimum length)
        if (password.length() < 4) { // Example: require at least 4 characters
            source.sendError(Text.literal("Password must be at least 4 characters long."));
            return 0;
        }

        // Handle different scenarios:
        boolean isAuthenticating = modInstance.isPlayerAuthenticating(playerUuid);
        boolean hasPassword = modInstance.hasPassword(playerUuid);

        if (isAuthenticating && !hasPassword) {
            // First-time password setting (original behavior)
            boolean success = modInstance.registerPlayer(playerUuid, password);
            if (success) {
                source.sendFeedback(() -> Text.literal("Password set successfully! You are now logged in."), false);
                Safeserver.LOGGER.info("Player {} set their password and is now authenticated.", playerName);
                return 1;
            } else {
                source.sendError(Text.literal("Failed to set password. Please contact an admin."));
                Safeserver.LOGGER.error("Failed to set password for player {}.", playerName);
                return 0;
            }
        } else if (!isAuthenticating && hasPassword) {
            // Authenticated user resetting their password
            boolean success = modInstance.resetAndSetPassword(playerUuid, password);
            if (success) {
                source.sendFeedback(() -> Text.literal("Password reset successfully!"), false);
                Safeserver.LOGGER.info("Player {} reset their password.", playerName);
                return 1;
            } else {
                source.sendError(Text.literal("Failed to reset password. Please contact an admin."));
                Safeserver.LOGGER.error("Failed to reset password for player {}.", playerName);
                return 0;
            }
        } else if (isAuthenticating && hasPassword) {
            // Player is authenticating but already has a password - should use login
            source.sendError(Text.literal("You already have a password set. Use /login instead."));
            return 0;
        } else {
            // Player is not authenticating and has no password - shouldn't happen normally
            source.sendError(Text.literal("You don't need to set a password right now."));
            return 0;
        }
    }

    private static int runLoginCommand(ServerCommandSource source, String password, Safeserver modInstance) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("This command can only be run by a player."));
            return 0;
        }

        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();

        if (!modInstance.isPlayerAuthenticating(playerUuid)) {
            source.sendError(Text.literal("You are already authenticated or not required to log in now."));
            return 0;
        }

        if (!modInstance.hasPassword(playerUuid)) {
            source.sendError(Text.literal("You don't have a password set yet. Use /setpassword first."));
            return 0;
        }

        boolean success = modInstance.authenticatePlayer(playerUuid, password);
        if (success) {
            source.sendFeedback(() -> Text.literal("Login successful!"), false);
            Safeserver.LOGGER.info("Player {} successfully authenticated.", playerName);
            return 1;
        } else {
            source.sendError(Text.literal("Incorrect password."));
            Safeserver.LOGGER.warn("Failed login attempt for player {}.");
            return 0;
        }
    }

    private static void registerNewCommands(CommandDispatcher<ServerCommandSource> dispatcher, Safeserver modInstance) {
        // Command for changing own password
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

        // Command for OPs to reset another player's password
        dispatcher.register(CommandManager.literal("resetpassword")
                .requires(source -> source.hasPermissionLevel(2)) // Require OP level 2 (configurable)
                .then(CommandManager.argument("targetPlayer", EntityArgumentType.player())
                        .executes(context -> runResetPasswordCommand(
                                context.getSource(),
                                EntityArgumentType.getPlayer(context, "targetPlayer"),
                                modInstance))));
    }

    private static int runChangePasswordCommand(ServerCommandSource source, String oldPassword, String newPassword, String confirmNewPassword, Safeserver modInstance) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("This command can only be run by a player."));
            return 0;
        }

        UUID playerUuid = player.getUuid();

        // Ensure player is actually logged in (not authenticating)
        if (modInstance.isPlayerAuthenticating(playerUuid)) {
            source.sendError(Text.literal("You must be logged in to change your password."));
            return 0;
        }

        // Check if new passwords match
        if (!newPassword.equals(confirmNewPassword)) {
            source.sendError(Text.literal("New passwords do not match. Please try again."));
            return 0;
        }

        // Basic password policy
        if (newPassword.length() < 4) {
            source.sendError(Text.literal("New password must be at least 4 characters long."));
            return 0;
        }

        // Attempt to change password
        boolean success = modInstance.changePlayerPassword(playerUuid, oldPassword, newPassword);

        if (success) {
            source.sendFeedback(() -> Text.literal("Password changed successfully."), false);
            Safeserver.LOGGER.info("Player {} changed their password.", player.getName().getString());
            return 1;
        } else {
            source.sendError(Text.literal("Failed to change password. Check your old password."));
            Safeserver.LOGGER.warn("Failed password change attempt for player {}.", player.getName().getString());
            return 0;
        }
    }

    private static int runResetPasswordCommand(ServerCommandSource source, ServerPlayerEntity targetPlayer, Safeserver modInstance) {
        UUID targetUuid = targetPlayer.getUuid();
        String targetName = targetPlayer.getName().getString();
        String sourceName = source.getName();

        // Check if target player actually has a password registered with the mod
        if (!modInstance.hasPassword(targetUuid)) {
             source.sendError(Text.literal("Player " + targetName + " does not have a password set by this mod."));
             return 0;
        }

        boolean success = modInstance.resetPlayerPassword(targetUuid);

        if (success) {
            source.sendFeedback(() -> Text.literal("Password for player " + targetName + " has been reset. They will need to set a new one."), false);
            Safeserver.LOGGER.info("Password for player {} ({}) was reset by {}.", targetName, targetUuid, sourceName);
            // Message is sent to the target player within resetPlayerPassword if they are online
            return 1;
        } else {
            // This might happen if the player was just removed concurrently, though unlikely.
            source.sendError(Text.literal("Failed to reset password for player " + targetName + ". They might not have a password set."));
             Safeserver.LOGGER.error("Failed attempt by {} to reset password for player {} ({}).", sourceName, targetName, targetUuid);
            return 0;
        }
    }
} 
package youraveragedev.safeserver;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import youraveragedev.safeserver.command.AuthCommands;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

public class Safeserver implements ModInitializer {
    public static final String MOD_ID = "safeserver";

    // 日志记录器
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // 密码存储（线程安全）
    private final ConcurrentHashMap<String, String> playerPasswords = new ConcurrentHashMap<>();
    
    // 玩家状态管理
    private final PlayerStateManager stateManager = new PlayerStateManager();
    
    // 异步文件操作执行器
    private final Executor fileExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Safeserver-FileIO");
        t.setDaemon(true);
        return t;
    });

    // JSON 处理器
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PASSWORD_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private Path passwordFilePath;

    private MinecraftServer serverInstance;

    @Override
    public void onInitialize() {
        LOGGER.info("正在初始化 SafeServer 安全认证系统...");

        // 设置密码文件路径
        passwordFilePath = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("passwords.json");

        // 加载已保存的密码
        loadPasswords();
        
        // 初始化状态管理器
        stateManager.setServerInstance(null);

        // 玩家加入事件
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            UUID playerUuid = player.getUuid();
            String playerUuidString = playerUuid.toString();
            String playerName = player.getName().getString();

            LOGGER.info("玩家 {} ({}) 加入游戏，正在检查认证状态...", playerName, playerUuidString);

            if (playerPasswords.containsKey(playerUuidString)) {
                // 老玩家需登录
                if (!stateManager.isPlayerAuthenticating(playerUuid)) {
                    LOGGER.info("玩家 {} 需要登录。", playerName);
                    stateManager.applyAuthenticationState(player, server);
                    stateManager.sendWelcomeMessages(player, true);
                }
            } else {
                // 新玩家需设置密码
                if (!stateManager.isPlayerAuthenticating(playerUuid)) {
                    LOGGER.info("玩家 {} 需要设置密码。", playerName);
                    stateManager.applyAuthenticationState(player, server);
                    stateManager.sendWelcomeMessages(player, false);
                }
            }
        });

        // 玩家断开连接事件
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            UUID playerUuid = player.getUuid();
            String playerName = player.getName().getString();
            
            stateManager.handlePlayerDisconnect(player, server);
            
            // 安全性机制：断开时移除 OP 权限
            if (server.getPlayerManager().isOperator(player.getGameProfile())) {
                server.getPlayerManager().removeFromOperators(player.getGameProfile());
                LOGGER.info("玩家 {} ({}) 断开连接，已移除 OP 权限以确保安全。", playerName, playerUuid);
            }
        });

        // 注册命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            AuthCommands.registerCommands(dispatcher, this);
            LOGGER.info("已注册安全认证命令。");
        });

        // 注册游戏行为拦截事件
        registerGameplayBlockingEvents();

        // 注册服务器 Tick 用于位置冻结
        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
        
        LOGGER.info("SafeServer 初始化完成！共加载 {} 个密码。", playerPasswords.size());
    }

    private void onEndTick(MinecraftServer server) {
        this.serverInstance = server;
        stateManager.setServerInstance(server);
        stateManager.enforcePositionFreeze(); // 强制冻结认证中玩家的位置
    }

    private void registerGameplayBlockingEvents() {
        // 拦截命令（仅允许 /login 和 /setpassword）
        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> {
            ServerPlayerEntity player = source.getPlayer();
            if (player != null && stateManager.isPlayerAuthenticating(player.getUuid())) {
                String fullCommand = message.getContent().getString().trim();
                String commandRoot = fullCommand.split(" ", 2)[0];
                if (commandRoot.startsWith("/")) {
                    commandRoot = commandRoot.substring(1);
                }

                if (!commandRoot.equalsIgnoreCase("login") && !commandRoot.equalsIgnoreCase("setpassword")) {
                    player.sendMessage(Text.literal(SafeserverConstants.AUTH_COMMAND_MESSAGE), false);
                    Safeserver.LOGGER.debug("已阻止未认证玩家 {} 使用命令：{}", player.getName().getString(), fullCommand);
                }
            }
        });

        // 拦截破坏方块
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (stateManager.isPlayerAuthenticating(player.getUuid())) {
                player.sendMessage(Text.literal(SafeserverConstants.AUTH_INTERACT_MESSAGE), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // 拦截使用方块
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (stateManager.isPlayerAuthenticating(player.getUuid())) {
                player.sendMessage(Text.literal(SafeserverConstants.AUTH_INTERACT_MESSAGE), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // 拦截使用物品
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (stateManager.isPlayerAuthenticating(player.getUuid())) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // 拦截攻击实体
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (stateManager.isPlayerAuthenticating(player.getUuid())) {
                player.sendMessage(Text.literal(SafeserverConstants.AUTH_INTERACT_MESSAGE), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // 拦截交互实体（如骑乘、交易）
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (stateManager.isPlayerAuthenticating(player.getUuid())) {
                player.sendMessage(Text.literal(SafeserverConstants.AUTH_INTERACT_MESSAGE), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        LOGGER.info("已注册游戏行为拦截事件监听器。");
    }

    // 密码哈希（SHA-256）
    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            LOGGER.error("初始化 SHA-256 哈希失败。", e);
            return SafeserverConstants.HASHING_ERROR_VALUE;
        }
    }

    // 从文件加载密码
    private void loadPasswords() {
        if (Files.exists(passwordFilePath)) {
            try (BufferedReader reader = Files.newBufferedReader(passwordFilePath)) {
                Map<String, String> loadedMap = GSON.fromJson(reader, PASSWORD_MAP_TYPE);
                if (loadedMap != null) {
                    playerPasswords.putAll(loadedMap);
                    LOGGER.info("已从 {} 成功加载密码。", passwordFilePath);
                } else {
                    LOGGER.warn("密码文件 {} 为空或包含无效 JSON。", passwordFilePath);
                }
            } catch (IOException | com.google.gson.JsonSyntaxException e) {
                LOGGER.error("加载密码文件 {} 失败：{}", passwordFilePath, e.getMessage());
            }
        } else {
            LOGGER.info("未找到密码文件 {}，首次保存时将自动创建。", passwordFilePath);
        }
    }

    // 异步保存密码
    private void savePasswords() {
        CompletableFuture.runAsync(this::savePasswordsSync, fileExecutor)
            .exceptionally(throwable -> {
                LOGGER.error("异步保存密码失败", throwable);
                return null;
            });
    }

    private synchronized void savePasswordsSync() {
        try {
            Files.createDirectories(passwordFilePath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(passwordFilePath)) {
                GSON.toJson(playerPasswords, writer);
                LOGGER.info("已成功将密码保存至 {}", passwordFilePath);
            } catch (IOException e) {
                LOGGER.error("写入密码文件 {} 失败：{}", passwordFilePath, e.getMessage());
            }
        } catch (IOException e) {
            LOGGER.error("创建密码文件目录 {} 失败：{}", passwordFilePath.getParent(), e.getMessage());
        }
    }

    // 认证接口方法

    public boolean isPlayerAuthenticating(UUID playerUuid) {
        return stateManager.isPlayerAuthenticating(playerUuid);
    }

    public boolean hasPassword(UUID playerUuid) {
        return playerPasswords.containsKey(playerUuid.toString());
    }

    public boolean registerPlayer(UUID playerUuid, String password) {
        if (hasPassword(playerUuid)) {
            return false;
        }
        String hashedPassword = hashPassword(password);
        if (SafeserverConstants.HASHING_ERROR_VALUE.equals(hashedPassword)) {
            LOGGER.error("因哈希错误，无法注册玩家 {}。", playerUuid);
            return false;
        }
        playerPasswords.put(playerUuid.toString(), hashedPassword);
        savePasswords();
        boolean restored = stateManager.restorePlayerState(playerUuid);
        if (!restored) {
            LOGGER.warn("为玩家 {} 注册后无法完全恢复状态，但将继续执行。", playerUuid);
        }
        return true;
    }

    public boolean authenticatePlayer(UUID playerUuid, String password) {
        if (!hasPassword(playerUuid) || !isPlayerAuthenticating(playerUuid)) {
            return false;
        }
        String storedPasswordHash = playerPasswords.get(playerUuid.toString());
        String providedPasswordHash = hashPassword(password);

        if (storedPasswordHash.equals(providedPasswordHash)) {
            boolean restored = stateManager.restorePlayerState(playerUuid);
            if (!restored) {
                LOGGER.warn("为玩家 {} 登录后无法完全恢复状态，但将继续执行。", playerUuid);
            }
            return true;
        } else {
            return false;
        }
    }

    // 密码管理方法

    public boolean changePlayerPassword(UUID playerUuid, String oldPassword, String newPassword) {
        if (isPlayerAuthenticating(playerUuid)) {
            LOGGER.warn("玩家 {} 尚未完成认证，无法更改密码。", playerUuid);
            return false;
        }
        if (!hasPassword(playerUuid)) {
            LOGGER.warn("玩家 {} 尚未设置密码，无法更改。", playerUuid);
            return false;
        }

        String storedPasswordHash = playerPasswords.get(playerUuid.toString());
        String providedOldPasswordHash = hashPassword(oldPassword);

        if (!storedPasswordHash.equals(providedOldPasswordHash)) {
            return false;
        }

        String newPasswordHash = hashPassword(newPassword);
        if (SafeserverConstants.HASHING_ERROR_VALUE.equals(newPasswordHash)) {
            LOGGER.error("因哈希错误，无法更改玩家 {} 的密码。", playerUuid);
            return false;
        }

        playerPasswords.put(playerUuid.toString(), newPasswordHash);
        savePasswords();
        LOGGER.info("玩家 {} 成功更改密码。", playerUuid);
        return true;
    }

    public boolean resetAndSetPassword(UUID playerUuid, String newPassword) {
        if (isPlayerAuthenticating(playerUuid)) {
            LOGGER.warn("玩家 {} 尚未完成认证，无法重置密码。", playerUuid);
            return false;
        }
        if (!hasPassword(playerUuid)) {
            LOGGER.warn("玩家 {} 尚未设置密码，无法重置。", playerUuid);
            return false;
        }

        String newPasswordHash = hashPassword(newPassword);
        if (SafeserverConstants.HASHING_ERROR_VALUE.equals(newPasswordHash)) {
            LOGGER.error("因哈希错误，无法为玩家 {} 重置密码。", playerUuid);
            return false;
        }

        playerPasswords.put(playerUuid.toString(), newPasswordHash);
        savePasswords();
        LOGGER.info("玩家 {} 使用 /setpassword 命令成功重置密码。", playerUuid);
        return true;
    }

    public boolean resetPlayerPassword(UUID targetPlayerUuid) {
        String targetUuidString = targetPlayerUuid.toString();
        if (!playerPasswords.containsKey(targetUuidString)) {
            return false;
        }

        playerPasswords.remove(targetUuidString);
        savePasswords();

        ServerPlayerEntity targetPlayer = (this.serverInstance != null) ? this.serverInstance.getPlayerManager().getPlayer(targetPlayerUuid) : null;
        if (targetPlayer != null && !stateManager.isPlayerAuthenticating(targetPlayerUuid)) {
            stateManager.forcePlayerIntoAuthenticationState(targetPlayer);
        } else {
            LOGGER.info("玩家 {} 的密码已重置（离线状态），下次登录需重新设置。", targetPlayerUuid);
        }

        return true;
    }
}

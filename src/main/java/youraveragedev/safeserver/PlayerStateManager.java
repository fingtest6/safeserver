package youraveragedev.safeserver;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("safeserver-state-manager");
    
    private final Set<UUID> authenticatingPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, GameMode> originalGameModes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vec3d> initialPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vec3d> originalPositionsBeforeAuth = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> originalOpStatus = new ConcurrentHashMap<>();
    
    private MinecraftServer serverInstance;
    
    public void setServerInstance(MinecraftServer server) {
        this.serverInstance = server;
    }
    
    public boolean isPlayerAuthenticating(UUID playerUuid) {
        return authenticatingPlayers.contains(playerUuid);
    }
    
    public void applyAuthenticationState(ServerPlayerEntity player, MinecraftServer server) {
        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();
        
        authenticatingPlayers.add(playerUuid);
        originalGameModes.put(playerUuid, player.interactionManager.getGameMode());
        
        Vec3d originalPos = player.getPos();
        originalPositionsBeforeAuth.put(playerUuid, originalPos);
        
        Vec3d safePos = calculateSafeSpawnPosition(server, playerName);
        initialPositions.put(playerUuid, safePos);
        
        boolean wasOp = server.getPlayerManager().isOperator(player.getGameProfile());
        originalOpStatus.put(playerUuid, wasOp);
        if (wasOp) {
            server.getPlayerManager().removeFromOperators(player.getGameProfile());
            LOGGER.info("玩家 {} ({}) 的 OP 权限已临时移除，进入认证流程。", playerName, playerUuid);
        }
        
        player.changeGameMode(GameMode.SPECTATOR);
        player.networkHandler.requestTeleport(safePos.getX(), safePos.getY(), safePos.getZ(), 0, 0);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, true));
        
        LOGGER.info("已为玩家 {} 设置旁观模式和失明效果，进入认证流程。", playerName);
    }
    
    public void sendWelcomeMessages(ServerPlayerEntity player, boolean hasPassword) {
        if (hasPassword) {
            player.sendMessage(Text.literal(SafeserverConstants.WELCOME_BACK_MESSAGE), false);
        } else {
            player.sendMessage(Text.literal(SafeserverConstants.WELCOME_NEW_MESSAGE), false);
            player.sendMessage(Text.literal(SafeserverConstants.SET_PASSWORD_PROMPT), false);
        }
    }
    
    public boolean restorePlayerState(UUID playerUuid) {
        GameMode originalMode = originalGameModes.remove(playerUuid);
        initialPositions.remove(playerUuid);
        Vec3d originalPos = originalPositionsBeforeAuth.remove(playerUuid);
        Boolean wasOp = originalOpStatus.remove(playerUuid);
        
        ServerPlayerEntity player = (serverInstance != null) ? serverInstance.getPlayerManager().getPlayer(playerUuid) : null;
        boolean success = true;
        
        if (player != null) {
            String playerName = player.getName().getString();
            
            if (originalPos != null) {
                player.networkHandler.requestTeleport(originalPos.getX(), originalPos.getY(), originalPos.getZ(), player.getYaw(), player.getPitch());
                LOGGER.info("认证完成，已将玩家 {} 传送回原位置。", playerName);
            } else {
                success = restoreToSpawn(player) && success;
            }
            
            GameMode modeToRestore = determineGameModeToRestore(originalMode, playerName);
            if (modeToRestore != null) {
                player.changeGameMode(modeToRestore);
            } else {
                success = false;
            }
            
            if (player.hasStatusEffect(StatusEffects.BLINDNESS)) {
                player.removeStatusEffect(StatusEffects.BLINDNESS);
                LOGGER.info("认证完成，已移除玩家 {} 的失明效果。", playerName);
            }
            
            if (wasOp != null && wasOp && serverInstance != null) {
                serverInstance.getPlayerManager().addToOperators(player.getGameProfile());
                LOGGER.info("已恢复玩家 {} 的 OP 权限。", playerName);
            } else if (wasOp == null) {
                LOGGER.warn("玩家 {} (UUID {}) 的原始 OP 状态在恢复时丢失。", playerName, playerUuid);
            }
        } else {
            LOGGER.warn("无法恢复 UUID {} 的玩家状态（玩家不在线）。", playerUuid);
            cleanupPlayerState(playerUuid);
            success = false;
        }
        
        authenticatingPlayers.remove(playerUuid);
        return success;
    }
    
    public void cleanupPlayerState(UUID playerUuid) {
        authenticatingPlayers.remove(playerUuid);
        originalGameModes.remove(playerUuid);
        initialPositions.remove(playerUuid);
        originalPositionsBeforeAuth.remove(playerUuid);
        originalOpStatus.remove(playerUuid);
    }
    
    public void handlePlayerDisconnect(ServerPlayerEntity player, MinecraftServer server) {
        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();
        
        if (!authenticatingPlayers.contains(playerUuid)) {
            originalOpStatus.remove(playerUuid);
            return;
        }
        
        LOGGER.info("玩家 {} ({}) 在认证过程中断开连接，尝试在保存前恢复状态...", playerName, playerUuid);
        
        GameMode originalMode = originalGameModes.get(playerUuid);
        Vec3d originalPos = originalPositionsBeforeAuth.get(playerUuid);
        Boolean wasOp = originalOpStatus.get(playerUuid);
        
        boolean restoredSomething = false;
        try {
            if (originalPos != null) {
                player.networkHandler.requestTeleport(originalPos.getX(), originalPos.getY(), originalPos.getZ(), player.getYaw(), player.getPitch());
                LOGGER.info("已请求将玩家 {} 传送回位置 {}，以便断开前保存。", playerName, originalPos);
                restoredSomething = true;
            }
            
            GameMode modeToRestore = determineGameModeToRestore(originalMode, playerName);
            if (modeToRestore != null && player.interactionManager.getGameMode() != modeToRestore) {
                player.changeGameMode(modeToRestore);
                LOGGER.info("已恢复玩家 {} 的游戏模式为 {}。", playerName, modeToRestore);
                restoredSomething = true;
            }
            
            if (player.hasStatusEffect(StatusEffects.BLINDNESS)) {
                player.removeStatusEffect(StatusEffects.BLINDNESS);
                LOGGER.info("已移除玩家 {} 的失明效果。", playerName);
                restoredSomething = true;
            }
            
            if (wasOp != null && wasOp && !server.getPlayerManager().isOperator(player.getGameProfile())) {
                server.getPlayerManager().addToOperators(player.getGameProfile());
                LOGGER.info("已恢复玩家 {} 的 OP 权限。", playerName);
                restoredSomething = true;
            }
        } catch (Exception e) {
            LOGGER.error("尝试在断开连接时恢复玩家 {} 状态时发生错误：{}", playerName, e.getMessage(), e);
        }
        
        cleanupPlayerState(playerUuid);
        if (restoredSomething) {
            LOGGER.info("已清理玩家 {} 的认证状态（断开前已恢复部分状态）。", playerName);
        } else {
            LOGGER.warn("已清理玩家 {} 的认证状态（断开前状态恢复可能未完全执行）。", playerName);
        }
    }
    
    public void enforcePositionFreeze() {
        for (UUID playerUuid : Set.copyOf(authenticatingPlayers)) {
            ServerPlayerEntity player = (serverInstance != null) ? serverInstance.getPlayerManager().getPlayer(playerUuid) : null;
            Vec3d initialPos = initialPositions.get(playerUuid);
            
            if (player != null && initialPos != null) {
                if (player.getX() != initialPos.getX() || player.getY() != initialPos.getY() || player.getZ() != initialPos.getZ()) {
                    player.networkHandler.requestTeleport(initialPos.getX(), initialPos.getY(), initialPos.getZ(), player.getYaw(), player.getPitch());
                }
            } else if (player == null || initialPos == null) {
                LOGGER.warn("检测到玩家 UUID {} 的认证状态异常，正在清理...", playerUuid);
                cleanupPlayerState(playerUuid);
            }
        }
    }
    
    public void forcePlayerIntoAuthenticationState(ServerPlayerEntity player) {
        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();
        
        LOGGER.info("玩家 {} ({}) 密码已重置，强制进入认证状态。", playerName, playerUuid);
        
        authenticatingPlayers.add(playerUuid);
        originalGameModes.put(playerUuid, player.interactionManager.getGameMode());
        
        Vec3d originalPos = player.getPos();
        originalPositionsBeforeAuth.put(playerUuid, originalPos);
        
        Vec3d safePos = calculateSafeSpawnPosition(serverInstance, playerName);
        initialPositions.put(playerUuid, safePos);
        
        boolean wasOp = serverInstance.getPlayerManager().isOperator(player.getGameProfile());
        originalOpStatus.put(playerUuid, wasOp);
        if (wasOp) {
            serverInstance.getPlayerManager().removeFromOperators(player.getGameProfile());
            LOGGER.info("玩家 {} ({}) 在线时密码被重置，已临时移除 OP 权限。", playerName, playerUuid);
        }
        
        player.changeGameMode(GameMode.SPECTATOR);
        player.networkHandler.requestTeleport(safePos.getX(), safePos.getY(), safePos.getZ(), 0, 0);
        player.sendMessage(Text.literal(SafeserverConstants.RESET_PASSWORD_MESSAGE), false);
        player.sendMessage(Text.literal(SafeserverConstants.RESET_PASSWORD_PROMPT), false);
    }
    
    private Vec3d calculateSafeSpawnPosition(MinecraftServer server, String playerName) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld != null) {
            BlockPos spawnPos = overworld.getSpawnPos();
            int safeY = overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawnPos.getX(), spawnPos.getZ());
            return new Vec3d(
                spawnPos.getX() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET, 
                safeY + SafeserverConstants.SAFE_SPAWN_Y_OFFSET, 
                spawnPos.getZ() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET
            );
        } else {
            LOGGER.warn("无法获取主世界以确定玩家 {} 的出生点，使用备用坐标。", playerName);
            return new Vec3d(
                SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET, 
                SafeserverConstants.FALLBACK_Y_COORDINATE, 
                SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET
            );
        }
    }
    
    private boolean restoreToSpawn(ServerPlayerEntity player) {
        String playerName = player.getName().getString();
        LOGGER.warn("无法找到玩家 {} 的原始位置，正在将其传送至出生点。", playerName);
        
        if (serverInstance != null) {
            ServerWorld overworld = serverInstance.getWorld(World.OVERWORLD);
            if (overworld != null) {
                BlockPos spawnPos = overworld.getSpawnPos();
                int spawnY = overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawnPos.getX(), spawnPos.getZ());
                player.networkHandler.requestTeleport(
                    spawnPos.getX() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET, 
                    spawnY, 
                    spawnPos.getZ() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET, 
                    overworld.getSpawnAngle(), 
                    0.0f
                );
                return true;
            } else {
                LOGGER.error("无法获取主世界，无法将玩家 {} 传送至出生点！", playerName);
                return false;
            }
        } else {
            LOGGER.error("无法获取服务器实例，无法将玩家 {} 传送至出生点！", playerName);
            return false;
        }
    }
    
    private GameMode determineGameModeToRestore(GameMode originalMode, String playerName) {
        if (originalMode != null) {
            if (originalMode == GameMode.SPECTATOR) {
                if (serverInstance != null) {
                    GameMode defaultMode = serverInstance.getDefaultGameMode();
                    LOGGER.info("原始游戏模式为旁观模式，为玩家 {} 恢复为服务器默认模式（{}）。", playerName, defaultMode);
                    return defaultMode;
                } else {
                    LOGGER.error("无法获取服务器实例，无法确定玩家 {} 的默认游戏模式，恢复可能失败。", playerName);
                    return null;
                }
            } else {
                LOGGER.info("已为玩家 {} 恢复原始游戏模式（{}）。", playerName, originalMode);
                return originalMode;
            }
        } else {
            LOGGER.warn("无法找到玩家 {} 的原始游戏模式，将使用服务器默认模式。", playerName);
            if (serverInstance != null) {
                return serverInstance.getDefaultGameMode();
            } else {
                LOGGER.error("无法获取服务器实例，无法确定玩家 {} 的默认游戏模式，恢复可能失败。", playerName);
                return null;
            }
        }
    }
}

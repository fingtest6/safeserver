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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

public class Safeserver implements ModInitializer {
	public static final String MOD_ID = "safeserver";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Password storage
	private final Map<String, String> playerPasswords = new HashMap<>();
	private final Set<UUID> authenticatingPlayers = new HashSet<>();
	// State storage for authenticating players
	private final Map<UUID, GameMode> originalGameModes = new HashMap<>();
	private final Map<UUID, Vec3d> initialPositions = new HashMap<>(); // This will now store the SAFE position for freezing
	private final Map<UUID, Vec3d> originalPositionsBeforeAuth = new HashMap<>(); // Store original position here
	private final Map<UUID, Boolean> originalOpStatus = new HashMap<>();

	// Gson instance for JSON handling
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// Define the type for deserialization
	private static final Type PASSWORD_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
	// Path to the password storage file
	private Path passwordFilePath;

	private MinecraftServer serverInstance; // Store server instance

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Initializing Safeserver...");

		// Determine password file path
		passwordFilePath = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("passwords.json");

		// Load passwords from file
		loadPasswords();

		// Player Join Logic
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.player;
			UUID playerUuid = player.getUuid();
			String playerUuidString = playerUuid.toString();
			String playerName = player.getName().getString();

			LOGGER.info("Player {} ({}) joined. Checking authentication...", playerName, playerUuidString);

			// Check and store OP status, then de-op if necessary
			boolean wasOp = server.getPlayerManager().isOperator(player.getGameProfile());
			originalOpStatus.put(playerUuid, wasOp);
			if (wasOp) {
				server.getPlayerManager().removeFromOperators(player.getGameProfile());
				LOGGER.info("Temporarily de-opped player {} ({}) for authentication.", playerName, playerUuidString);
			}

			if (playerPasswords.containsKey(playerUuidString)) {
				// Returning player needing login
				if (!authenticatingPlayers.contains(playerUuid)) { // Avoid re-adding if already processing
					LOGGER.info("Player {} needs to log in.", playerName);
					authenticatingPlayers.add(playerUuid);
					originalGameModes.put(playerUuid, player.interactionManager.getGameMode());
					Vec3d originalPos = player.getPos(); // Store original position
					originalPositionsBeforeAuth.put(playerUuid, originalPos);
					// Calculate safe Y position
					ServerWorld overworld = server.getWorld(World.OVERWORLD);
					int safeY = (overworld != null) ? overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 0, 0) : 64;
					Vec3d safePos = new Vec3d(0.5, safeY + 1.0, 0.5); // Centered on block, 1 block above surface
					initialPositions.put(playerUuid, safePos); // Store safe position for freezing
					player.changeGameMode(GameMode.SPECTATOR);
					// Teleport to safe location immediately
					player.networkHandler.requestTeleport(safePos.getX(), safePos.getY(), safePos.getZ(), 0, 0);
					player.sendMessage(Text.literal("Welcome back! Please login using /login <password>"), false);
				}
			} else {
				// First join, needing password set
				if (!authenticatingPlayers.contains(playerUuid)) { // Avoid re-adding if already processing
					LOGGER.info("Player {} needs to set a password.", playerName);
					authenticatingPlayers.add(playerUuid);
					originalGameModes.put(playerUuid, player.interactionManager.getGameMode());
					Vec3d originalPos = player.getPos(); // Store original position
					originalPositionsBeforeAuth.put(playerUuid, originalPos);
					// Calculate safe Y position
					ServerWorld overworld = server.getWorld(World.OVERWORLD);
					int safeY = (overworld != null) ? overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 0, 0) : 64;
					Vec3d safePos = new Vec3d(0.5, safeY + 1.0, 0.5); // Centered on block, 1 block above surface
					initialPositions.put(playerUuid, safePos); // Store safe position for freezing
					player.changeGameMode(GameMode.SPECTATOR);
					// Teleport to safe location immediately
					player.networkHandler.requestTeleport(safePos.getX(), safePos.getY(), safePos.getZ(), 0, 0);
					player.sendMessage(Text.literal("Welcome! This server requires authentication."), false);
					player.sendMessage(Text.literal("Please set your password using /setpassword <password>"), false);
				}
			}
		});

		// Player Disconnect Logic
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayerEntity player = handler.player;
			UUID playerUuid = player.getUuid();
			// Remove player from auth process and cleanup state
			if (authenticatingPlayers.remove(playerUuid)) {
				originalGameModes.remove(playerUuid);
				initialPositions.remove(playerUuid); // Remove safe freeze position
				originalPositionsBeforeAuth.remove(playerUuid); // Remove original position
				originalOpStatus.remove(playerUuid);
				LOGGER.info("Player {} ({}) disconnected during authentication. Cleaned up state.", player.getName().getString(), playerUuid);
			}

			// De-op player on disconnect as a safety measure if they were OP
			// Note: Check the stored status *before* removing it, but act after removal from auth set if applicable
			// We check the actual server state at disconnect time
			if (this.serverInstance != null && this.serverInstance.getPlayerManager().isOperator(player.getGameProfile())) {
				this.serverInstance.getPlayerManager().removeFromOperators(player.getGameProfile());
				LOGGER.info("De-opped player {} ({}) on disconnect for security.", player.getName().getString(), playerUuid);
			}
			// Remove OP status tracking if they disconnect while *not* authenticating (already logged in)
			originalOpStatus.remove(playerUuid);
		});

		// Register Commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			AuthCommands.registerCommands(dispatcher, this);
			LOGGER.info("Registered authentication commands.");
		});

		// Register Gameplay Blocking Events
		registerGameplayBlockingEvents();

		// Register Server Tick for position freeze
		ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
		
		LOGGER.info("Safeserver Initialized! Loaded {} passwords.", playerPasswords.size());
	}

	private void onEndTick(MinecraftServer server) {
		this.serverInstance = server; // Store the server instance

		// Iterate over players needing authentication
		for (UUID playerUuid : new HashSet<>(authenticatingPlayers)) { // Iterate over a copy
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
			Vec3d initialPos = initialPositions.get(playerUuid);
			
			if (player != null && initialPos != null) {
				// Keep teleporting them back to their initial spot (the safe spot) using network handler
				if (player.getX() != initialPos.getX() || player.getY() != initialPos.getY() || player.getZ() != initialPos.getZ()) {
					// requestTeleport handles sending the S2C packet
					player.networkHandler.requestTeleport(initialPos.getX(), initialPos.getY(), initialPos.getZ(), player.getYaw(), player.getPitch());
				}
			} else {
				// Clean up if player somehow disappeared or state is inconsistent
				LOGGER.warn("Cleaning up inconsistent authentication state for UUID: {}", playerUuid);
				authenticatingPlayers.remove(playerUuid);
				initialPositions.remove(playerUuid);
				originalGameModes.remove(playerUuid);
				originalPositionsBeforeAuth.remove(playerUuid); // Clean up original position too
				originalOpStatus.remove(playerUuid);
			}
		}
	}

	private void registerGameplayBlockingEvents() {
		// Block commands other than /login and /setpassword
		ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> {
			ServerPlayerEntity player = source.getPlayer();
			if (player != null && isPlayerAuthenticating(player.getUuid())) {
				String fullCommand = message.getContent().getString().trim();
				String commandRoot = fullCommand.split(" ", 2)[0];
				if (commandRoot.startsWith("/")) {
					commandRoot = commandRoot.substring(1);
				}

				if (!commandRoot.equalsIgnoreCase("login") && !commandRoot.equalsIgnoreCase("setpassword")) {
					player.sendMessage(Text.literal("You must authenticate first. Use /login or /setpassword."), false);
					Safeserver.LOGGER.debug("Blocked command attempt \"{}\" for unauthenticated player {}", fullCommand, player.getName().getString());
				}
			}
		});

		// Block Block Breaking
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (isPlayerAuthenticating(player.getUuid())) {
				player.sendMessage(Text.literal("You must authenticate to interact."), true); // Send to action bar
				return ActionResult.FAIL;
			}
			return ActionResult.PASS;
		});

		// Block Block Usage
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (isPlayerAuthenticating(player.getUuid())) {
				player.sendMessage(Text.literal("You must authenticate to interact."), true);
				return ActionResult.FAIL;
			}
			return ActionResult.PASS;
		});

		// Block Item Usage
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (isPlayerAuthenticating(player.getUuid())) {
				return ActionResult.FAIL;
			}
			return ActionResult.PASS;
		});

		// Block Attacking Entities
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (isPlayerAuthenticating(player.getUuid())) {
                player.sendMessage(Text.literal("You must authenticate to interact."), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

		// Block Using Entities (e.g., trading, mounting)
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (isPlayerAuthenticating(player.getUuid())) {
                player.sendMessage(Text.literal("You must authenticate to interact."), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

		// Note: Movement blocking is more complex and might require cancelling packets
        // or using server ticks to teleport players back. For now, we focus on interactions.
		LOGGER.info("Registered gameplay blocking event listeners.");
	}

	// Helper method to hash passwords (using SHA-256)
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
			LOGGER.error("Failed to initialize SHA-256 for password hashing.", e);
			// Fallback or critical error handling - maybe prevent login?
			// For now, we'll return a predictable (insecure) value in case of error,
			// but ideally, the server should probably stop or prevent logins.
			return "HASHING_ERROR";
		}
	}

	// --- Password Persistence --- 

	private void loadPasswords() {
        if (Files.exists(passwordFilePath)) {
            try (BufferedReader reader = Files.newBufferedReader(passwordFilePath)) {
                Map<String, String> loadedMap = GSON.fromJson(reader, PASSWORD_MAP_TYPE);
                if (loadedMap != null) {
                    playerPasswords.putAll(loadedMap);
                    LOGGER.info("Successfully loaded passwords from {}", passwordFilePath);
                } else {
					LOGGER.warn("Password file {} was empty or invalid JSON.", passwordFilePath);
				}
            } catch (IOException | com.google.gson.JsonSyntaxException e) {
                LOGGER.error("Failed to load passwords from {}: {}", passwordFilePath, e.getMessage());
            }
        } else {
            LOGGER.info("Password file not found at {}, creating a new one on first save.", passwordFilePath);
            // Optionally create the directory structure here if needed upon first load attempt
            // try { Files.createDirectories(passwordFilePath.getParent()); } catch (IOException e) { ... }
        }
    }

    private synchronized void savePasswords() {
        try {
            // Ensure parent directory exists
            Files.createDirectories(passwordFilePath.getParent());
            // Write the current map to the file
            try (BufferedWriter writer = Files.newBufferedWriter(passwordFilePath)) {
                GSON.toJson(playerPasswords, writer);
                LOGGER.info("Successfully saved passwords to {}", passwordFilePath);
            } catch (IOException e) {
                LOGGER.error("Failed to write passwords to {}: {}", passwordFilePath, e.getMessage());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create directory for password file {}: {}", passwordFilePath.getParent(), e.getMessage());
        }
    }

	// --- Authentication Logic --- 

	public boolean isPlayerAuthenticating(UUID playerUuid) {
		return authenticatingPlayers.contains(playerUuid);
	}

	public boolean hasPassword(UUID playerUuid) {
		return playerPasswords.containsKey(playerUuid.toString());
	}

	public boolean registerPlayer(UUID playerUuid, String password) {
		if (hasPassword(playerUuid)) {
			return false; // Already registered
		}
		String hashedPassword = hashPassword(password);
		if ("HASHING_ERROR".equals(hashedPassword)) {
			LOGGER.error("Could not register player {} due to hashing error.", playerUuid);
			return false;
		}
		playerPasswords.put(playerUuid.toString(), hashedPassword);
		
		savePasswords(); // Save passwords after registration

		// Restore original state and remove from auth list
		boolean restored = restorePlayerState(playerUuid);
		if(!restored) {
			LOGGER.warn("Could not fully restore state for {} after registration, but proceeding.", playerUuid);
		}
		authenticatingPlayers.remove(playerUuid); // Ensure removed even if state restoration had issues

		return true;
	}

	public boolean authenticatePlayer(UUID playerUuid, String password) {
		if (!hasPassword(playerUuid) || !isPlayerAuthenticating(playerUuid)) {
			return false; // Not registered or not in auth process
		}
		String storedPasswordHash = playerPasswords.get(playerUuid.toString());
		String providedPasswordHash = hashPassword(password);

		if (storedPasswordHash.equals(providedPasswordHash)) {
			// Restore original state and remove from auth list
			boolean restored = restorePlayerState(playerUuid);
			if(!restored) {
				LOGGER.warn("Could not fully restore state for {} after login, but proceeding.", playerUuid);
			}
			authenticatingPlayers.remove(playerUuid); // Ensure removed even if state restoration had issues
			return true;
		} else {
			return false; // Incorrect password
		}
	}

	private boolean restorePlayerState(UUID playerUuid) {
		GameMode originalMode = originalGameModes.remove(playerUuid);
		initialPositions.remove(playerUuid); // Stop freezing position (at safe spot)
		Vec3d originalPos = originalPositionsBeforeAuth.remove(playerUuid); // Get original position
		Boolean wasOp = originalOpStatus.remove(playerUuid); // Get and remove original OP status

		// Get player instance using the stored server instance
		ServerPlayerEntity player = (this.serverInstance != null) ? this.serverInstance.getPlayerManager().getPlayer(playerUuid) : null;

		boolean success = true; // Assume success unless something fails

		if (player != null) {
			String playerName = player.getName().getString();

			// --- Bug Fix: Check if player logged out during authentication --- 
			boolean loggedOutDuringAuth = false;
			if (originalMode == GameMode.SPECTATOR && originalPos != null && this.serverInstance != null) {
				// Calculate what the safe position would be right now
				ServerWorld overworld = this.serverInstance.getWorld(World.OVERWORLD);
				int safeY = (overworld != null) ? overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 0, 0) : 64;
				Vec3d currentSafePos = new Vec3d(0.5, safeY + 1.0, 0.5);
				// Check if the stored original position is very close to the safe position
				if (originalPos.squaredDistanceTo(currentSafePos) < 0.1) {
					loggedOutDuringAuth = true;
				}
			}

			if (loggedOutDuringAuth) {
				LOGGER.info("Player {} logged out during authentication. Restoring to default spawn/gamemode.", playerName);
				// Restore to default spawn and gamemode
				ServerWorld overworld = this.serverInstance.getWorld(World.OVERWORLD);
				if (overworld != null) {
					net.minecraft.util.math.BlockPos spawnPos = overworld.getSpawnPos();
					int spawnY = overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawnPos.getX(), spawnPos.getZ());
					player.networkHandler.requestTeleport(spawnPos.getX() + 0.5, spawnY, spawnPos.getZ() + 0.5, overworld.getSpawnAngle(), 0.0f);
					player.changeGameMode(this.serverInstance.getDefaultGameMode());
					LOGGER.info("Set gamemode to default ({}) for player {}.", this.serverInstance.getDefaultGameMode(), playerName);
				} else {
					LOGGER.error("Could not get Overworld to restore player {} to spawn!", playerName);
					success = false;
				}
			} else {
				// Standard restore logic
				// Teleport back to original location *before* changing gamemode
				if (originalPos != null) {
					// Use requestTeleport for reliability across dimensions/loads
					// Keep the player's current yaw/pitch from spectator mode
					player.networkHandler.requestTeleport(originalPos.getX(), originalPos.getY(), originalPos.getZ(), player.getYaw(), player.getPitch()); 
					LOGGER.info("Teleported player {} back to original location after authentication.", playerName);
				} else {
					LOGGER.warn("Could not find original position for UUID {} (Player: {}) during state restoration.", playerUuid, playerName);
					// Not necessarily a failure of the whole process, maybe they disconnected weirdly before?
					// Proceed with restoring other states.
				}

				if (originalMode != null) {
					player.changeGameMode(originalMode);
					LOGGER.info("Restored original gamemode ({}) for player {}.", originalMode, playerName);
				} else {
					LOGGER.warn("Could not find original gamemode for UUID {} (Player: {}). Setting to default.", playerUuid, playerName);
					// Fallback to default gamemode if original is missing for some reason
					if(this.serverInstance != null) player.changeGameMode(this.serverInstance.getDefaultGameMode());
					success = false;
				}
			}

			// Re-op if they were originally OP (applies to both restore paths)
			if (wasOp != null && wasOp) {
				// Ensure server instance is available before trying to re-op
				if (this.serverInstance != null) {
					this.serverInstance.getPlayerManager().addToOperators(player.getGameProfile());
					LOGGER.info("Restored OP status for player {}.", playerName);
				} else {
					LOGGER.error("Cannot restore OP status for {} because server instance is null.", playerName);
					success = false; // Indicate state wasn't fully restored
				}
			} else if (wasOp == null) {
				// This case might happen if the player disconnects *after* authenticating
                // but before the originalOpStatus was naturally removed (e.g., server crash).
                // It's likely safe to ignore, but we log a warning.
                LOGGER.warn("Original OP status for player {} (UUID {}) was unexpectedly missing during state restoration.", playerName, playerUuid);
			}

		} else {
			LOGGER.warn("Could not restore state for UUID {} (Player not found online). Original mode found: {}, Was OP found: {}",
				playerUuid, (originalMode != null), (wasOp != null));
			// Clean up any remaining state just in case
			originalGameModes.remove(playerUuid);
			initialPositions.remove(playerUuid);
			originalPositionsBeforeAuth.remove(playerUuid); // Ensure cleanup here too
			originalOpStatus.remove(playerUuid);
			success = false;
		}

		return success; // Indicate if state was fully restored
	}

	// --- New/Modified Password Management Methods ---

	/**
	 * Changes the password for the currently logged-in player.
	 * Requires the correct old password.
	 */
	public boolean changePlayerPassword(UUID playerUuid, String oldPassword, String newPassword) {
		if (isPlayerAuthenticating(playerUuid)) {
			LOGGER.warn("Attempt to change password while player {} is still authenticating.", playerUuid);
			return false; // Should not change password while authenticating
		}
		if (!hasPassword(playerUuid)) {
			LOGGER.warn("Attempt to change password for player {} who has no password set.", playerUuid);
			return false; // Should have a password to change it
		}

		String storedPasswordHash = playerPasswords.get(playerUuid.toString());
		String providedOldPasswordHash = hashPassword(oldPassword);

		if (!storedPasswordHash.equals(providedOldPasswordHash)) {
			return false; // Incorrect old password
		}

		String newPasswordHash = hashPassword(newPassword);
		if ("HASHING_ERROR".equals(newPasswordHash)) {
			LOGGER.error("Could not change password for player {} due to hashing error.", playerUuid);
			return false;
		}

		playerPasswords.put(playerUuid.toString(), newPasswordHash);
		savePasswords();
		LOGGER.info("Player {} successfully changed their password.", playerUuid);
		return true;
	}

	/**
	 * Resets the password for a target player (removes their entry).
	 * This forces them to set a new password on next join.
	 */
	public boolean resetPlayerPassword(UUID targetPlayerUuid) {
		String targetUuidString = targetPlayerUuid.toString();
		if (!playerPasswords.containsKey(targetUuidString)) {
			return false; // Player doesn't have a password / isn't registered with the mod
		}

		playerPasswords.remove(targetUuidString);
		savePasswords();

		// If the target player is currently online, force them back into authentication state
		ServerPlayerEntity targetPlayer = (this.serverInstance != null) ? this.serverInstance.getPlayerManager().getPlayer(targetPlayerUuid) : null;
		if (targetPlayer != null && !isPlayerAuthenticating(targetPlayerUuid)) {
			LOGGER.info("Forcing player {} ({}) into authentication state after password reset.", targetPlayer.getName().getString(), targetPlayerUuid);
			authenticatingPlayers.add(targetPlayerUuid);
			originalGameModes.put(targetPlayerUuid, targetPlayer.interactionManager.getGameMode());
			// Store current position as the original position
            Vec3d originalPos = targetPlayer.getPos(); 
			originalPositionsBeforeAuth.put(targetPlayerUuid, originalPos);
			// Define and store safe position for freezing
            // Calculate safe Y position
			ServerWorld overworld = this.serverInstance.getWorld(World.OVERWORLD);
			int safeY = (overworld != null) ? overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 0, 0) : 64;
            Vec3d safePos = new Vec3d(0.5, safeY + 1.0, 0.5); // Centered on block, 1 block above surface
            initialPositions.put(targetPlayerUuid, safePos);
            // Store current OP status and de-op if needed
            boolean wasOp = this.serverInstance.getPlayerManager().isOperator(targetPlayer.getGameProfile());
			originalOpStatus.put(targetPlayerUuid, wasOp);
			if (wasOp) {
				this.serverInstance.getPlayerManager().removeFromOperators(targetPlayer.getGameProfile());
                LOGGER.info("Temporarily de-opped player {} ({}) due to password reset while online.", targetPlayer.getName().getString(), targetPlayerUuid);
			}

			targetPlayer.changeGameMode(GameMode.SPECTATOR);
            // Teleport to safe position
            targetPlayer.networkHandler.requestTeleport(safePos.getX(), safePos.getY(), safePos.getZ(), 0, 0);
            targetPlayer.sendMessage(Text.literal("Your password has been reset by an administrator."), false);
            targetPlayer.sendMessage(Text.literal("Please set a new password using /setpassword <password> <password>"), false);
		} else {
            LOGGER.info("Password reset for offline player UUID {}. They will need to set a new password on next login.", targetPlayerUuid);
        }

		return true;
	}
}
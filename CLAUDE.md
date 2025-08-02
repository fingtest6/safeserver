# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Safeserver is a Minecraft Fabric mod that adds mandatory password authentication to servers. Players must set a password on first join and authenticate on subsequent joins. The mod enforces strict interaction blocking until authentication is complete.

## Development Commands

### Building and Testing
- `./gradlew build` - Build the mod (assembles and tests)
- `./gradlew assemble` - Assemble the mod JAR without running tests
- `./gradlew clean` - Clean build artifacts
- `./gradlew test` - Run the test suite
- `./gradlew check` - Run all verification tasks

### Development and Testing
- `./gradlew runServer` - Start development server
- `./gradlew runClient` - Start development client
- `./gradlew runDatagen` - Run data generation
- `./gradlew jar` - Build the mod JAR
- `./gradlew remapJar` - Remap the built JAR to intermediary mappings

### IDE Setup
- `./gradlew genEclipseRuns` - Generate Eclipse run configurations
- `./gradlew vscode` - Generate VSCode launch configurations

## Architecture

### Core Components

**Main Class**: `youraveragedev.safeserver.Safeserver`
- Implements `ModInitializer` 
- Manages player authentication state using UUID-based tracking
- Handles player join/disconnect events with position and gamemode restoration
- Integrates with Fabric API events for interaction blocking

**Authentication System**:
- Password storage in `config/safeserver/passwords.json` (SHA-256 hashed)
- State tracking maps: `authenticatingPlayers`, `originalGameModes`, `originalPositions`, `originalOpStatus`
- Position freezing at world spawn with spectator mode during authentication
- Automatic OP status management (temporary removal during auth)

**Command System**: `youraveragedev.safeserver.command.AuthCommands`
- `/setpassword <password> <password>` - First-time password setting
- `/login <password>` - Subsequent authentication
- `/changepassword <old> <new> <new>` - Change existing password
- `/resetpassword <player>` - OP-only password reset

### Security Features

- **Position Protection**: Players teleported to safe spawn location during auth, preventing coordinate leakage
- **Interaction Blocking**: All world interactions disabled until authentication (block breaking/placing, item use, entity interaction)
- **Command Restriction**: Only auth commands allowed during authentication process
- **Visual Blocking**: Blindness effect applied during authentication
- **State Restoration**: Complete restoration of original position, gamemode, and OP status after auth

### File Structure

```
src/main/java/youraveragedev/safeserver/
├── Safeserver.java              # Main mod class and authentication logic
└── command/
    └── AuthCommands.java        # Command registration and handlers

src/main/resources/
├── fabric.mod.json             # Mod metadata
└── safeserver.mixins.json      # Mixin configuration
```

### Key Technical Details

- **Fabric Version**: 1.21.4 (check `gradle.properties` for current versions)
- **Java Version**: 21 (required)
- **Password Security**: SHA-256 hashing with secure storage
- **Event Handling**: Uses Fabric API callbacks for player events and interaction blocking
- **State Management**: Comprehensive tracking of player state during authentication process
- **Position Handling**: Uses `requestTeleport()` for reliable cross-dimensional teleportation

When modifying authentication logic, ensure proper cleanup of all tracking maps and consider both online and offline player scenarios for password reset functionality.
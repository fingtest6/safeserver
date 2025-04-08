# Safeserver

A simple Fabric mod for Minecraft that adds mandatory password authentication to your server, enhancing security.

## Features

*   **Password Protection:** Players must set a password on their first join and log in on subsequent joins.
*   **Interaction Blocking:** Prevents unauthenticated players from breaking/placing blocks, using items/entities, or interacting with the world.
*   **Command Restriction:** Blocks all commands except `/login` and `/setpassword` until the player is authenticated.
*   **Secure Storage:** Passwords are securely hashed (SHA-256) and stored in a JSON file (`config/safeserver/passwords.json`).
*   **OP Safety:**
    *   Temporarily removes OP status from players upon joining until they authenticate.
    *   Removes OP status from players upon disconnecting as a safety measure.
    *   Restores OP status after successful authentication if the player was originally OP.
*   **Position Freeze & Safety:** Players are placed in Spectator mode and teleported to a safe, fixed location (0, calculated surface Y, 0) upon joining if authentication is needed. They are kept at this location until authenticated, preventing coordinate leakage. Their original position is restored upon successful login.

## Commands

*   `/setpassword <password> <password>`
    *   Sets your initial password upon first joining the server.
    *   Requires typing the password twice for confirmation.
    *   Only usable when required (first join).
*   `/login <password>`
    *   Logs you into the server with your existing password.
    *   Only usable when required (subsequent joins).
*   `/changepassword <oldPassword> <newPassword> <newPassword>`
    *   Allows an authenticated player to change their own password.
    *   Requires the old password and confirmation of the new password.
*   `/resetpassword <playerName>`
    *   **OP Only (Level 2+):** Resets the password for the specified player.
    *   Forces the target player to set a new password using `/setpassword` on their next join (or immediately if they are currently online).

## Installation

1.  Ensure you have the [Fabric Loader](https://fabricmc.net/use/) installed.
2.  Download the `Safeserver` mod JAR file.
3.  Place the JAR file into your server's `mods` folder.
4.  Restart your server.

The mod will automatically generate the necessary configuration file upon first load. 
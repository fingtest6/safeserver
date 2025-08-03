package youraveragedev.safeserver;

public final class SafeserverConstants {
    
    public static final int MIN_PASSWORD_LENGTH = 4;
    public static final double SAFE_SPAWN_Y_OFFSET = 1.0;
    public static final double SAFE_SPAWN_CENTER_OFFSET = 0.5;
    public static final double FALLBACK_Y_COORDINATE = 65.0;
    
    public static final String AUTH_INTERACT_MESSAGE = "You must authenticate to interact.";
    public static final String AUTH_COMMAND_MESSAGE = "You must authenticate first. Use /login or /setpassword.";
    public static final String WELCOME_BACK_MESSAGE = "Welcome back! Please login using /login <password>";
    public static final String WELCOME_NEW_MESSAGE = "Welcome! This server requires authentication.";
    public static final String SET_PASSWORD_PROMPT = "Please set your password using /setpassword <password>";
    public static final String RESET_PASSWORD_MESSAGE = "Your password has been reset by an administrator.";
    public static final String RESET_PASSWORD_PROMPT = "Please set a new password using /setpassword <password> <password>";
    
    public static final String PASSWORD_MISMATCH_ERROR = "Passwords do not match. Please try again.";
    public static final String PASSWORD_LENGTH_ERROR = "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.";
    public static final String PASSWORD_SET_SUCCESS = "Password set successfully! You are now logged in.";
    public static final String PASSWORD_RESET_SUCCESS = "Password reset successfully!";
    public static final String PASSWORD_CHANGE_SUCCESS = "Password changed successfully.";
    public static final String LOGIN_SUCCESS = "Login successful!";
    public static final String INCORRECT_PASSWORD_ERROR = "Incorrect password.";
    public static final String ALREADY_AUTHENTICATED_ERROR = "You are already authenticated or not required to log in now.";
    public static final String NO_PASSWORD_SET_ERROR = "You don't have a password set yet. Use /setpassword first.";
    public static final String ALREADY_HAS_PASSWORD_ERROR = "You already have a password set. Use /login instead.";
    public static final String NO_PASSWORD_NEEDED_ERROR = "You don't need to set a password right now.";
    public static final String MUST_BE_LOGGED_IN_ERROR = "You must be logged in to change your password.";
    public static final String CHECK_OLD_PASSWORD_ERROR = "Failed to change password. Check your old password.";
    public static final String PLAYER_ONLY_COMMAND_ERROR = "This command can only be run by a player.";
    public static final String CONTACT_ADMIN_ERROR = "Please contact an admin.";
    
    public static final String HASHING_ERROR_VALUE = "HASHING_ERROR";
    
    private SafeserverConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
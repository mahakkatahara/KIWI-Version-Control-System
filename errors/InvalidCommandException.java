package errors;

public class InvalidCommandException extends KiwiException {
    public InvalidCommandException(String command) {
        super(command.contains(" ") ? command : "Unknown or invalid command: '" + command + "'. Try 'kiwi init', 'kiwi add', 'kiwi commit', 'kiwi status', or 'kiwi log'.");
    }
}


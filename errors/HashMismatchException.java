package errors;

public class HashMismatchException extends KiwiException {
    public HashMismatchException(String details) {
        super("Hash mismatch: " + details);
    }

    public HashMismatchException(String expected, String actual) {
        super("Hash mismatch! Expected: " + expected + ", Actual: " + actual);
    }
}


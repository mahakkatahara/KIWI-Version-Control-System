package src;

import errors.*;
import java.io.*;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Stream;
import utils.*;

class Helper {

    protected static String normalizePath(String path) {
        try {
            Path repoRoot = Paths.get("").toAbsolutePath().normalize();
            Path targetPath = Paths.get(path).toAbsolutePath().normalize();
            if (targetPath.startsWith(repoRoot)) {
                Path rel = repoRoot.relativize(targetPath);
                String relStr = rel.toString().replace("\\", "/").trim();
                return relStr.isEmpty() ? "." : relStr;
            }
            return targetPath.toString().replace("\\", "/").trim();
        } catch (Exception e) {
            return path.replace("\\", "/").trim();
        }
    }

    protected static String updateIndex(String content, String filename, String hash) {
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        String normalizedFile = normalizePath(filename);

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split(" ", 2);
            if (parts.length < 2) {
                continue;
            }

            String existingFile = normalizePath(parts[0]);

            if (existingFile.equals(normalizedFile)) {
                sb.append(normalizedFile).append(" ").append(hash).append("\n");
                replaced = true;
            } else {
                sb.append(existingFile).append(" ").append(parts[1].trim()).append("\n");
            }
        }

        if (!replaced) {
            sb.append(normalizedFile).append(" ").append(hash).append("\n");
        }
        return sb.toString();
    }

    protected static boolean isFileInIndex(File indexFile, String normalizedFile) throws IOException {
        if (!indexFile.exists()) {
            return false;
        }
        List<String> lines = Files.readAllLines(indexFile.toPath());
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(" ", 2);
            if (parts.length < 2) {
                continue;
            }
            String existing = normalizePath(parts[0]);
            if (existing.equals(normalizedFile)) {
                return true;
            }
        }
        return false;
    }

    protected static void removeFileFromIndex(File indexFile, String normalizedFile) throws IOException {
        if (!indexFile.exists()) {
            return;
        }
        List<String> lines = Files.readAllLines(indexFile.toPath());
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(" ", 2);
            if (parts.length < 2) {
                continue;
            }
            String existing = normalizePath(parts[0]);
            if (!existing.equals(normalizedFile)) {
                sb.append(existing).append(" ").append(parts[1].trim()).append("\n");
            }
        }
        Files.writeString(indexFile.toPath(), sb.toString());
    }

    protected static void removeDeletedFilesFromIndex() throws IndexCorruptedException {
        File indexFile = new File(".kiwi/index/stage.index");
        if (!indexFile.exists()) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(indexFile.toPath());
            StringBuilder updatedContent = new StringBuilder();

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(" ", 2);
                if (parts.length < 2) {
                    continue;
                }

                String filename = normalizePath(parts[0]);
                String hash = parts[1].trim();
                File f = new File(filename);

                // If file still exists, keep it in the index.
                // If deleted, we remove it from stage.index without deleting objects in .kiwi/objects.
                if (f.exists()) {
                    updatedContent.append(filename).append(" ").append(hash).append("\n");
                }
            }
            Files.writeString(indexFile.toPath(), updatedContent.toString());

        } catch (IOException e) {
            throw new IndexCorruptedException(e.getMessage());
        }
    }

    protected static void addSingleFile(String filename) throws FileStagingException, ObjectWriteException {
        String normalizedFile = normalizePath(filename);

        if (normalizedFile.equals(".kiwi") || normalizedFile.startsWith(".kiwi/") || normalizedFile.startsWith(".kiwi\\")) {
            throw new FileStagingException(filename, "Cannot stage KIWI internal directory.");
        }
        if (normalizedFile.equals(".git") || normalizedFile.startsWith(".git/") || normalizedFile.startsWith(".git\\")) {
            throw new FileStagingException(filename, "Cannot stage Git repository files.");
        }

        File file = new File(filename);
        File indexFile = new File(".kiwi/index/stage.index");

        // If file does not exist on disk, check if it was staged previously (stage deletion)
        if (!file.exists()) {
            try {
                if (indexFile.exists() && isFileInIndex(indexFile, normalizedFile)) {
                    removeFileFromIndex(indexFile, normalizedFile);
                    return;
                }
            } catch (IOException ignored) {
            }
            throw new FileStagingException(filename, "File does not exist.");
        }

        if (file.isDirectory()) {
            throw new FileStagingException(filename, "File is a directory. Use 'kiwi add .' to stage directories.");
        }

        try {
            String hash = HashUtils.getFileHash(file);

            if (!indexFile.exists()) {
                indexFile.getParentFile().mkdirs();
                indexFile.createNewFile();
            }

            String existingContent = "";
            if (indexFile.length() > 0) {
                existingContent = Files.readString(indexFile.toPath());
            }

            File objectsDir = new File(".kiwi/objects");
            if (!objectsDir.exists()) {
                objectsDir.mkdirs();
            }

            File objectFile = new File(objectsDir, hash);
            try {
                Files.copy(file.toPath(), objectFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new ObjectWriteException(hash, e.getMessage());
            }

            String updatedContent = updateIndex(existingContent, normalizedFile, hash);
            Files.writeString(indexFile.toPath(), updatedContent);

        } catch (IOException | NoSuchAlgorithmException e) {
            throw new FileStagingException(filename, e.getMessage());
        }
    }

    protected static void addAllFilesRecursively(File dir) throws FileStagingException, ObjectWriteException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            String name = file.getName();
            if (name.equals(".kiwi") || name.startsWith(".")) {
                continue;
            }

            if (file.isDirectory()) {
                addAllFilesRecursively(file);
            } else {
                addSingleFile(file.getPath());
            }
        }
    }

    protected static String formatTimestamp(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(millis));
    }

    protected static long getCommitTimestamp(File folder) {
        String name = folder.getName().trim();
        String dateStr = "";
        File messageFile = new File(folder, "message.txt");

        if (messageFile.exists()) {
            String[] parts = name.split(" ", 2);
            if (parts.length >= 2) {
                dateStr = parts[1].trim();
            }
        } else {
            // Older commit backward compatibility: date is in folder name
            if (name.length() >= 19) {
                dateStr = name.substring(name.length() - 19).trim();
            }
        }

        if (!dateStr.isEmpty()) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                return sdf.parse(dateStr).getTime();
            } catch (Exception ignored) {
            }
        }
        return folder.lastModified();
    }

    protected static String addContent(File folder) throws IOException {
        StringBuilder sb = new StringBuilder();
        File[] files = folder.listFiles();
        if (files == null) {
            return "";
        }

        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            if (file.isDirectory()) {
                sb.append(addContent(file));
            } else {
                sb.append(file.getName());
            }
        }
        return sb.toString();
    }

    protected static void copyFolder(File src, File dest) throws IOException {
        Path source = src.toPath();
        Path target = dest.toPath();

        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(path -> {
                try {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative);

                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}

class VCSHANDLER extends Helper {

    public static void initRepository() throws RepoAlreadyExistsException, KiwiException {
        File kiwiDir = new File(".kiwi");

        if (kiwiDir.exists()) {
            throw new RepoAlreadyExistsException("Repository already initialized in this directory!");
        }

        try {
            if (!kiwiDir.mkdir()) {
                throw new KiwiException("Failed to create .kiwi directory!");
            }

            new File(".kiwi/objects").mkdirs();
            new File(".kiwi/commits").mkdirs();
            new File(".kiwi/index").mkdirs();

            new File(".kiwi/index/stage.index").createNewFile();
            new File(".kiwi/HEAD").createNewFile();

            System.out.println(Colors.GREEN + "Initialized KIWI repository" + Colors.RESET);

        } catch (IOException e) {
            throw new KiwiException("Could not initialize repository: " + e.getMessage());
        }
    }

    public static void add(String[] args)
            throws RepoNotInitializedException, FileStagingException, ObjectWriteException, IndexCorruptedException {

        if (!new File(".kiwi").exists()) {
            throw new RepoNotInitializedException();
        }

        if (args.length < 2) {
            System.out.println(Colors.YELLOW + "Usage: kiwi add <filename> [more_files] OR kiwi add ." + Colors.RESET);
            return;
        }

        if (args[1].equals(".")) {
            removeDeletedFilesFromIndex();
            addAllFilesRecursively(new File("."));
            System.out.println(Colors.GREEN + "Staging completed." + Colors.RESET);
            return;
        }

        for (int i = 1; i < args.length; i++) {
            addSingleFile(args[i]);
        }
        System.out.println(Colors.GREEN + "Staging completed." + Colors.RESET);
    }

    public static void status(File dir, Map<String, String> indexMap,
            ArrayList<String> deletedfiles, ArrayList<String> modified, ArrayList<String> untracked) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            String name = file.getName();
            if (name.equals(".kiwi") || name.startsWith(".")) {
                continue;
            }

            if (file.isDirectory()) {
                status(file, indexMap, deletedfiles, modified, untracked);
            } else {
                try {
                    String normalizedPath = normalizePath(file.getPath());
                    String hash = HashUtils.getFileHash(file);

                    if (indexMap.containsKey(normalizedPath)) {
                        String storedHash = indexMap.get(normalizedPath);
                        if (!storedHash.equals(hash)) {
                            modified.add(normalizedPath);
                        }
                    } else {
                        untracked.add(normalizedPath);
                    }

                } catch (IOException | NoSuchAlgorithmException e) {
                    System.err.println("[KIWI ERROR] Could not hash file: " + file.getName());
                }
            }
        }
    }

    public static void status() throws RepoNotInitializedException, IndexCorruptedException {
        if (!new File(".kiwi").exists()) {
            throw new RepoNotInitializedException();
        }

        File indexFile = new File(".kiwi/index/stage.index");
        Map<String, String> indexMap = new HashMap<>();

        if (indexFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(indexFile.toPath());
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] parts = line.split(" ", 2);
                    if (parts.length < 2) {
                        continue;
                    }
                    String filename = normalizePath(parts[0]);
                    String hash = parts[1].trim();
                    indexMap.put(filename, hash);
                }
            } catch (IOException e) {
                throw new IndexCorruptedException(e.getMessage());
            }
        }

        ArrayList<String> modified = new ArrayList<>();
        ArrayList<String> untracked = new ArrayList<>();
        ArrayList<String> deletedfiles = new ArrayList<>();

        for (String filename : indexMap.keySet()) {
            File f = new File(filename);
            if (!f.exists()) {
                deletedfiles.add(filename);
            }
        }

        status(new File("."), indexMap, deletedfiles, modified, untracked);

        if (indexMap.isEmpty() && untracked.isEmpty() && modified.isEmpty() && deletedfiles.isEmpty()) {
            System.out.println(Colors.RED + "No files have been staged yet!" + Colors.RESET);
            return;
        }

        System.out.println();
        System.out.println(Colors.CYAN + "======================================" + Colors.RESET);

        if (modified.isEmpty() && untracked.isEmpty() && deletedfiles.isEmpty()) {
            System.out.println(Colors.GREEN + "ALL GOOD" + Colors.RESET);
        }

        if (!modified.isEmpty()) {
            System.out.println(Colors.YELLOW + "\nModified files:" + Colors.RESET);
            for (String file : modified) {
                System.out.println(Colors.YELLOW + "   " + file + Colors.RESET);
            }
        }

        if (!deletedfiles.isEmpty()) {
            System.out.println(Colors.BLUE + "\nDeleted files:" + Colors.RESET);
            for (String file : deletedfiles) {
                System.out.println(Colors.BLUE + "   " + file + Colors.RESET);
            }
        }

        if (!untracked.isEmpty()) {
            System.out.println(Colors.RED + "\nUntracked files:" + Colors.RESET);
            for (String file : untracked) {
                System.out.println(Colors.RED + "   " + file + Colors.RESET);
            }
        }

        System.out.println(Colors.CYAN + "======================================" + Colors.RESET);
    }

    public static void commit(String[] args)
            throws RepoNotInitializedException, CommitException {

        if (!new File(".kiwi").exists()) {
            throw new RepoNotInitializedException();
        }

        if (args.length < 2) {
            throw new CommitException("Commit message not provided! Usage: kiwi commit <message>");
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (message.isEmpty()) {
            throw new CommitException("Commit message cannot be empty!");
        }

        File indexFile = new File(".kiwi/index/stage.index");
        if (!indexFile.exists() || indexFile.length() == 0) {
            throw new CommitException("No files staged for commit. Use 'kiwi add <file>' first.");
        }

        try {
            String currentIndexContent = Files.readString(indexFile.toPath()).trim();
            if (currentIndexContent.isEmpty()) {
                throw new CommitException("No files staged for commit. Use 'kiwi add <file>' first.");
            }

            // Check against the latest commit to prevent empty commits when nothing has changed
            File commitsDir = new File(".kiwi/commits");
            File[] commitFolders = commitsDir.listFiles(File::isDirectory);
            if (commitFolders != null && commitFolders.length > 0) {
                Arrays.sort(commitFolders, (f1, f2) -> Long.compare(getCommitTimestamp(f2), getCommitTimestamp(f1)));
                File latestCommit = commitFolders[0];
                File latestIndexFile = new File(latestCommit, "index/stage.index");
                if (latestIndexFile.exists()) {
                    String latestIndexContent = Files.readString(latestIndexFile.toPath()).trim();
                    if (currentIndexContent.equals(latestIndexContent)) {
                        throw new CommitException("No changes staged for commit. Stage new or modified files first.");
                    }
                }
            }

            long timestampMillis = System.currentTimeMillis();
            String readableTime = formatTimestamp(timestampMillis);

            String content = addContent(new File(".kiwi/objects"));
            String dataToHash = content + "\n" + readableTime + "\n" + message;
            String commitHash = CommitHashUtils.generateCommitHash(dataToHash);
            String commitName = commitHash + " " + readableTime;

            File commitDir = new File(".kiwi/commits/" + commitName);
            commitDir.mkdirs();

            File commitObjectsDir = new File(commitDir, "objects");
            File commitIndexDir = new File(commitDir, "index");

            commitObjectsDir.mkdirs();
            commitIndexDir.mkdirs();

            copyFolder(new File(".kiwi/objects"), commitObjectsDir);
            copyFolder(new File(".kiwi/index"), commitIndexDir);

            File messageFile = new File(commitDir, "message.txt");
            Files.writeString(messageFile.toPath(), message);

            File headFile = new File(".kiwi/HEAD");
            Files.writeString(headFile.toPath(), commitHash);

            System.out.println(Colors.GREEN + "[" + commitHash.substring(0, 7) + "] " + message + Colors.RESET);

        } catch (KiwiException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitException(e.getMessage());
        }
    }

    public static void log() throws RepoNotInitializedException {
        if (!new File(".kiwi").exists()) {
            throw new RepoNotInitializedException();
        }

        try {
            File commitsDir = new File(".kiwi/commits");

            File[] commitFolders = commitsDir.listFiles(File::isDirectory);
            if (commitFolders == null || commitFolders.length == 0) {
                System.out.println(Colors.YELLOW + "No commits yet!" + Colors.RESET);
                return;
            }

            // Chronological sort: newest first
            Arrays.sort(commitFolders, (f1, f2) -> {
                int cmp = Long.compare(getCommitTimestamp(f2), getCommitTimestamp(f1));
                if (cmp != 0) {
                    return cmp;
                }
                return f2.getName().compareTo(f1.getName());
            });

            System.out.println(Colors.CYAN + "\n ============ KIWI COMMIT HISTORY ============" + Colors.RESET);

            for (File folder : commitFolders) {

                String name = folder.getName().trim();
                String hash = "";
                String message = "";
                String date = "";

                File messageFile = new File(folder, "message.txt");
                if (messageFile.exists()) {
                    String[] parts = name.split(" ", 2);
                    if (parts.length < 2) {
                        continue;
                    }
                    hash = parts[0].trim();
                    date = parts[1].trim();
                    try {
                        message = Files.readString(messageFile.toPath()).trim();
                    } catch (IOException e) {
                        message = "";
                    }
                } else {
                    // Backward compatibility: older commits that still store message in folder name
                    int firstSpace = name.indexOf(' ');
                    if (firstSpace == -1) {
                        continue;
                    }
                    hash = name.substring(0, firstSpace).trim();
                    String rest = name.substring(firstSpace + 1).trim();

                    if (rest.length() > 20 && rest.charAt(rest.length() - 20) == ' ') {
                        message = rest.substring(0, rest.length() - 20).trim();
                        date = rest.substring(rest.length() - 19).trim();
                    } else {
                        String[] parts = name.split(" ", 3);
                        if (parts.length < 3) {
                            continue;
                        }
                        hash = parts[0].trim();
                        message = parts[1].trim();
                        date = parts[2].trim();
                    }
                }

                System.out.println("Hash:     " + Colors.CYAN + hash + Colors.RESET);
                System.out.println("Timestamp:    " + Colors.MAGENTA + date + Colors.RESET);
                System.out.println("Message:  " + Colors.YELLOW + message + Colors.RESET);
                System.out.println(Colors.CYAN + " =============================================\n" + Colors.RESET);
            }

        } catch (Exception e) {
            System.out.println(Colors.RED + "[KIWI ERROR] Could not read log: " + e.getMessage() + Colors.RESET);
        }
    }
}

public class KIWI {
    private static void handleAddFile(VCSHANDLER vcs, Scanner scanner) throws KiwiException {
        System.out.print("Enter filename: ");
        if (!scanner.hasNextLine()) {
            return;
        }
        String filename = scanner.nextLine().trim();

        if (filename.isEmpty()) {
            System.out.println(Colors.RED + "Filename cannot be empty!" + Colors.RESET);
            return;
        }

        Path repoRoot = Paths.get("").toAbsolutePath().normalize();
        Path filePath;
        try {
            filePath = repoRoot.resolve(filename).normalize();
        } catch (InvalidPathException e) {
            System.out.println(Colors.RED + "Invalid filename or path: " + e.getMessage() + Colors.RESET);
            return;
        }

        if (!filePath.startsWith(repoRoot) || filePath.equals(repoRoot)) {
            System.out.println(Colors.RED + "Invalid path: Directory traversal outside repository is not allowed." + Colors.RESET);
            return;
        }

        String relPath = repoRoot.relativize(filePath).toString().replace("\\", "/");
        if (relPath.equals(".kiwi") || relPath.startsWith(".kiwi/") ||
            relPath.equals(".git") || relPath.startsWith(".git/")) {
            System.out.println(Colors.RED + "Cannot modify internal repository files or directories." + Colors.RESET);
            return;
        }

        if (!new File(".kiwi").exists()) {
            throw new RepoNotInitializedException();
        }

        File targetFile = filePath.toFile();

        if (targetFile.exists()) {
            if (targetFile.isDirectory()) {
                System.out.println(Colors.RED + "Path is a directory, not a file. Use Option 3 ('Add All Files') or 'kiwi add .' to stage directories." + Colors.RESET);
                return;
            }

            System.out.print("File already exists. Do you want to stage it directly? (Y/N): ");
            if (!scanner.hasNextLine()) {
                return;
            }
            String answer = scanner.nextLine().trim();
            if (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes")) {
                vcs.add(new String[]{"add", relPath});
            }
        } else {
            System.out.print("File does not exist. Do you want to create it? (Y/N): ");
            if (!scanner.hasNextLine()) {
                return;
            }
            String answer = scanner.nextLine().trim();
            if (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes")) {
                try {
                    File parentDir = targetFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        if (!parentDir.mkdirs()) {
                            System.err.println(Colors.RED + "Failed to create directory: " + parentDir.getPath() + Colors.RESET);
                            return;
                        }
                    }

                    boolean created = targetFile.createNewFile();
                    if (!created && !targetFile.exists()) {
                        System.err.println(Colors.RED + "Failed to create file: " + relPath + Colors.RESET);
                        return;
                    }

                    System.out.print("Enter file content: ");
                    if (!scanner.hasNextLine()) {
                        return;
                    }
                    String content = scanner.nextLine();

                    Files.writeString(filePath, content);
                    System.out.println(Colors.GREEN + "File '" + relPath + "' created successfully." + Colors.RESET);

                    vcs.add(new String[]{"add", relPath});
                    System.out.println(Colors.GREEN + "File '" + relPath + "' staged successfully." + Colors.RESET);
                } catch (IOException e) {
                    System.err.println(Colors.RED + "File creation error: " + e.getMessage() + Colors.RESET);
                } catch (SecurityException e) {
                    System.err.println(Colors.RED + "Permission denied: " + e.getMessage() + Colors.RESET);
                }
            }
        }
    }

    public static void runInteractiveMenu(VCSHANDLER vcs) {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("KIWI \u2013 VERSION CONTROL SYSTEM");
            System.out.println();
            System.out.println("1. Initialize Repository");
            System.out.println("2. Add a File");
            System.out.println("3. Add All Files");
            System.out.println("4. Check Status");
            System.out.println("5. Commit Changes");
            System.out.println("6. View Commit History");
            System.out.println("7. Exit");
            System.out.println();
            System.out.print("Enter your choice: ");

            if (!scanner.hasNextLine()) {
                break;
            }
            String choice = scanner.nextLine().trim();

            if (choice.equals("7")) {
                System.out.println("Exiting KIWI. Goodbye!");
                break;
            }

            try {
                switch (choice) {
                    case "1" -> vcs.initRepository();
                    case "2" -> handleAddFile(vcs, scanner);
                    case "3" -> vcs.add(new String[]{"add", "."});
                    case "4" -> vcs.status();
                    case "5" -> {
                        System.out.print("Enter commit message: ");
                        if (!scanner.hasNextLine()) {
                            return;
                        }
                        String message = scanner.nextLine().trim();
                        vcs.commit(new String[]{"commit", message});
                    }
                    case "6" -> vcs.log();
                    default -> System.out.println(Colors.RED + "Invalid choice. Please enter a number between 1 and 7." + Colors.RESET);
                }
            } catch (KiwiException e) {
                System.err.println(Colors.RED + "[KIWI ERROR] " + e.getMessage() + Colors.RESET);
            } catch (Exception e) {
                System.err.println(Colors.RED + "[SYSTEM ERROR] " + e.getMessage() + Colors.RESET);
            }

            System.out.println();
        }
    }

    public static void main(String[] args) {
        VCSHANDLER vcs = new VCSHANDLER();
        if (args.length == 0) {
            runInteractiveMenu(vcs);
            return;
        }

        try {
            String command = args[0];

            switch (command) {
                case "init" ->
                    vcs.initRepository();
                case "status" ->
                    vcs.status();
                case "add" ->
                    vcs.add(args);
                case "commit" ->
                    vcs.commit(args);
                case "log" ->
                    vcs.log();
                default ->
                    throw new InvalidCommandException(command);
            }
        } catch (KiwiException e) {
            System.err.println(Colors.RED + "[KIWI ERROR] " + e.getMessage() + Colors.RESET);
        } catch (Exception e) {
            System.err.println(Colors.RED + "[SYSTEM ERROR] " + e.getMessage() + Colors.RESET);
        }
    }
}

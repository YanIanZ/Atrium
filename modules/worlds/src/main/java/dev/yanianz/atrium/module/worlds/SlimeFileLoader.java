package dev.yanianz.atrium.module.worlds;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Atrium's own file loader for Advanced Slime Paper ({@code <directory>/<world>.slime}). Every world name is
 * validated and the resolved path must stay inside the directory, so a name can never escape it. Saves are
 * written to a temporary file and moved into place. Deleting is not offered (Atrium never deletes worlds).
 * Hook class: loaded only when the ASP API exists.
 */
final class SlimeFileLoader implements SlimeLoader {
    static final String SUFFIX = ".slime";
    private final Path directory;

    SlimeFileLoader(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        if (Files.exists(this.directory) && !Files.isDirectory(this.directory)) {
            throw new NotDirectoryException(this.directory.toString());
        }
        Files.createDirectories(this.directory);
    }

    Path directory() {
        return directory;
    }

    private Path file(String worldName) throws IOException {
        Path file = WorldNames.fileIn(directory, worldName, SUFFIX);
        if (file == null) {
            throw new IOException("Invalid slime world name: " + worldName);
        }
        return file;
    }

    @Override
    public byte[] readWorld(String worldName) throws UnknownWorldException, IOException {
        Path file = file(worldName);
        if (!Files.isRegularFile(file)) {
            throw new UnknownWorldException(worldName);
        }
        return Files.readAllBytes(file);
    }

    @Override
    public boolean worldExists(String worldName) throws IOException {
        return WorldNames.valid(worldName) && Files.isRegularFile(file(worldName));
    }

    @Override
    public List<String> listWorlds() throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - SUFFIX.length());
                if (WorldNames.valid(name) && Files.isRegularFile(file)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    @Override
    public void saveWorld(String worldName, byte[] serializedWorld) throws IOException {
        Path file = file(worldName);
        Path temp = Files.createTempFile(directory, worldName + ".", ".tmp");
        try {
            Files.write(temp, serializedWorld);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void deleteWorld(String worldName) throws UnknownWorldException, IOException {
        throw new IOException("Atrium's slime file loader does not delete worlds");
    }
}

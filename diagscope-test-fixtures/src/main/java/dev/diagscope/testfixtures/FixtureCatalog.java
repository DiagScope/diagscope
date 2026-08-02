package dev.diagscope.testfixtures;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Comparator;

public final class FixtureCatalog {
    private FixtureCatalog() {}

    public static Path copyTo(Path targetRoot, String fixtureName) {
        String resource = "/fixtures/" + fixtureName;
        try {
            var url = FixtureCatalog.class.getResource(resource);
            if (url == null) throw new IllegalArgumentException("Unknown fixture: " + fixtureName);
            Path source;
            FileSystem fileSystem = null;
            if ("jar".equals(url.getProtocol())) {
                try {
                    fileSystem = FileSystems.newFileSystem(url.toURI(), java.util.Map.of());
                } catch (FileSystemAlreadyExistsException ignored) {
                    fileSystem = FileSystems.getFileSystem(java.net.URI.create(url.toString().split("!", 2)[0]));
                }
                source = fileSystem.getPath(resource);
            } else {
                source = Path.of(url.toURI());
            }
            Path target = targetRoot.resolve(fixtureName);
            copyTree(source, target);
            if (fileSystem != null && fileSystem.isOpen()) fileSystem.close();
            return target;
        } catch (IOException | URISyntaxException exception) {
            throw new IllegalStateException("Unable to copy fixture " + fixtureName, exception);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (var path : paths.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}

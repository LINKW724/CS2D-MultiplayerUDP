package cs2d.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves the actual Java executable, including a jpackage private runtime. */
final class JavaRuntimeLocator {
    private JavaRuntimeLocator() {
    }

    static Path locate() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank())
            throw new IllegalStateException("java.home is unavailable");
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        Path executable = Path.of(javaHome, "bin", executableName).toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable))
            throw new IllegalStateException("Java executable not found: " + executable);
        return executable;
    }
}

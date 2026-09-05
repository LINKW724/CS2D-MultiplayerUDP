package cs2d.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds the isolated PCM worker command independently from the app launcher. */
final class PcmWorkerProcessFactory {
    private final Path javaExecutable;

    PcmWorkerProcessFactory(Path javaExecutable) {
        this.javaExecutable = javaExecutable;
    }

    static PcmWorkerProcessFactory systemRuntime() {
        return new PcmWorkerProcessFactory(JavaRuntimeLocator.locate());
    }

    List<String> command(List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path", ""));
        command.addAll(arguments);
        return List.copyOf(command);
    }

    Process start(List<String> arguments) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command(arguments));
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        return builder.start();
    }
}

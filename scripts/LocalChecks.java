import java.nio.file.*;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;

/** Uses the JDK compiler API when this environment lacks the javac executable. */
class LocalChecks {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path output = root.resolve("core/domain/build/offline-checks");
        Files.createDirectories(output);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Full JDK required");
        int result = compiler.run(null, null, null, "-source", "17", "-target", "17", "-d", output.toString(),
            root.resolve("core/domain/src/main/java/app/televault/domain/AuthProtocol.java").toString(),
            root.resolve("core/domain/src/test/java/app/televault/domain/AuthProtocolChecks.java").toString());
        if (result != 0) throw new IllegalStateException("Domain compilation failed");
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
            loader.loadClass("app.televault.domain.AuthProtocolChecks").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
        }
    }
}

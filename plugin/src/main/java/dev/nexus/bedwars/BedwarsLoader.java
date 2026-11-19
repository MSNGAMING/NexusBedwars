package dev.nexus.bedwars;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

import java.util.List;

/**
 * Resolves runtime libraries from Maven Central when the plugin is loaded.
 * Keeps the jar small and lets admins swap JDBC drivers without recompiling.
 */
public final class BedwarsLoader implements PluginLoader {

    /** Keep in sync with build.gradle.kts. */
    private static final List<String> LIBRARIES = List.of(
            "com.zaxxer:HikariCP:6.2.1",
            "org.xerial:sqlite-jdbc:3.47.1.0",
            "com.mysql:mysql-connector-j:9.1.0",
            "org.postgresql:postgresql:42.7.4",
            "redis.clients:jedis:5.2.0"
    );

    @Override
    public void classloader(PluginClasspathBuilder builder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", "https://repo.maven.apache.org/maven2/").build());
        for (String coordinate : LIBRARIES) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
        }
        builder.addLibrary(resolver);
    }
}

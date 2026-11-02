package dev.yanianz.atrium.core.boot;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.util.List;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Resolves Atrium's runtime libraries from Maven Central at startup instead of shading them.
 * Keep this list in sync with gradle/libs.versions.toml.
 */
public final class AtriumLoader implements PluginLoader {
    static final List<String> LIBRARIES = List.of(
            "com.zaxxer:HikariCP:7.1.0",
            "com.github.ben-manes.caffeine:caffeine:3.3.1",
            "org.spongepowered:configurate-yaml:4.2.0",
            "org.xerial:sqlite-jdbc:3.54.1.0",
            "org.mariadb.jdbc:mariadb-java-client:4.0.0");

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        for (String coordinates : LIBRARIES) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinates), null));
        }
        classpathBuilder.addLibrary(resolver);
    }
}

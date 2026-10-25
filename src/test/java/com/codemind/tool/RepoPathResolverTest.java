package com.codemind.tool;

import com.codemind.service.IndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoPathResolverTest {

    private IndexService indexService;
    private RepoPathResolver resolver;

    @BeforeEach
    void setUp() {
        indexService = mock(IndexService.class);
        resolver = new RepoPathResolver(indexService);
    }

    @Test
    void explicitNonDotPathIsReturnedAsIs() {
        assertThat(resolver.resolve("/srv/repo")).isEqualTo("/srv/repo");
    }

    @Test
    void dotResolvesToActiveIndexRoot() {
        Path active = Path.of("C:/workspace/proj");
        when(indexService.currentRoot()).thenReturn(active);

        assertThat(resolver.resolve(".")).isEqualTo(active.toString());
    }

    @Test
    void dotSlashAlsoResolvesToActiveIndexRoot() {
        Path active = Path.of("C:/workspace/proj");
        when(indexService.currentRoot()).thenReturn(active);

        assertThat(resolver.resolve("./")).isEqualTo(active.toString());
    }

    @Test
    void emptySuppliedResolvesToActiveIndexRoot() {
        Path active = Path.of("C:/workspace/proj");
        when(indexService.currentRoot()).thenReturn(active);

        assertThat(resolver.resolve("")).isEqualTo(active.toString());
    }

    @Test
    void blankSuppliedTrimsAndResolvesToActiveIndexRoot() {
        Path active = Path.of("C:/workspace/proj");
        when(indexService.currentRoot()).thenReturn(active);

        assertThat(resolver.resolve("   ")).isEqualTo(active.toString());
    }

    @Test
    void nullSuppliedResolvesToActiveIndexRoot() {
        Path active = Path.of("C:/workspace/proj");
        when(indexService.currentRoot()).thenReturn(active);

        assertThat(resolver.resolve(null)).isEqualTo(active.toString());
    }

    @Test
    void dotFallsBackToUserDirWhenNothingIndexed() {
        when(indexService.currentRoot()).thenReturn(null);
        String expected = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();

        assertThat(resolver.resolve(".")).isEqualTo(expected);
    }

    @Test
    void nullFallsBackToUserDirWhenNothingIndexed() {
        when(indexService.currentRoot()).thenReturn(null);
        String expected = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();

        assertThat(resolver.resolve(null)).isEqualTo(expected);
    }
}

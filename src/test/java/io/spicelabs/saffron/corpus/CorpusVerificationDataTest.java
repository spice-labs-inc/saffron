package io.spicelabs.saffron.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The committed corpus-verification files are also read by the black-box integration
 * tests of the downstream CLI, which pair their cases with these unit tests by the
 * {@code id} field. Every file must carry {@code id} equal to its {@code imageBasename}, and
 * ids must be unique. Always runs; needs no corpus image.
 */
class CorpusVerificationDataTest {

    private static final Path VERIFICATION_DIR = Path.of("src/test/resources/corpus-verification");
    private static final Gson GSON = new Gson();

    @Test
    void everyVerificationFileHasItsImageBasenameAsId() throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int count = 0;
        try (Stream<Path> files = Files.list(VERIFICATION_DIR)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json")).sorted()::iterator) {
                count++;
                try (Reader r = Files.newBufferedReader(p)) {
                    CorpusTestData.CorpusImageData data = GSON.fromJson(r, CorpusTestData.CorpusImageData.class);
                    if (data.id == null || data.id.isBlank()) {
                        problems.add(p + ": missing id (expected \"" + data.imageBasename + "\")");
                        continue;
                    }
                    if (!data.id.equals(data.imageBasename)) {
                        problems.add(p + ": id \"" + data.id + "\" != imageBasename \"" + data.imageBasename + "\"");
                    }
                    if (!seen.add(data.id)) {
                        problems.add(p + ": duplicate id \"" + data.id + "\"");
                    }
                }
            }
        }
        assertThat(count).as("verification files").isGreaterThan(0);
        assertThat(problems).isEmpty();
    }
}

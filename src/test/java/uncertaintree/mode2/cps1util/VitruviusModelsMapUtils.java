// Copied unchanged except for the package from UncertainTreeDev master @ 7b067a6,
// BrakeCaseStudy/Maven_Project/vsum/src/test/java/tools/vitruv/methodologisttemplate/vsum/UncertainTreeTests/utils/VitruviusModelsMapUtils.java
package uncertaintree.mode2.cps1util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for creating Vitruvius vsum metadata files (e.g. models.models).
 */
public class VitruviusModelsMapUtils {

	private static final String VSUM_DIR = "vsum";
	private static final String MODELS_LIST_FILE = "models.models";
	private static final String EXCLUDED_FILE = "test_project.marker_vitruv";

	/**
	 * Creates the {@code models.models} file in {@code vsumStateTestExecutionDir/vsum/}.
	 * Assumes concrete model files are already present in {@code vsumStateTestExecutionDir}.
	 * Writes one file URI per line for each regular file in that directory, excluding
	 * {@value #EXCLUDED_FILE}. Uses Vitruv's file URI format ({@code file:/path}).
	 *
	 * @param vsumStateTestExecutionDir directory containing the copied model files (e.g. brakesystem.brakesystem, *.cad)
	 * @throws IOException if listing the directory or writing the file fails
	 */
	public static void createModelsModelsFile(Path vsumStateTestExecutionDir) throws IOException {
		Path vsumDir = vsumStateTestExecutionDir.resolve(VSUM_DIR);
		Files.createDirectories(vsumDir);

		List<String> lines = new ArrayList<>();
		try (var stream = Files.list(vsumStateTestExecutionDir)) {
			for (Path entry : stream.toList()) {
				if (!Files.isRegularFile(entry)) {
					continue;
				}
				if (EXCLUDED_FILE.equals(entry.getFileName().toString())) {
					continue;
				}
				lines.add(toVitruvFileUri(entry.toAbsolutePath()));
			}
		}
		Path modelsFile = vsumDir.resolve(MODELS_LIST_FILE);
		Files.writeString(modelsFile, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
	}

	/**
	 * Normalizes a path to Vitruv's file URI form (file:/path) so models.models is not duplicated when Vitruv persists.
	 */
	private static String toVitruvFileUri(Path path) {
		String u = path.toUri().toString();
		if (u.startsWith("file:///")) {
			return "file:" + u.substring(7);
		}
		if (u.startsWith("file://")) {
			return "file:" + u.substring(6);
		}
		return u;
	}
}

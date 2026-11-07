// Copied unchanged except for the package from UncertainTreeDev master @ 7b067a6,
// BrakeCaseStudy/Maven_Project/vsum/src/test/java/tools/vitruv/methodologisttemplate/vsum/UncertainTreeTests/utils/EnsureTestDirectoryStructureUtils.java
package uncertaintree.mode2.cps1util;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;


public class EnsureTestDirectoryStructureUtils {

	
	/** File name that is not copied when copying original state to vsum root; it is handled separately (e.g. into vsum/). */
	private static final String CORRESPONDENCE_FILE_NAME = "correspondences.correspondence";

	

	/**
	 * Ensures the given directory exists and is empty. If it already exists, its contents are
	 * deleted recursively, then the directory is (re)created.
	 *
	 * @param path the directory path to ensure (created empty)
	 */
	public static void ensureDirectoryExistsAndClear(Path path) throws IOException {
		if (Files.exists(path)) {
			deleteRecursively(path);
		}
		Files.createDirectories(path);
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (Files.isDirectory(path)) {
			try (Stream<Path> entries = Files.list(path)) {
				for (Path entry : entries.toList()) {
					deleteRecursively(entry);
				}
			}
		}
		Files.delete(path);
	}

	/**
	 * Recursively copies the contents of {@code source} into {@code target}. Directories are
	 * created; files are copied with {@link StandardCopyOption#REPLACE_EXISTING}. Skips any
	 * file (not directory) whose name is in {@code skipFileNames}; useful when some files
	 * are handled elsewhere (e.g. correspondence copied into a subfolder).
	 *
	 * @param source       the source directory
	 * @param target       the target directory
	 * @param skipFileNames file names to skip when encountered as files (optional)
	 */
	public static void copyDirectoryContents(Path source, Path target, String... skipFileNames) throws IOException {
		Set<String> skip = skipFileNames.length == 0 ? Set.of() : Set.copyOf(Arrays.asList(skipFileNames));
		try (Stream<Path> stream = Files.list(source)) {
			for (Path entry : stream.toList()) {
				String name = entry.getFileName().toString();
				if (!Files.isDirectory(entry) && skip.contains(name)) {
					continue;
				}
				Path dest = target.resolve(entry.getFileName());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(dest);
					copyDirectoryContents(entry, dest, skipFileNames);
				} else {
					Files.copy(entry, dest, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/**
	 * Copies all files and subdirectories from {@code originalState} to the base of {@code vsumState}.
	 * Does not copy {@code correspondences.correspondence}, as that file is treated differently (e.g. placed under vsum/).
	 *
	 * @param originalState directory containing the concrete model files
	 * @param vsumState     directory that will hold the vsum state (files are copied into its root)
	 */
	public static void copyOriginalStateToVsumRoot(Path originalState, Path vsumState) throws IOException {
		copyDirectoryContents(originalState, vsumState, CORRESPONDENCE_FILE_NAME);
	}

	/**
	 * Returns the directory at the same level as the given test class's .java file.
	 * When run with Maven, the class is in target/test-classes; we map that to
	 * src/test/java so both read and write happen next to the source file.
	 *
	 * @param testClass the test class (e.g. MyTest.class)
	 * @return the base path for test resources (directory containing the test class's package folder)
	 */
	public static Path getTestResourceBase(Class<?> testClass) throws Exception {
		URI resourceUri = testClass.getResource(".").toURI();
		Path classDir = Path.of(resourceUri).normalize();
		String pathStr = classDir.toString();
		String srcDirStr = pathStr
				.replace("target/test-classes", "src/test/java")
				.replace("target\\test-classes", "src\\test\\java");
		return Path.of(srcDirStr).normalize();
	}
}

// Copied unchanged except for the package from UncertainTreeDev master @ 7b067a6,
// BrakeCaseStudy/Maven_Project/vsum/src/test/java/tools/vitruv/methodologisttemplate/vsum/UncertainTreeTests/utils/CopyCorrespondencesUtils.java
package uncertaintree.mode2.cps1util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utilities for copying and adjusting correspondence files.
 * <p>
 * Correspondence files created in Eclipse have hrefs relative to the same directory as the
 * correspondence file. When the correspondence file is used under vsum (one directory level down,
 * e.g. in {@code vsum/}), hrefs must be updated to go one directory up by prefixing each with
 * {@code "../}.
 */
public class CopyCorrespondencesUtils {

	private static final String CORRESPONDENCE_FILE_NAME = "correspondences.correspondence";
	private static final String HREF_PREFIX = "../";

	/**
	 * If the directory contains {@value #CORRESPONDENCE_FILE_NAME}, updates each {@code href}
	 * attribute in that file by prefixing the link with {@value #HREF_PREFIX} (so that references
	 * point one directory up). If the file does not exist, prints an error message and returns
	 * without changing anything.
	 *
	 * @param directory directory that should contain the correspondence file (e.g. original model state or vsum target)
	 */
	public static void adjustCorrespondenceHrefsToParent(Path directory) throws IOException {
		Path correspondenceFile = directory.resolve(CORRESPONDENCE_FILE_NAME);
		if (!Files.isRegularFile(correspondenceFile)) {
			System.err.println("CopyCorrespondencesUtils: correspondence file not found: " + correspondenceFile);
			return;
		}
		String content = Files.readString(correspondenceFile, StandardCharsets.UTF_8);
		String updated = addHrefPrefixToParent(content);
		Files.writeString(correspondenceFile, updated, StandardCharsets.UTF_8);
	}

	/**
	 * Copies the correspondence file from the source directory to the target directory, then
	 * updates only the copy by prefixing each {@code href} with {@value #HREF_PREFIX}. The
	 * original file at the source is not modified.
	 *
	 * @param sourceDirectory directory containing the original {@value #CORRESPONDENCE_FILE_NAME}
	 * @param targetDirectory directory where the updated copy will be written (as {@value #CORRESPONDENCE_FILE_NAME})
	 * @throws IOException if the source file is missing, or reading/writing fails
	 */
	public static void copyAndAdjustCorrespondenceFile(Path sourceDirectory, Path targetDirectory) throws IOException {
		Path sourceFile = sourceDirectory.resolve(CORRESPONDENCE_FILE_NAME);
		if (!Files.isRegularFile(sourceFile)) {
			System.err.println("CopyCorrespondencesUtils: correspondence file not found: " + sourceFile);
			throw new IOException("Correspondence file not found: " + sourceFile);
		}
		String content = Files.readString(sourceFile, StandardCharsets.UTF_8);
		String updated = addHrefPrefixToParent(content);
		Files.createDirectories(targetDirectory);
		Path targetFile = targetDirectory.resolve(CORRESPONDENCE_FILE_NAME);
		Files.writeString(targetFile, updated, StandardCharsets.UTF_8);
	}

	private static String addHrefPrefixToParent(String content) {
		return content.replaceAll("href=\"(?!\\.\\./)([^\"]+)\"", "href=\"" + HREF_PREFIX + "$1\"");
	}
}

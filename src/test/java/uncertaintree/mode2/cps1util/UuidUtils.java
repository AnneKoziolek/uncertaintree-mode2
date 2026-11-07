// Copied unchanged except for the package from UncertainTreeDev master @ 7b067a6,
// BrakeCaseStudy/Maven_Project/vsum/src/test/java/tools/vitruv/methodologisttemplate/vsum/UncertainTreeTests/utils/UuidUtils.java
package uncertaintree.mode2.cps1util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;

public class UuidUtils {

	private static final String NON_READONLY_PREFIX = "ord_";
	private static final String CORRESPONDENCE_FILE_NAME = "correspondences.correspondence";
	private static final String UUID_LINE_SEPARATOR = "|";

	/**
	 * Generates a UUID string in the format used by Vitruvius for the uuid.uuid file (non-readonly prefix + EMF UUID).
	 * Use when pre-filling uuid.uuid for a loaded vsum state where Vitruvius has not yet assigned UUIDs.
	 *
	 * @return a raw UUID string suitable for a line in uuid.uuid (e.g. {@code ord__eDwLUBxtEfGyE9TZYl2eMA})
	 */
	public static String generateVitruviusUuid() {
		String uuid = NON_READONLY_PREFIX + EcoreUtil.generateUUID();
		return uuid;
	}

	/**
	 * Loads all model files from {@code modelDirectory} (except {@code correspondences.correspondence}),
	 * builds a UUID-to-hierarchical-ID mapping for every {@link EObject}, and writes the uuid.uuid file
	 * at {@code uuidFileOutputPath} in the format expected by Vitruvius (one line per element:
	 * {@code uuid|uri#fragment}).
	 * <p>
	 * The resource set uses the global {@link org.eclipse.emf.ecore.resource.Resource.Factory.Registry} and
	 * {@link org.eclipse.emf.ecore.EPackage.Registry}; ensure these are configured for your model types
	 * (e.g. in test {@code @BeforeAll}) before calling.
	 *
	 * @param modelDirectory   directory containing concrete model files (e.g. .brakesystem, .cad)
	 * @param uuidFileOutputPath path where the uuid.uuid file will be written (parent dirs created if needed)
	 * @throws IOException if listing/reading the directory or writing the file fails
	 */
	public static void writeUuidFileFromModelDirectory(Path modelDirectory, Path uuidFileOutputPath) throws IOException {
		ResourceSet resourceSet = new ResourceSetImpl();
		List<Resource> resources = loadModelResources(resourceSet, modelDirectory);
		List<String> lines = new ArrayList<>();
		for (Resource resource : resources) {
			for (EObject root : resource.getContents()) {
				appendUuidLineRecursive(lines, root);
			}
		}
		Path parent = uuidFileOutputPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(uuidFileOutputPath, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
	}

	/**
	 * Loads all files in {@code modelDirectory} as EMF resources, excluding {@code correspondences.correspondence}
	 * and non-files. Uses {@link URI#createFileURI(String)} for each file.
	 */
	private static List<Resource> loadModelResources(ResourceSet resourceSet, Path modelDirectory) throws IOException {
		List<Resource> resources = new ArrayList<>();
		try (var stream = Files.list(modelDirectory)) {
			for (Path entry : stream.toList()) {
				if (Files.isDirectory(entry)) {
					continue;
				}
				String fileName = entry.getFileName().toString();
				if (CORRESPONDENCE_FILE_NAME.equals(fileName)) {
					continue;
				}
				URI uri = URI.createFileURI(entry.toAbsolutePath().toString());
				Resource resource = resourceSet.getResource(uri, true);
				resources.add(resource);
			}
		}
		return resources;
	}

	private static void appendUuidLineRecursive(List<String> lines, EObject eObject) {
		String uuid = generateVitruviusUuid();
		String hierarchicalId = EcoreUtil.getURI(eObject).toString();
		lines.add(uuid + UUID_LINE_SEPARATOR + hierarchicalId);
		for (EObject child : eObject.eContents()) {
			appendUuidLineRecursive(lines, child);
		}
	}
}

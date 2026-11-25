package blockagent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 先寫暫存檔再搬過去，寫到一半當掉也不會留下半個檔案(docs/REVIEW.md M5)。 */
public final class AtomicFiles {
	private AtomicFiles() {
	}

	public static void writeString(Path target, String text) throws IOException {
		Path dir = target.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path temp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
		try {
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			try {
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}

package io.shaama.todoapp.infra.aws.lambda;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DockerfilePatcher {

    // Inserts LWA COPY right after the last FROM stage.
    // Returns absolute path to the patched file.
    public static String createPatchedDockerfile(String originalDockerfilePath) {
        return createPatchedDockerfile(originalDockerfilePath, "public.ecr.aws/awsguru/aws-lambda-adapter:0.9.1");
    }

    // Overload to allow custom adapter image tag if you ever need to bump versions.
    public static String createPatchedDockerfile(String originalDockerfilePath, String adapterImageRef) {
        try {
            Path original = Paths.get(originalDockerfilePath).toAbsolutePath().normalize();
            if (!Files.exists(original)) {
                throw new IllegalArgumentException("Dockerfile not found: " + original);
            }

            String content = Files.readString(original, StandardCharsets.UTF_8);

            // If already patched, just write a copy and return
            if (content.contains("/opt/extensions/lambda-adapter") || content.contains("aws-lambda-adapter")) {
                Path copy = siblingPatchedPath(original);
                Files.writeString(copy, content, StandardCharsets.UTF_8);
                return copy.toString();
            }

            // Find the last FROM (multistage safe)
            Pattern fromPattern = Pattern.compile("(?m)^\\s*FROM\\s+.+$");
            Matcher m = fromPattern.matcher(content);

            int lastFromEndIdx = -1;
            while (m.find()) {
                lastFromEndIdx = m.end();
            }
            if (lastFromEndIdx == -1) {
                throw new IllegalStateException("No FROM instruction found in Dockerfile: " + original);
            }

            // Normalize newline insertion
            String newline = content.contains("\r\n") ? "\r\n" : "\n";

            // Snippet to inject right after the last FROM
            String snippet =
                    newline +
                    "# Add Lambda Web Adapter as an extension (redirects HTTP from Lambda into app)" + newline +
                    "COPY --from=" + adapterImageRef + " \\" + newline +
                    "     /lambda-adapter /opt/extensions/lambda-adapter" + newline;

            String patched = content.substring(0, lastFromEndIdx) + snippet + content.substring(lastFromEndIdx);

            Path patchedFile = siblingPatchedPath(original);
            Files.writeString(patchedFile, patched, StandardCharsets.UTF_8);
            return patchedFile.toString();

        } catch (IOException e) {
            throw new RuntimeException("Failed to patch Dockerfile: " + e.getMessage(), e);
        }
    }

    private static Path siblingPatchedPath(Path original) {
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        String baseName = original.getFileName().toString();
        // e.g., Dockerfile.lambda -> Dockerfile.lambda.patched_20250101_120000
        String patchedName = baseName + ".patched_" + ts;
        return original.getParent().resolve(patchedName);
    }
}

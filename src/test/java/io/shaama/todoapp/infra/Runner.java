package io.shaama.todoapp.infra;

import java.io.File;

public class Runner {
    
    // Get project root directory (where build.gradle is located)
    public static File getProjectRoot() {
        String currentDir = System.getProperty("user.dir");
        File dir = new File(currentDir);
        
        // Look for build.gradle to confirm we're in project root
        while (dir != null && !new File(dir, "build.gradle").exists()) {
            dir = dir.getParentFile();
        }
        
        return dir != null ? dir : new File(currentDir);
    }
    
    public static void runDocker(String... args) {
        try {
            System.out.println("Running: docker " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("docker");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running Docker: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    public static void runAwsCli(String... args) {
        try {
            System.out.println("Running: aws " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("aws");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running AWS CLI: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    public static void runPulumiUp() {
        runPulumi("up");
    }
    
    public static void runPulumiDown() {
        runPulumi("destroy", "--yes");
    }
    
    public static void runPulumiDeploy() {
        runPulumi("up");
    }
    
    public static void runPulumi(String... args) {
        try {
            System.out.println("Running: pulumi " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("pulumi");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running Pulumi: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    public static void runAzureCli(String... args) {
        try {
            System.out.println("Running: az " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("az");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running Azure CLI: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    public static String runAzureCliWithOutput(String... args) {
        try {
            System.out.println("Running: az " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("az");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            
            Process process = pb.start();
            
            // Read the output
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Azure CLI command failed with exit code: " + exitCode);
            }
            
            return output.toString();
        } catch (Exception e) {
            System.err.println("Error running Azure CLI: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    public static void runCommand(String command) {
        try {
            System.out.println("Running: " + command);
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running command: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
package io.shaama.todoapp.infra.gcp.cloudrun;

import com.google.auth.oauth2.GoogleCredentials;


import com.google.protobuf.Duration;
import io.grpc.StatusRuntimeException;
import io.shaama.todoapp.infra.CloudDeployer;
import io.shaama.todoapp.infra.Runner;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ContainerPort;
import com.google.cloud.run.v2.EnvVar;
import com.google.cloud.run.v2.ExecutionTemplate;
import com.google.cloud.run.v2.IngressTraffic;
import com.google.cloud.run.v2.RevisionScaling;
import com.google.cloud.run.v2.RevisionTemplate;
import com.google.cloud.run.v2.ResourceRequirements;
import com.google.cloud.run.v2.Service;
import com.google.cloud.run.v2.ServiceName;
import com.google.cloud.run.v2.ServicesClient;
import com.google.cloud.run.v2.ServicesSettings;
import com.google.cloud.run.v2.TrafficTarget;
import com.google.cloud.run.v2.TrafficTargetAllocationType;
import com.google.cloud.run.v2.UpdateServiceRequest;
import com.google.cloud.run.v2.CreateServiceRequest;
import com.google.cloud.run.v2.DeleteServiceRequest;
import com.google.cloud.run.v2.RevisionsClient;
import com.google.cloud.run.v2.RevisionsSettings;
import com.google.devtools.artifactregistry.v1.ArtifactRegistryClient;
import com.google.devtools.artifactregistry.v1.ArtifactRegistrySettings;
import com.google.devtools.artifactregistry.v1.CreateRepositoryRequest;
import com.google.devtools.artifactregistry.v1.DeleteRepositoryRequest;
import com.google.devtools.artifactregistry.v1.Repository;

public class GcpCloudRunDeployer implements CloudDeployer {

    private GcpCloudRunConfig config;
    private String configFilename;
    private ServicesClient servicesClient;
    private RevisionsClient revisionsClient;
    private ArtifactRegistryClient artifactRegistryClient;

    @Override
    public void init(String configFilename) {
        System.out.println("GCP Cloud Run: Initializing with config file: " + configFilename);
        this.configFilename = configFilename;

        try {
            // Load config using SnakeYAML
            Yaml yaml = new Yaml();
            config = yaml.loadAs(new FileInputStream(configFilename), GcpCloudRunConfig.class);
            System.out.println("Loaded config: " + config);

            // Set project configuration automatically
            System.out.println("- Setting gcloud project to: " + config.getProjectId());
            setGcloudProject();

            // Initialize Google Cloud credentials
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();

            // Initialize Cloud Run clients
            ServicesSettings servicesSettings = ServicesSettings.newBuilder()
                    .setCredentialsProvider(() -> credentials)
                    .build();
            this.servicesClient = ServicesClient.create(servicesSettings);

            RevisionsSettings revisionsSettings = RevisionsSettings.newBuilder()
                    .setCredentialsProvider(() -> credentials)
                    .build();
            this.revisionsClient = RevisionsClient.create(revisionsSettings);

            // Initialize Artifact Registry client
            ArtifactRegistrySettings artifactRegistrySettings = ArtifactRegistrySettings.newBuilder()
                    .setCredentialsProvider(() -> credentials)
                    .build();
            this.artifactRegistryClient = ArtifactRegistryClient.create(artifactRegistrySettings);

            System.out.println("✅ GCP Cloud Run deployer initialized successfully");

        } catch (Exception e) {
            System.out.println("Failed to initialize GCP Cloud Run deployer: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize GCP Cloud Run deployer", e);
        }
    }

    @Override
    public void setup() {
        System.out.println("GCP Cloud Run: Setting up infrastructure for " + config.getServiceName());

        try {
            System.out.println("- Enabling required GCP services...");
            enableRequiredServices();

            System.out.println("- Creating Artifact Registry repository: " + config.getArtifactRegistryRepository());
            createArtifactRegistry();

            System.out.println("✅ GCP Cloud Run infrastructure setup completed!");

        } catch (Exception e) {
            System.out.println("Setup failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("GCP Cloud Run setup failed", e);
        }
    }

    @Override
    public void deploy() {
        System.out.println("GCP Cloud Run: Deploying " + config.getServiceName());

        try {
            // Build and push Docker image
            String imageUri = config.getImageUri() + ":latest";
            System.out.println("- Building and pushing Docker image: " + imageUri);
            buildAndPushImage(imageUri);

            System.out.println("- Deploying to Cloud Run");
            String serviceUrl = deployCloudRunService();

            System.out.println("\n=== GCP Cloud Run Deployment Complete ===");
            System.out.println("Service Name: " + config.getServiceName());
            System.out.println("Project ID: " + config.getProjectId());
            System.out.println("Region: " + config.getRegion());
            System.out.println("Service URL: " + serviceUrl);
            System.out.println("\nMCP Configuration:");
            printMcpConfig(serviceUrl);

        } catch (Exception e) {
            System.out.println("Deployment failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("GCP Cloud Run deployment failed", e);
        }
    }

    @Override
    public void destroy() {
        System.out.println("GCP Cloud Run: Starting aggressive cleanup for " + config.getServiceName());

        try {
            System.out.println("- Deleting Cloud Run service");
            deleteCloudRunService();

            System.out.println("- Deleting Artifact Registry repository");
            deleteArtifactRegistry();

            System.out.println("✅ GCP Cloud Run cleanup completed successfully!");

        } catch (Exception e) {
            System.out.println("Cleanup failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("GCP Cloud Run cleanup failed", e);
        }
    }

    private void createArtifactRegistry() {
        try {
            String repositoryName = String.format("projects/%s/locations/%s/repositories/%s",
                config.getProjectId(), config.getRegion(), config.getArtifactRegistryRepository());

            // Check if repository already exists
            try {
                Repository existingRepo = artifactRegistryClient.getRepository(repositoryName);
                System.out.println("Artifact Registry repository already exists: " + existingRepo.getName());
                return;
            } catch (com.google.api.gax.rpc.NotFoundException e) {
                // Repository doesn't exist, create it
                System.out.println("Repository doesn't exist, creating new one...");
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.UNAUTHENTICATED) {
                    System.err.println("❌ Authentication failed. Please ensure you have:");
                    System.err.println("   1. Run 'gcloud auth login' and 'gcloud auth application-default login'");
                    System.err.println("   2. Set project with 'gcloud config set project YOUR_PROJECT_ID'");
                    System.err.println("   3. Enabled required APIs: artifactregistry.googleapis.com, run.googleapis.com");
                    System.err.println("   4. Have proper IAM permissions for Artifact Registry and Cloud Run");
                    throw new RuntimeException("GCP Authentication failed. Please check credentials and permissions.", e);
                }
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    // Repository doesn't exist, create it
                    System.out.println("Repository doesn't exist, creating new one...");
                } else {
                    throw e;
                }
            }

            // Create repository
            String parent = String.format("projects/%s/locations/%s", config.getProjectId(), config.getRegion());
            Repository repository = Repository.newBuilder()
                    .setFormat(Repository.Format.DOCKER)
                    .setDescription("Repository for " + config.getServiceName())
                    .build();

            CreateRepositoryRequest request = CreateRepositoryRequest.newBuilder()
                    .setParent(parent)
                    .setRepositoryId(config.getArtifactRegistryRepository())
                    .setRepository(repository)
                    .build();

            Repository createdRepo = artifactRegistryClient.createRepositoryAsync(request).get(5, TimeUnit.MINUTES);
            System.out.println("Artifact Registry repository created successfully: " + createdRepo.getName());

        } catch (Exception e) {
            if (e.getMessage().contains("UNAUTHENTICATED") || e.getMessage().contains("authentication")) {
                System.err.println("❌ Authentication error detected. Please run:");
                System.err.println("   gcloud auth login");
                System.err.println("   gcloud auth application-default login");
                System.err.println("   gcloud config set project " + config.getProjectId());
            }
            throw new RuntimeException("Failed to create Artifact Registry repository", e);
        }
    }

    private void setGcloudProject() {
        try {
            System.out.println("  Setting gcloud default project...");
            runGcloudCommand("config", "set", "project", config.getProjectId());
            
            System.out.println("  Setting quota project for ADC...");
            runGcloudCommand("auth", "application-default", "set-quota-project", config.getProjectId());
            
            System.out.println("  ✅ Project configuration updated");
        } catch (Exception e) {
            System.err.println("  ⚠️  Warning: Failed to set project configuration automatically.");
            System.err.println("     Please run manually:");
            System.err.println("     gcloud config set project " + config.getProjectId());
            System.err.println("     gcloud auth application-default set-quota-project " + config.getProjectId());
            // Don't fail initialization for this - user can set manually
        }
    }

    private void enableRequiredServices() {
        try {
            System.out.println("  Enabling artifactregistry.googleapis.com...");
            runGcloudCommand("services", "enable", "artifactregistry.googleapis.com");
            
            System.out.println("  Enabling run.googleapis.com...");
            runGcloudCommand("services", "enable", "run.googleapis.com");
            
            System.out.println("  ✅ Required services enabled");
        } catch (Exception e) {
            System.err.println("  ⚠️  Warning: Failed to enable services automatically. Please run manually:");
            System.err.println("     gcloud services enable artifactregistry.googleapis.com run.googleapis.com");
            // Don't fail the setup for this - user can enable manually
        }
    }

    private void runGcloudCommand(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("gcloud");
        command.addAll(Arrays.asList(args));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // Read output
        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println("    " + line);
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("gcloud command failed with exit code: " + exitCode);
        }
    }

    private void buildAndPushImage(String imageUri) {
        try {
            System.out.println("  Building Docker image from " + config.getDockerfilePath());
            
            // Build the image for linux/amd64 platform (required for Cloud Run)
            Runner.runDocker("build",
                "--platform", "linux/amd64",
                "--provenance=false",
                "-t", imageUri,
                "-f", config.getDockerfilePath(),
                ".");
            
            // Configure Docker to use gcloud for authentication
            System.out.println("  Configuring Docker authentication for Artifact Registry");
            String registryUrl = config.getRegion() + "-docker.pkg.dev";
            runGcloudCommand("auth", "configure-docker", registryUrl, "--quiet");
            
            // Push the image to Artifact Registry
            System.out.println("  Pushing image to Artifact Registry: " + imageUri);
            Runner.runDocker("push", imageUri);
            
            System.out.println("  ✅ Docker image built and pushed successfully");
            
            // Give a moment for the image to be fully available in the registry
            System.out.println("  Waiting for image to be available in registry...");
            Thread.sleep(5000); // 5 second delay
            
        } catch (Exception e) {
            System.err.println("Failed to build and push Docker image: " + e.getMessage());
            throw new RuntimeException("Docker build and push failed", e);
        }
    }

    private String deployCloudRunService() {
        try {
            String imageUri = config.getImageUri() + ":latest";
            String serviceName = String.format("projects/%s/locations/%s/services/%s",
                    config.getProjectId(), config.getRegion(), config.getServiceName());

            // Build container spec
            Container.Builder containerBuilder = Container.newBuilder()
                    .setImage(imageUri)
                    .addPorts(ContainerPort.newBuilder()
                            .setName("http1")
                            .setContainerPort(config.getContainerPort())
                            .build())
                    .setResources(ResourceRequirements.newBuilder()
                            .putLimits("cpu", config.getCpu())
                            .putLimits("memory", config.getMemory())
                            .build());

            // Add environment variables
            for (Map.Entry<String, String> entry : config.getEnvironmentVariables().entrySet()) {
                containerBuilder.addEnv(EnvVar.newBuilder()
                        .setName(entry.getKey())
                        .setValue(entry.getValue())
                        .build());
            }

            // Build revision template - use the Builder methods directly
            RevisionTemplate.Builder revisionTemplateBuilder = RevisionTemplate.newBuilder()
                    .setScaling(RevisionScaling.newBuilder()
                            .setMinInstanceCount(config.getMinInstances())
                            .setMaxInstanceCount(config.getMaxInstances())
                            .build())
                    .setTimeout(Duration.newBuilder()
                            .setSeconds(config.getTimeout())
                            .build())
                    .addContainers(containerBuilder.build());

            // Build service configuration
            Service.Builder serviceBuilder = Service.newBuilder()
                    .setTemplate(revisionTemplateBuilder.build())
                    .addTraffic(TrafficTarget.newBuilder()
                            .setPercent(100)
                            .setType(TrafficTargetAllocationType.TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST)
                            .build());

            // Set ingress to allow all traffic if unauthenticated access is enabled
            if (config.isAllowUnauthenticated()) {
                serviceBuilder.setIngress(IngressTraffic.INGRESS_TRAFFIC_ALL);
            }

            Service service = serviceBuilder.build();

            // Check if service exists and update or create
            try {
                Service existingService = servicesClient.getService(serviceName);
                System.out.println("Service exists, updating...");

                Service updatedService = existingService.toBuilder()
                        .setTemplate(service.getTemplate())
                        .clearTraffic()
                        .addAllTraffic(service.getTrafficList())
                        .build();

                UpdateServiceRequest updateRequest = UpdateServiceRequest.newBuilder()
                        .setService(updatedService)
                        .build();

                Service result = servicesClient.updateServiceAsync(updateRequest).get(10, TimeUnit.MINUTES);
                System.out.println("Service updated successfully");
                
                // Set IAM policy for unauthenticated access if configured
                if (config.isAllowUnauthenticated()) {
                    setIamPolicyForUnauthenticatedAccess();
                }
                
                return result.getUri();

            } catch (Exception e) {
                // Handle both StatusRuntimeException and NotFoundException for NOT_FOUND
                boolean isNotFound = false;
                if (e instanceof StatusRuntimeException) {
                    StatusRuntimeException sre = (StatusRuntimeException) e;
                    isNotFound = sre.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND;
                } else if (e instanceof com.google.api.gax.rpc.NotFoundException) {
                    isNotFound = true;
                }
                
                if (isNotFound) {
                    System.out.println("Service not found, creating new service...");

                    String parent = String.format("projects/%s/locations/%s", config.getProjectId(), config.getRegion());
                    CreateServiceRequest createRequest = CreateServiceRequest.newBuilder()
                            .setParent(parent)
                            .setService(service)
                            .setServiceId(config.getServiceName())
                            .build();

                    Service result = servicesClient.createServiceAsync(createRequest).get(10, TimeUnit.MINUTES);
                    System.out.println("Service created successfully");
                    
                    // Set IAM policy for unauthenticated access if configured
                    if (config.isAllowUnauthenticated()) {
                        setIamPolicyForUnauthenticatedAccess();
                    }
                    
                    return result.getUri();
                } else {
                    throw e;
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to deploy Cloud Run service", e);
        }
    }
    private void deleteCloudRunService() {
        try {
            String serviceName = String.format("projects/%s/locations/%s/services/%s",
                config.getProjectId(), config.getRegion(), config.getServiceName());

            try {
                servicesClient.getService(serviceName);

                DeleteServiceRequest deleteRequest = DeleteServiceRequest.newBuilder()
                        .setName(serviceName)
                        .build();

                servicesClient.deleteServiceAsync(deleteRequest).get(5, TimeUnit.MINUTES);
                System.out.println("Cloud Run service deleted successfully");

            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    System.out.println("Cloud Run service not found, skipping deletion");
                } else {
                    throw e;
                }
            }

        } catch (Exception e) {
            System.out.println("Warning: Failed to delete Cloud Run service: " + e.getMessage());
        }
    }

    private void deleteArtifactRegistry() {
        try {
            String repositoryName = String.format("projects/%s/locations/%s/repositories/%s",
                config.getProjectId(), config.getRegion(), config.getArtifactRegistryRepository());

            try {
                artifactRegistryClient.getRepository(repositoryName);

                DeleteRepositoryRequest deleteRequest = DeleteRepositoryRequest.newBuilder()
                        .setName(repositoryName)
                        .build();

                artifactRegistryClient.deleteRepositoryAsync(deleteRequest).get(5, TimeUnit.MINUTES);
                System.out.println("Artifact Registry repository deleted successfully");

            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    System.out.println("Artifact Registry repository not found, skipping deletion");
                } else {
                    throw e;
                }
            }

        } catch (Exception e) {
            System.out.println("Warning: Failed to delete Artifact Registry repository: " + e.getMessage());
        }
    }

    private void setIamPolicyForUnauthenticatedAccess() {
        try {
            // Use gcloud command to set IAM policy for unauthenticated access
            // This is simpler and more reliable than using the Java SDK for IAM operations
            String command = String.format(
                "gcloud run services add-iam-policy-binding %s " +
                "--region=%s " +
                "--member=\"allUsers\" " +
                "--role=\"roles/run.invoker\" " +
                "--project=%s",
                config.getServiceName(),
                config.getRegion(),
                config.getProjectId()
            );

            Runner.runCommand(command);
            System.out.println("IAM policy set for unauthenticated access");

        } catch (Exception e) {
            System.out.println("Warning: Failed to set IAM policy for unauthenticated access: " + e.getMessage());
            System.out.println("You may need to run manually: gcloud run services add-iam-policy-binding " + 
                config.getServiceName() + " --region=" + config.getRegion() + 
                " --member=\"allUsers\" --role=\"roles/run.invoker\" --project=" + config.getProjectId());
        }
    }

    private void printMcpConfig(String serviceUrl) {

        System.out.println("\nMCP Configuration:");
        System.out.println("Add this to your MCP client config:");
        System.out.println("\"" + config.getServiceName()  + "\": {");
        System.out.println("  \"type\": \"http\",");
        System.out.println("  \"url\": \"" + serviceUrl + "/mcp\"");
        System.out.println("}");

        
    }

    @Override
    public void showLogs() {
        try {
            System.out.println("=== Showing logs for Cloud Run service: " + config.getServiceName() + " ===");
            System.out.println("Project: " + config.getProjectId());
            System.out.println("Region: " + config.getRegion());
            System.out.println("Press Ctrl+C to stop...\n");
            
            // Show recent logs first
            System.out.println("📜 Fetching recent logs...");
            String recentLogsCommand = String.format(
                "gcloud beta run services logs read %s " +
                "--project=%s " +
                "--region=%s " +
                "--limit=50",
                config.getServiceName(),
                config.getProjectId(),
                config.getRegion()
            );
            
            Runner.runCommand(recentLogsCommand);
            
            System.out.println("\n🔄 Polling for new logs every 10 seconds... (Press Ctrl+C to stop)");
            
            // Simple polling without timestamp filtering
            while (true) {
                try {
                    Thread.sleep(10000); // Wait 10 seconds
                    
                    System.out.println("\n--- Checking for new logs ---");
                    String pollCommand = String.format(
                        "gcloud beta run services logs read %s " +
                        "--project=%s " +
                        "--region=%s " +
                        "--limit=10",
                        config.getServiceName(),
                        config.getProjectId(),
                        config.getRegion()
                    );
                    
                    Runner.runCommand(pollCommand);
                    
                } catch (InterruptedException e) {
                    System.out.println("\n� Log polling stopped by user");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("⚠️  Error polling logs: " + e.getMessage());
                    Thread.sleep(5000); // Wait before retrying
                }
            }

        } catch (Exception e) {
            System.err.println("Failed to show logs: " + e.getMessage());
            System.err.println("\nYou can manually run:");
            System.err.println("gcloud beta run services logs read " + config.getServiceName() +
                " --project=" + config.getProjectId() + " --region=" + config.getRegion() + " --limit=50");
        }
    }
}